package io.github.jun32036.manacore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.jun32036.manacore.data.Config;
import io.github.jun32036.manacore.data.PlayerState;
import io.github.jun32036.manacore.data.WorldState;
import io.github.jun32036.manacore.logic.EconMath;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 伺服器與畫面之間的訊息：
 * - Sync（伺服器→畫面）：整份畫面需要的資料（JSON），open=true 時打開畫面
 * - Action（畫面→伺服器）：玩家按了什麼按鈕
 */
public final class Net {

    public record Sync(String json, boolean open) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ManaCore.MOD_ID, "sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1 << 20), Sync::json, ByteBufCodecs.BOOL, Sync::open, Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Action(String action, String arg, int count) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ManaCore.MOD_ID, "action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256), Action::action, ByteBufCodecs.stringUtf8(256), Action::arg, ByteBufCodecs.VAR_INT, Action::count, Action::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(Sync.TYPE, Sync.CODEC, 1 << 20);
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
    }

    public static void registerServer(ManaCore mod) {
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            Features f = mod.features();
            String a = payload.action(), arg = payload.arg();
            int n = Math.max(1, Math.min(64, payload.count()));
            switch (a) {
                case "open", "refresh" -> { }
                case "order" -> f.deliverOrder(p, arg);
                case "buy" -> {
                    String[] parts = arg.split(":");
                    if (parts.length == 2) f.buy(p, parts[0], parseInt(parts[1]), Math.min(n, 5));
                }
                case "deposit" -> f.deposit(p, false);
                case "deposit_all" -> f.deposit(p, true);
                case "withdraw" -> f.withdraw(p, arg, n);
                case "donate" -> {
                    String[] parts = arg.split(":");
                    if (parts.length == 2) f.donate(p, parts[0], parts[1]);
                }
                case "invest" -> f.invest(p, arg, payload.count());
                default -> { }
            }
            send(mod, p, a.equals("open"));
        });
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    public static void send(ManaCore mod, ServerPlayer p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Sync.TYPE)) {
            if (open) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("你的客戶端沒有安裝 ManaCore，看不到選單。用 /mana status 查看進度。"));
            return;
        }
        ServerPlayNetworking.send(p, new Sync(snapshot(mod, p).toString(), open));
    }

    // ======================================================================
    // 畫面需要的資料
    // ======================================================================

    public static JsonObject snapshot(ManaCore mod, ServerPlayer p) {
        Mana m = mod.mana();
        Features f = mod.features();
        Config cfg = mod.config();
        PlayerState s = m.state(p);
        JsonObject o = new JsonObject();
        o.addProperty("balance", s.balance);
        o.addProperty("day", s.day);

        JsonArray today = new JsonArray();
        for (Mana.Source src : Mana.Source.values()) {
            JsonObject t = new JsonObject();
            t.addProperty("name", src.display);
            t.addProperty("got", s.today(src.key));
            t.addProperty("cap", m.cap(src));
            today.add(t);
        }
        o.add("today", today);

        JsonArray skills = new JsonArray();
        for (Mana.Skill k : Mana.Skill.values()) {
            int lv = m.level(s, k);
            JsonObject t = new JsonObject();
            t.addProperty("name", k.display);
            t.addProperty("icon", k.icon);
            t.addProperty("how", k.how);
            t.addProperty("level", lv);
            t.addProperty("title", m.title(lv));
            t.addProperty("xp", Math.floor(s.xp.getOrDefault(k.key, 0.0)));
            t.addProperty("prev", lv - 1 < cfg.craft.levels.size() ? cfg.craft.levels.get(lv - 1) : 0);
            t.addProperty("next", EconMath.nextLevelXp(lv, cfg.craft.levels));
            t.addProperty("today", s.skillToday.getOrDefault(k.key, 0L));
            t.addProperty("cap", cfg.dayCaps.craftPerSkill);
            t.addProperty("fresh", m.freshness(p.getUUID(), k));
            t.addProperty("variety", m.varietyFactor(p.getUUID(), k));
            t.addProperty("passive", m.passive(s, k));
            skills.add(t);
        }
        o.add("skills", skills);

        JsonObject dex = new JsonObject();
        for (String cat : Mana.DEX_NAMES.keySet()) {
            JsonArray arr = new JsonArray();
            for (String key : s.found) if (key.startsWith("dex:" + cat + ":")) arr.add(key.substring(("dex:" + cat + ":").length()));
            dex.add(cat, arr);
        }
        o.add("dex", dex);
        o.addProperty("explored", s.found.stream().filter(x -> x.startsWith("biome:") || x.startsWith("structure:")).count());

        JsonArray orders = new JsonArray();
        for (Config.OrderDef od : f.todaysOrders(p)) {
            Item it = Features.item(od.item);
            Mana.Skill k = Features.skill(od.skill);
            JsonObject t = new JsonObject();
            t.addProperty("id", od.id);
            t.addProperty("item", od.item);
            t.addProperty("amount", od.amount);
            t.addProperty("reward", od.reward);
            t.addProperty("skill", k == null ? od.skill : k.display);
            t.addProperty("level", od.level);
            t.addProperty("myLevel", k == null ? 99 : m.level(s, k));
            t.addProperty("done", s.ordersToday.contains(od.id));
            t.addProperty("have", Features.count(p, it));
            orders.add(t);
        }
        o.add("orders", orders);
        o.addProperty("orderLeft", m.remaining(s, Mana.Source.ORDER));

        JsonArray reqs = new JsonArray();
        for (WorldState.VillagerRequest r : f.requestsOf(p)) {
            JsonObject t = new JsonObject();
            t.addProperty("item", r.item);
            t.addProperty("amount", r.amount);
            t.addProperty("reward", r.reward);
            t.addProperty("daysLeft", Math.max(0, 3 - (s.day - r.day)));
            t.addProperty("have", Features.count(p, Features.item(r.item)));
            reqs.add(t);
        }
        o.add("requests", reqs);

        JsonArray altar = new JsonArray();
        for (Config.AltarCategory c : cfg.altar) {
            JsonObject t = new JsonObject();
            t.addProperty("id", c.id);
            t.addProperty("name", c.name);
            t.addProperty("icon", c.icon);
            JsonArray items = new JsonArray();
            for (Config.AltarItem ai : c.items) {
                JsonObject x = new JsonObject();
                x.addProperty("item", ai.item);
                x.addProperty("amount", ai.amount);
                x.addProperty("price", f.priceFor(p, c, ai));
                x.addProperty("base", ai.price);
                items.add(x);
            }
            t.add("items", items);
            altar.add(t);
        }
        o.add("altar", altar);

        JsonObject al = new JsonObject();
        al.addProperty("av", s.alchemy);
        al.addProperty("loss", cfg.alchemy.lossPercent);
        ItemStack hand = p.getMainHandItem();
        al.addProperty("hand", hand.isEmpty() ? "" : Features.id(hand.getItem()));
        al.addProperty("handCount", hand.getCount());
        al.addProperty("handValue", hand.isEmpty() || !Features.plain(hand) ? 0 : f.value(hand.getItem()));
        al.addProperty("handYield", hand.isEmpty() || !Features.plain(hand) ? 0 : f.yieldFor(hand.getItem(), hand.getCount()));
        JsonArray known = new JsonArray();
        for (String id : f.knownSorted(s)) {
            JsonObject x = new JsonObject();
            x.addProperty("item", id);
            x.addProperty("value", f.value(Features.item(id)));
            known.add(x);
        }
        al.add("known", known);
        o.add("alchemy", al);

        JsonArray projects = new JsonArray();
        for (Config.Project pr : cfg.projects) {
            JsonObject t = new JsonObject();
            t.addProperty("id", pr.id);
            t.addProperty("name", pr.name);
            t.addProperty("description", pr.description);
            t.addProperty("icon", pr.icon);
            t.addProperty("done", s.projectsDone.contains(pr.id));
            t.addProperty("mana", s.projectMana.getOrDefault(pr.id, 0L));
            t.addProperty("manaGoal", pr.mana);
            JsonArray needs = new JsonArray();
            Map<String, Integer> have = s.projectHave.getOrDefault(pr.id, Map.of());
            for (Map.Entry<String, Integer> e : pr.needs.entrySet()) {
                JsonObject x = new JsonObject();
                x.addProperty("item", e.getKey());
                x.addProperty("goal", e.getValue());
                x.addProperty("have", have.getOrDefault(e.getKey(), 0));
                x.addProperty("mine", Features.count(p, Features.item(e.getKey())));
                needs.add(x);
            }
            t.add("needs", needs);
            projects.add(t);
        }
        o.add("projects", projects);

        JsonArray log = new JsonArray();
        for (String line : List.copyOf(s.log)) log.add(line);
        o.add("log", log);
        return o;
    }
}
