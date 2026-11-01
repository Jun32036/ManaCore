package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 結伴與合作挑戰。
 * - companions：附近有幾位「真的在玩、不同網路」的其他玩家，生活手藝會因此加成。
 * - 合作挑戰：擊敗首領（終界龍、凋零、遠古守衛、伏守者）或打贏突襲，所有出過力與在場的玩家都有獎勵，
 *   而且人越多每個人拿得越多（最多算到 max-extra 位隊友），讓組隊真正划算。
 *   同一種挑戰每人每天只算一次，蓋農場重複刷沒有意義。
 */
public final class CoopService implements Listener {

    private final ManaCorePlugin plugin;
    /** 首領 → (玩家 → 最後一次打到的時間) */
    private final Map<UUID, Map<UUID, Long>> hits = new HashMap<>();

    public CoopService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 結伴 ----------------

    public int companions(Player p) {
        double r = plugin.getConfig().getDouble("sources.craft.companion.radius", 32);
        return nearbyActive(p, r).size();
    }

    /** 半徑內、非 AFK、不同 IP 的其他玩家 */
    public List<Player> nearbyActive(Player p, double radius) {
        List<Player> out = new ArrayList<>();
        PlayerData me = plugin.mana().get(p);
        if (me == null) return out;
        Location l = p.getLocation();
        double r2 = radius * radius;
        for (Player o : p.getWorld().getPlayers()) {
            if (o.equals(p)) continue;
            PlayerData od = plugin.mana().get(o);
            if (od == null || plugin.mana().isAfk(od) || plugin.mana().sameIp(me, od)) continue;
            if (o.getLocation().distanceSquared(l) <= r2) out.add(o);
        }
        return out;
    }

    // ---------------- 首領 ----------------

    private long bossReward(Entity e) {
        String key = e.getType().getKey().getKey().toLowerCase(Locale.ROOT);
        String path = "sources.challenge.bosses." + key;
        return plugin.getConfig().contains(path) ? plugin.getConfig().getLong(path) : 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target) || bossReward(target) <= 0) return;
        Player p = attacker(e.getDamager());
        if (p == null) return;
        hits.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).put(p.getUniqueId(), System.currentTimeMillis());
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity boss = e.getEntity();
        Map<UUID, Long> h = hits.remove(boss.getUniqueId());
        long base = bossReward(boss);
        if (base <= 0 || !enabled()) return;
        long window = plugin.getConfig().getLong("sources.challenge.window-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        Set<Player> group = new LinkedHashSet<>();
        if (h != null) {
            for (Map.Entry<UUID, Long> en : h.entrySet()) {
                Player p = Bukkit.getPlayer(en.getKey());
                if (p != null && now - en.getValue() <= window) group.add(p);
            }
        }
        if (group.isEmpty() && boss.getKiller() != null) group.add(boss.getKiller());
        if (group.isEmpty()) return;
        // 在場支援（治療、擋怪、蓋掩體的人）也算
        double r = plugin.getConfig().getDouble("sources.challenge.support-radius", 48);
        for (Player p : new ArrayList<>(group)) group.addAll(nearbyActive(p, r));
        group = distinctNetworks(group);

        String id = boss.getType().getKey().getKey();
        Component name = Component.translatable(boss.getType().translationKey());
        reward(new ArrayList<>(group), "boss:" + id, base, Component.text("合作討伐 ").append(name));
        for (Player p : group) {
            Material head = Material.matchMaterial(id + "_spawn_egg");
            plugin.dex().discover(p, DexService.Category.BOSS, id, head == null ? name : Component.translatable(head.translationKey()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaid(RaidFinishEvent e) {
        long base = plugin.getConfig().getLong("sources.challenge.raid", 15);
        if (base <= 0 || !enabled()) return;
        List<Player> winners = new ArrayList<>(distinctNetworks(new LinkedHashSet<>(e.getWinners())));
        if (winners.isEmpty()) return;
        reward(winners, "raid", base, Component.text("一起守住了村莊（突襲勝利）"));
    }

    /** 同一個網路的多個帳號只算一位（不能靠分身拿雙份或灌隊伍人數） */
    private Set<Player> distinctNetworks(Set<Player> in) {
        Set<Player> out = new LinkedHashSet<>();
        for (Player p : in) {
            PlayerData d = plugin.mana().get(p);
            if (d == null) continue;
            boolean dup = false;
            for (Player q : out) {
                PlayerData qd = plugin.mana().get(q);
                if (qd != null && plugin.mana().sameIp(d, qd)) { dup = true; break; }
            }
            if (!dup) out.add(p);
        }
        return out;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("sources.challenge.enabled", true);
    }

    private void reward(List<Player> group, String challengeKey, long base, Component reason) {
        double perExtra = plugin.getConfig().getDouble("sources.challenge.per-extra", 0.25);
        int maxExtra = plugin.getConfig().getInt("sources.challenge.max-extra", 3);
        boolean oncePerDay = plugin.getConfig().getBoolean("sources.challenge.once-per-day", true);
        long each = EconMath.groupReward(base, group.size(), perExtra, maxExtra);
        Component full = reason.append(Component.text(group.size() > 1 ? "（" + group.size() + " 人合作）" : ""));
        for (Player p : group) {
            PlayerData d = plugin.mana().get(p);
            if (d == null) continue;
            if (oncePerDay && !plugin.mana().markChallenge(d, challengeKey)) {
                plugin.msg().raw(p, "<gray>今天已經拿過這項合作挑戰的 mana 了，明天再一起來吧！</gray>");
                continue;
            }
            plugin.mana().earn(p, Source.CHALLENGE, each, full, challengeKey + " x" + group.size(), false);
        }
        if (group.size() == 1) {
            plugin.msg().raw(group.get(0), "<gray>小提示：找夥伴一起挑戰，每個人拿到的 mana 都會更多（最多 <n> 人加成）。</gray>", "n", maxExtra + 1);
        }
    }
}
