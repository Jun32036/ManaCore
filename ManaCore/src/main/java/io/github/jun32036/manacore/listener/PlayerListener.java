package io.github.jun32036.manacore.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import io.github.jun32036.manacore.Source;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.advancement.Advancement;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import io.github.jun32036.manacore.ManaCorePlugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** 進出伺服器、AFK 判定、點擊祭壇。（生產收成在 1.3.0 移到 LifeListener，改成生活手藝） */
public final class PlayerListener implements Listener {

    private final ManaCorePlugin plugin;

    public PlayerListener(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 進出 ----------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.mana().load(p, () -> {
            String id = p.getUniqueId().toString();
            plugin.db().query(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM comments WHERE owner=? AND seen=0")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }).thenAccept(n -> plugin.sync(() -> {
                if (n > 0 && p.isOnline())
                    plugin.msg().raw(p, "<green>你的作品有 <n> 則新留言，輸入 <white>/mana</white> 打開選單查看。</green>", "n", n);
            }));
            int unclaimed = plugin.commissions().unclaimed(p.getUniqueId());
            if (unclaimed > 0)
                plugin.msg().raw(p, "<green>有人在你離線時完成了 <n> 張委託，輸入 <white>/mana board</white> →「我的委託」領取物品。</green>", "n", unclaimed);
            var pair = plugin.mentors().of(p.getUniqueId());
            if (pair != null && "pending".equals(pair.status)) {
                String mentor = org.bukkit.Bukkit.getOfflinePlayer(pair.mentor).getName();
                plugin.msg().raw(p, "<green><name> 想當你的導師，輸入 <white>/mana</white> 打開選單接受。</green>", "name", String.valueOf(mentor));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.mana().unload(e.getPlayer().getUniqueId());
    }

    // ---------------- AFK：轉頭、指令、互動、放置方塊才算「有在玩」 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        var from = e.getFrom();
        var to = e.getTo();
        if (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) plugin.mana().touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        plugin.mana().touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        plugin.mana().touch(e.getPlayer());
    }

    // ---------------- Shift + F：開啟 mana 選單 ----------------

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (!plugin.getConfig().getBoolean("menu.sneak-swap-opens-menu", true)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        e.setCancelled(true);
        plugin.mana().touch(p);
        plugin.openHub(p);
    }

    // ---------------- 成就：達成原版成就得到大量 mana（一次性，本來就刷不了） ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("sources.advancement.enabled", true)) return;
        Advancement a = e.getAdvancement();
        if (a.getKey().getKey().startsWith("recipes/")) return; // 配方解鎖不算成就
        AdvancementDisplay display = a.getDisplay();
        if (display == null) return;
        String key = a.getKey().toString();
        long amount;
        String override = "sources.advancement.overrides." + key;
        if (cfg.contains(override)) amount = cfg.getLong(override);
        else amount = switch (display.frame()) {
            case CHALLENGE -> cfg.getLong("sources.advancement.challenge");
            case GOAL -> cfg.getLong("sources.advancement.goal");
            default -> cfg.getLong("sources.advancement.task");
        };
        if (amount <= 0) return;
        String kind = switch (display.frame()) { case CHALLENGE -> "挑戰"; case GOAL -> "目標"; default -> "成就"; };
        plugin.mana().earn(e.getPlayer(), Source.ADVANCEMENT, amount,
                Component.text("達成" + kind + "「").append(display.displayName()).append(Component.text("」")), key, false);
    }

    // ---------------- 祭壇 ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        plugin.mana().touch(e.getPlayer());
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !plugin.places().isAltar(b)) return;
        e.setCancelled(true);
        plugin.altar().open(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakTouch(BlockBreakEvent e) {
        plugin.mana().touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAltarBreak(BlockBreakEvent e) {
        if (plugin.places().isAltar(e.getBlock()) && !e.getPlayer().hasPermission("manacore.admin")) {
            e.setCancelled(true);
            plugin.msg().raw(e.getPlayer(), "<red>這是生命祭壇，不能破壞。</red>");
        }
    }
}
