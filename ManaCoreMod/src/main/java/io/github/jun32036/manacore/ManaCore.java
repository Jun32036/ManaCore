package io.github.jun32036.manacore;

import com.mojang.brigadier.Command;
import io.github.jun32036.manacore.data.Config;
import io.github.jun32036.manacore.data.PlayerState;
import io.github.jun32036.manacore.data.WorldState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ManaCore：讓原版生存的每件事都有一點意義。
 * 遊戲邏輯全部在伺服器端（單人遊戲時是內建伺服器）；客戶端只負責選單畫面。
 */
public final class ManaCore implements ModInitializer {

    public static final String MOD_ID = "manacore";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static ManaCore instance;

    private Config config;
    private WorldState world = new WorldState();
    private final Mana mana = new Mana(this);
    private final Features features = new Features(this);
    private final Hooks hooks = new Hooks(this);
    private MinecraftServer server;
    private boolean dirty;

    public static ManaCore get() {
        return instance;
    }

    public Config config() {
        return config;
    }

    public WorldState world() {
        return world;
    }

    public Mana mana() {
        return mana;
    }

    public Features features() {
        return features;
    }

    public Hooks hooks() {
        return hooks;
    }

    public void markDirty() {
        dirty = true;
    }

    @Override
    public void onInitialize() {
        instance = this;
        config = Config.load();
        Net.registerPayloads();
        Net.registerServer(this);

        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            world = WorldState.load(s);
            features.buildValues(s);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, rm, ok) -> {
            if (ok) features.buildValues(s);
        });
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> save());
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            save();
            server = null;
        });

        ServerTickEvents.END_SERVER_TICK.register(s -> {
            int t = s.getTickCount();
            if (t % 20 == 0) features.tickRequests(s);
            if (t % 100 == 0) for (ServerPlayer p : s.getPlayerList().getPlayers()) hooks.tickExplore(p);
            if (t % 1200 == 0) save(); // 每分鐘存一次，避免當機掉進度
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> Net.send(this, handler.player, false));

        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> {
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel sl) hooks.onBreak(sp, sl, pos, state);
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown() || !(entity instanceof Villager v)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) {
                // 客戶端：有委託的村民交給伺服器判斷，這裡不攔
                return InteractionResult.PASS;
            }
            return features.interactVillager(sp, v) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> dispatcher.register(Commands.literal("mana")
                .executes(c -> {
                    Net.send(this, c.getSource().getPlayerOrException(), true);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("menu").executes(c -> {
                    Net.send(this, c.getSource().getPlayerOrException(), true);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("status").executes(c -> {
                    status(c.getSource().getPlayerOrException());
                    return Command.SINGLE_SUCCESS;
                }))));

        LOG.info("ManaCore 已載入");
    }

    private void save() {
        if (server != null && dirty) {
            world.save(server);
            dirty = false;
        }
    }

    /** 沒裝客戶端或想快速看的時候：聊天欄文字版 */
    private void status(ServerPlayer p) {
        PlayerState s = mana.state(p);
        p.sendSystemMessage(Component.literal("◆ mana：" + s.balance + "（第 " + s.day + " 天）").withStyle(ChatFormatting.AQUA));
        StringBuilder sb = new StringBuilder("今天：");
        for (Mana.Source src : Mana.Source.values()) {
            long cap = mana.cap(src);
            sb.append(src.display).append(' ').append(s.today(src.key)).append(cap > 0 ? "/" + cap : "").append("  ");
        }
        p.sendSystemMessage(Component.literal(sb.toString()).withStyle(ChatFormatting.GRAY));
        StringBuilder sk = new StringBuilder("手藝：");
        for (Mana.Skill k : Mana.Skill.values()) sk.append(k.display).append(" Lv").append(mana.level(s, k)).append("  ");
        p.sendSystemMessage(Component.literal(sk.toString()).withStyle(ChatFormatting.GRAY));
    }

    // ======================================================================
    // 原版成就（由 mixin 呼叫）
    // ======================================================================

    public void onAdvancement(ServerPlayer p, AdvancementHolder h) {
        if (p.isCreative()) return;
        String id = h.id().toString();
        if (h.id().getPath().startsWith("recipes/")) return;
        DisplayInfo d = h.value().display().orElse(null);
        if (d == null) return; // 沒有顯示的是內部用的，不算
        PlayerState s = mana.state(p);
        String key = "adv:" + id;
        if (s.found.contains(key)) return;
        Config.Advancement c = config.advancement;
        long amount = c.overrides.getOrDefault(id, switch (d.type()) {
            case TASK -> c.task;
            case GOAL -> c.goal;
            case CHALLENGE -> c.challenge;
        });
        s.found.add(key);
        mana.earn(p, Mana.Source.ADVANCEMENT, amount, Component.literal("成就「").append(d.title()).append("」"));
    }
}
