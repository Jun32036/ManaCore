package io.github.jun32036.manacore;

import io.github.jun32036.manacore.data.Config;
import io.github.jun32036.manacore.data.PlayerState;
import io.github.jun32036.manacore.data.WorldState;
import io.github.jun32036.manacore.logic.EconMath;
import io.github.jun32036.manacore.logic.ValueDerive;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 有目標的事：城鎮訂單、村民委託、生命祭壇、煉金爐、工程。 */
public final class Features {

    private final ManaCore mod;
    /** 物品 → 價值（煉金值） */
    private final Map<Item, Long> values = new HashMap<>();

    public Features(ManaCore mod) {
        this.mod = mod;
    }

    private Config cfg() {
        return mod.config();
    }

    private Mana mana() {
        return mod.mana();
    }

    // ======================================================================
    // 物品工具
    // ======================================================================

    public static Item item(String id) {
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (rl == null) return Items.AIR;
        return BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
    }

    public static Component name(Item item) {
        return item.getName(item.getDefaultInstance());
    }

    public static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }

    /** 素的物品：沒有自訂名稱、附魔等額外資料 */
    public static boolean plain(ItemStack st) {
        return !st.isEmpty() && st.getComponentsPatch().isEmpty();
    }

    public static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack st : p.getInventory()) if (st.is(item) && plain(st)) n += st.getCount();
        return n;
    }

    public static void take(ServerPlayer p, Item item, int amount) {
        int left = amount;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(item) || !plain(st)) continue;
            int t = Math.min(left, st.getCount());
            st.shrink(t);
            left -= t;
        }
        inv.setChanged();
    }

    public static void give(ServerPlayer p, Item item, int amount) {
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            ItemStack st = new ItemStack(item, n);
            if (!p.getInventory().add(st)) p.drop(st, false, net.minecraft.util.Prediction.SERVER_ONLY);
            left -= n;
        }
    }

    // ======================================================================
    // 城鎮訂單：每個遊戲日抽 perDay 張，每張每天交一次
    // ======================================================================

    public List<Config.OrderDef> todaysOrders(ServerPlayer p) {
        long day = mana().day(p);
        List<EconMath.Candidate> cands = new ArrayList<>();
        Map<String, Config.OrderDef> byId = new HashMap<>();
        for (Config.OrderDef o : cfg().orders.pool) {
            cands.add(new EconMath.Candidate(o.id, o.skill));
            byId.put(o.id, o);
        }
        long seed = p.level().getServer().overworld().getSeed();
        List<Config.OrderDef> out = new ArrayList<>();
        for (String id : EconMath.pickOrders(seed + "#" + day, cands, cfg().orders.perDay, cfg().orders.maxPerSkill)) out.add(byId.get(id));
        return out;
    }

    public boolean deliverOrder(ServerPlayer p, String id) {
        PlayerState s = mana().state(p);
        Config.OrderDef o = todaysOrders(p).stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
        if (o == null) return fail(p, "這張訂單已經過期了。");
        if (s.ordersToday.contains(id)) return fail(p, "今天已經交過這張訂單了。");
        Mana.Skill k = skill(o.skill);
        if (k != null && mana().level(s, k) < o.level) return fail(p, "這張訂單需要「" + k.display + "」Lv" + o.level + "。");
        Item it = item(o.item);
        if (count(p, it) < o.amount) return fail(p, "需要 " + o.amount + " 個 " + name(it).getString() + "。");
        if (mana().remaining(s, Mana.Source.ORDER) == 0) return fail(p, "今天城鎮訂單的 mana 已經滿了，明天再交吧（東西先留著）。");
        take(p, it, o.amount);
        s.ordersToday.add(id);
        mana().earn(p, Mana.Source.ORDER, o.reward, Component.literal("城鎮訂單：").append(name(it)).append(" ×" + o.amount));
        if (k != null) mana().addXp(p, s, k, o.reward * 2.0);
        p.level().playSound(null, p.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.6f, 1.1f);
        return true;
    }

    public static Mana.Skill skill(String key) {
        for (Mana.Skill k : Mana.Skill.values()) if (k.key.equals(key)) return k;
        return null;
    }

    // ======================================================================
    // 村民委託：每個遊戲日，附近的村民可能有事想請你幫忙；蹲下右鍵他交貨
    // ======================================================================

    /** 每秒呼叫：跨日時替附近的村民發委託 */
    public void tickRequests(MinecraftServer server) {
        WorldState w = mod.world();
        ServerLevel ow = server.overworld();
        long day = Math.floorDiv(ow.getOverworldClockTime(), 24000L);
        if (w.lastRequestDay == day) return;
        w.lastRequestDay = day;
        w.requests.values().removeIf(r -> day - r.day > 3); // 三天沒完成就過期
        Config.Villager vc = cfg().villager;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            long active = w.requests.values().stream().filter(r -> p.getUUID().toString().equals(r.player)).count();
            if (active >= vc.maxActive || p.getRandom().nextDouble() > vc.chancePerDay) continue;
            ServerLevel lvl = p.level();
            List<Villager> near = lvl.getEntitiesOfClass(Villager.class, new AABB(p.blockPosition()).inflate(vc.radius),
                    v -> !v.isBaby() && !w.requests.containsKey(v.getUUID().toString()));
            if (near.isEmpty()) continue;
            Villager v = near.get(p.getRandom().nextInt(near.size()));
            String prof = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
            List<Config.Request> pool = vc.requests.getOrDefault(prof, vc.requests.get("none"));
            if (pool == null || pool.isEmpty()) continue;
            Config.Request rq = pool.get(p.getRandom().nextInt(pool.size()));
            WorldState.VillagerRequest r = new WorldState.VillagerRequest();
            r.item = rq.item;
            r.amount = rq.amount;
            r.reward = rq.reward;
            r.day = day;
            r.player = p.getUUID().toString();
            w.requests.put(v.getUUID().toString(), r);
            v.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 30, 0, false, false));
            p.sendSystemMessage(Component.literal("✉ 附近有位村民想請你幫忙：").withStyle(ChatFormatting.GOLD)
                    .append(name(item(r.item))).append(" ×" + r.amount)
                    .append(Component.literal("（他會發光 30 秒；蹲下右鍵他交貨，3 天內有效）").withStyle(ChatFormatting.GRAY)));
            mod.markDirty();
        }
    }

    /** 蹲下右鍵村民：看委託、交貨 */
    public boolean interactVillager(ServerPlayer p, Villager v) {
        WorldState.VillagerRequest r = mod.world().requests.get(v.getUUID().toString());
        if (r == null) return false;
        Item it = item(r.item);
        int have = count(p, it);
        if (have < r.amount) {
            p.sendSystemMessage(Component.literal("這位村民需要 ").withStyle(ChatFormatting.YELLOW).append(name(it)).append(" ×" + r.amount)
                    .append(Component.literal("（你身上有 " + have + "），可得 " + r.reward + " mana").withStyle(ChatFormatting.GRAY)));
            return true;
        }
        take(p, it, r.amount);
        mod.world().requests.remove(v.getUUID().toString());
        mana().earn(p, Mana.Source.VILLAGER, r.reward, Component.literal("幫村民的忙：").append(name(it)).append(" ×" + r.amount));
        p.level().playSound(null, v.blockPosition(), SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1f, 1f);
        mod.markDirty();
        return true;
    }

    public List<WorldState.VillagerRequest> requestsOf(ServerPlayer p) {
        List<WorldState.VillagerRequest> out = new ArrayList<>();
        for (WorldState.VillagerRequest r : mod.world().requests.values()) if (p.getUUID().toString().equals(r.player)) out.add(r);
        return out;
    }

    // ======================================================================
    // 生命祭壇：用 mana 換物資（一組一組換）
    // ======================================================================

    public boolean buy(ServerPlayer p, String category, int index, int lots) {
        Config.AltarCategory c = cfg().altar.stream().filter(x -> x.id.equals(category)).findFirst().orElse(null);
        if (c == null || index < 0 || index >= c.items.size()) return false;
        Config.AltarItem ai = c.items.get(index);
        Item it = item(ai.item);
        if (it == Items.AIR) return false;
        long price = priceFor(p, c, ai);
        int bought = 0;
        for (int i = 0; i < Math.max(1, lots); i++) {
            if (!mana().spend(p, price, "祭壇：" + ai.amount + "x " + ai.item)) break;
            give(p, it, ai.amount);
            bought++;
        }
        if (bought > 0) p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.2f);
        return bought > 0;
    }

    /** 建造等級高的人，買建材類有折扣 */
    public long priceFor(ServerPlayer p, Config.AltarCategory c, Config.AltarItem ai) {
        if (!c.discount) return ai.price;
        double d = mana().passive(mana().state(p), Mana.Skill.BUILD);
        return d <= 0 ? ai.price : Math.max(1, Math.round(ai.price * (1 - d)));
    }

    // ======================================================================
    // 價值表與煉金爐（等價交換：物品換物品，不產生 mana）
    // ======================================================================

    public void buildValues(MinecraftServer server) {
        values.clear();
        Map<Item, Long> base = new HashMap<>();
        for (Map.Entry<String, Long> e : cfg().values.entrySet()) {
            Item it = item(e.getKey());
            if (it != Items.AIR && e.getValue() > 0) base.put(it, e.getValue());
        }
        List<ValueDerive.Rec<Item>> recs = new ArrayList<>();
        ContextMap ctx = SlotDisplayContext.fromLevel(server.overworld());
        for (RecipeHolder<?> h : server.getRecipeManager().getRecipes()) {
            Recipe<?> r = h.value();
            for (RecipeDisplay d : r.display()) {
                SlotDisplay result;
                List<SlotDisplay> ings;
                if (d instanceof ShapedCraftingRecipeDisplay s) { result = s.result(); ings = s.ingredients(); }
                else if (d instanceof ShapelessCraftingRecipeDisplay s) { result = s.result(); ings = s.ingredients(); }
                else if (d instanceof FurnaceRecipeDisplay s) { result = s.result(); ings = List.of(s.ingredient()); }
                else if (d instanceof StonecutterRecipeDisplay s) { result = s.result(); ings = List.of(s.input()); }
                else continue;
                ItemStack out = result.resolveForFirstStack(ctx);
                if (out.isEmpty() || !plain(out)) continue;
                List<List<Item>> slots = new ArrayList<>();
                boolean ok = true;
                for (SlotDisplay sd : ings) {
                    List<ItemStack> opts = sd.resolveForStacks(ctx);
                    if (opts.isEmpty()) continue; // 空格
                    List<Item> items = new ArrayList<>();
                    for (ItemStack st : opts) items.add(st.getItem());
                    if (items.isEmpty()) { ok = false; break; }
                    slots.add(items);
                }
                if (ok && !slots.isEmpty()) recs.add(new ValueDerive.Rec<>(out.getItem(), out.getCount(), slots));
            }
        }
        values.putAll(ValueDerive.derive(base, recs, 25));
        for (Map.Entry<String, Long> e : cfg().values.entrySet()) if (e.getValue() <= 0) values.remove(item(e.getKey()));
        ManaCore.LOG.info("ManaCore 價值表：基礎 {} 種，由 {} 條配方推算後共 {} 種", base.size(), recs.size(), values.size());
    }

    public long value(Item it) {
        return values.getOrDefault(it, 0L);
    }

    public long yieldFor(Item it, int n) {
        return value(it) * n * (100 - cfg().alchemy.lossPercent) / 100;
    }

    public boolean deposit(ServerPlayer p, boolean all) {
        ItemStack hand = p.getMainHandItem();
        if (hand.isEmpty()) return fail(p, "請先把要投入的物品拿在主手上。");
        if (!plain(hand)) return fail(p, "帶附魔或改過名的物品不能投入煉金爐。");
        Item it = hand.getItem();
        if (value(it) <= 0) return fail(p, "煉金爐不認識這個物品。");
        int n = all ? count(p, it) : hand.getCount();
        if (all) take(p, it, n);
        else hand.shrink(n);
        PlayerState s = mana().state(p);
        long got = yieldFor(it, n);
        s.alchemy += got;
        boolean learned = s.known.add(id(it));
        p.sendSystemMessage(Component.literal("投入 ").withStyle(ChatFormatting.LIGHT_PURPLE).append(name(it)).append(" ×" + n + "，得到 " + got + " 煉金值" + (learned ? "，學會了這種物品。" : "。")));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 0.6f, 1.3f);
        mod.markDirty();
        return true;
    }

    public boolean withdraw(ServerPlayer p, String itemId, int want) {
        PlayerState s = mana().state(p);
        Item it = item(itemId);
        long v = value(it);
        if (v <= 0) return false;
        if (cfg().alchemy.learnRequired && !s.known.contains(id(it))) return fail(p, "你還沒學會這個物品，先投入一個。");
        int n = (int) Math.min(want, Math.min(it.getDefaultMaxStackSize(), s.alchemy / v));
        if (n <= 0) return fail(p, "煉金值不夠：換 1 個需要 " + v + "，你有 " + s.alchemy + "。");
        s.alchemy -= v * n;
        give(p, it, n);
        p.level().playSound(null, p.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 0.5f, 1.4f);
        mod.markDirty();
        return true;
    }

    public List<String> knownSorted(PlayerState s) {
        List<String> out = new ArrayList<>(s.known);
        out.removeIf(x -> value(item(x)) <= 0);
        out.sort(Comparator.comparingLong((String x) -> value(item(x))).reversed().thenComparing(x -> x));
        return out;
    }

    // ======================================================================
    // 工程：自己的建設目標（交物資、投入 mana，完工拿獎勵）
    // ======================================================================

    public Config.Project project(String id) {
        return cfg().projects.stream().filter(x -> x.id.equals(id)).findFirst().orElse(null);
    }

    public boolean donate(ServerPlayer p, String projectId, String itemId) {
        Config.Project pr = project(projectId);
        PlayerState s = mana().state(p);
        if (pr == null || s.projectsDone.contains(projectId)) return false;
        Integer goal = pr.needs.get(itemId);
        if (goal == null) return false;
        Map<String, Integer> have = s.projectHave.computeIfAbsent(projectId, k -> new HashMap<>());
        int missing = goal - have.getOrDefault(itemId, 0);
        if (missing <= 0) return fail(p, "這項物資已經湊齊了。");
        Item it = item(itemId);
        int n = Math.min(missing, count(p, it));
        if (n <= 0) return fail(p, "你身上沒有 " + name(it).getString() + "。");
        take(p, it, n);
        have.merge(itemId, n, Integer::sum);
        mana().addXp(p, s, Mana.Skill.BUILD, n / 8.0);
        p.sendSystemMessage(Component.literal("為「" + pr.name + "」交了 ").withStyle(ChatFormatting.GREEN).append(name(it)).append(" ×" + n));
        checkProject(p, pr, s);
        mod.markDirty();
        return true;
    }

    public boolean invest(ServerPlayer p, String projectId, long amount) {
        Config.Project pr = project(projectId);
        PlayerState s = mana().state(p);
        if (pr == null || s.projectsDone.contains(projectId)) return false;
        long missing = pr.mana - s.projectMana.getOrDefault(projectId, 0L);
        if (missing <= 0) return fail(p, "這項工程的 mana 已經湊齊了，還缺物資。");
        long a = Math.min(amount, missing);
        if (!mana().spend(p, a, "投入「" + pr.name + "」")) return false;
        s.projectMana.merge(projectId, a, Long::sum);
        checkProject(p, pr, s);
        mod.markDirty();
        return true;
    }

    private void checkProject(ServerPlayer p, Config.Project pr, PlayerState s) {
        Map<String, Integer> have = s.projectHave.getOrDefault(pr.id, Map.of());
        for (Map.Entry<String, Integer> e : pr.needs.entrySet()) if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return;
        if (s.projectMana.getOrDefault(pr.id, 0L) < pr.mana) return;
        s.projectsDone.add(pr.id);
        p.sendSystemMessage(Component.literal("🏗 「" + pr.name + "」完工了！").withStyle(ChatFormatting.GOLD));
        p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8f, 1f);
        if (pr.reward != null) {
            if (pr.reward.mana > 0) mana().earn(p, Mana.Source.PROJECT, pr.reward.mana, Component.literal("「" + pr.name + "」完工"));
            for (Map.Entry<String, Integer> e : pr.reward.items.entrySet()) give(p, item(e.getKey()), e.getValue());
        }
    }

    private static boolean fail(ServerPlayer p, String msg) {
        p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY));
        return false;
    }
}
