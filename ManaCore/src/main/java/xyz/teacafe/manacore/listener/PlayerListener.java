package xyz.teacafe.manacore.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import xyz.teacafe.manacore.ManaCorePlugin;
import xyz.teacafe.manacore.PlayerData;
import xyz.teacafe.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumSet;
import java.util.Set;

/** 進出伺服器、AFK 判定、生產收成、點擊祭壇。 */
public final class PlayerListener implements Listener {

    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAltarBreak(BlockBreakEvent e) {
        if (plugin.places().isAltar(e.getBlock()) && !e.getPlayer().hasPermission("manacore.admin")) {
            e.setCancelled(true);
            plugin.msg().raw(e.getPlayer(), "<red>這是生命祭壇，不能破壞。</red>");
        }
    }

    // ---------------- 生產收成 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        plugin.mana().touch(e.getPlayer());
        if (!plugin.getConfig().getBoolean("sources.harvest.crops", true)) return;
        Block b = e.getBlock();
        if (!CROPS.contains(b.getType())) return;
        if (!(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return;
        harvestPoint(e.getPlayer(), "收成作物");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        if (!plugin.getConfig().getBoolean("sources.harvest.crops", true)) return;
        harvestPoint(e.getPlayer(), "採收");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!plugin.getConfig().getBoolean("sources.harvest.fishing", true)) return;
        plugin.mana().touch(e.getPlayer());
        harvestPoint(e.getPlayer(), "釣魚");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p)) return;
        if (!plugin.getConfig().getBoolean("sources.harvest.breeding", true)) return;
        harvestPoint(p, "照顧動物");
    }

    private void harvestPoint(Player p, String what) {
        if (!plugin.getConfig().getBoolean("sources.harvest.enabled", true)) return;
        PlayerData d = plugin.mana().get(p);
        if (d == null || plugin.mana().isAfk(d)) return;
        if (plugin.mana().remaining(d, Source.HARVEST) == 0) return; // 今天滿了就不再累積點數
        long per = Math.max(1, plugin.getConfig().getLong("sources.harvest.points-per-mana", 16));
        plugin.mana().addHarvestPoints(d, 1);
        if (d.harvestPoints >= per) {
            long mana = d.harvestPoints / per;
            plugin.mana().addHarvestPoints(d, -mana * per);
            plugin.mana().earn(p, Source.HARVEST, mana, Component.text("生產收成（" + what + "）"), what, true);
        }
    }
}
