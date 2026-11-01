package io.github.jun32036.manacore.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.GeneratedStructure;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.Places;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.util.Map;

/** 定期檢查玩家位置：第一次踏入新的生態域、結構、地標時得 mana。達到當日上限時不記錄，之後再來仍算首次。 */
public final class ExploreTask implements Runnable {

    private static final Map<String, String> STRUCTURE_NAMES = Map.ofEntries(
            Map.entry("village", "村莊"), Map.entry("pillager_outpost", "掠奪者前哨站"),
            Map.entry("mansion", "林地府邸"), Map.entry("woodland_mansion", "林地府邸"),
            Map.entry("jungle_pyramid", "叢林神殿"), Map.entry("desert_pyramid", "沙漠神殿"),
            Map.entry("igloo", "雪屋"), Map.entry("shipwreck", "沉船"), Map.entry("swamp_hut", "女巫小屋"),
            Map.entry("stronghold", "要塞"), Map.entry("monument", "海底神殿"), Map.entry("ocean_ruin", "海底廢墟"),
            Map.entry("fortress", "地獄要塞"), Map.entry("end_city", "終界城"), Map.entry("buried_treasure", "埋藏的寶藏"),
            Map.entry("mineshaft", "廢棄礦坑"), Map.entry("ruined_portal", "廢棄傳送門"),
            Map.entry("bastion_remnant", "堡壘遺跡"), Map.entry("nether_fossil", "地獄化石"),
            Map.entry("ancient_city", "遠古城市"), Map.entry("trail_ruins", "古蹟廢墟"),
            Map.entry("trial_chambers", "試煉密室"), Map.entry("desert_well", "沙漠水井"));

    private final ManaCorePlugin plugin;

    public ExploreTask(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("sources.explore.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.mana().get(p);
            if (d == null || plugin.mana().isAfk(d)) continue;
            if (plugin.mana().remaining(d, Source.EXPLORE) == 0) continue;
            Location loc = p.getLocation();
            if (loc.getWorld() == null) continue;

            // 生態域
            Biome biome = loc.getBlock().getBiome();
            NamespacedKey bk = biome.getKey();
            tryDiscover(p, d, "biome:" + bk, cfg.getLong("sources.explore.biome", 3),
                    Component.text("發現生態域 ").append(Component.translatable(biome.translationKey())));

            // 結構（玩家站在結構範圍內）
            for (GeneratedStructure gs : loc.getChunk().getStructures()) {
                if (!gs.getBoundingBox().contains(loc.toVector())) continue;
                NamespacedKey sk = gs.getStructure().getKey();
                long value = overrideValue(sk);
                tryDiscover(p, d, "structure:" + sk, value, Component.text("發現結構 " + structureName(sk)));
            }

            // 地標
            for (Places.Landmark l : plugin.places().landmarks()) {
                if (!l.contains(loc)) continue;
                tryDiscover(p, d, "landmark:" + l.name(), cfg.getLong("sources.explore.landmark", 10), Component.text("抵達地標 " + l.name()));
            }
        }
    }

    /** structure-overrides 裡有列出的結構用自訂值，否則用 structure 的預設值 */
    private long overrideValue(NamespacedKey key) {
        var sec = plugin.getConfig().getConfigurationSection("sources.explore.structure-overrides");
        if (sec != null && sec.contains(key.toString())) return sec.getLong(key.toString());
        return plugin.getConfig().getLong("sources.explore.structure", 5);
    }

    private static String structureName(NamespacedKey key) {
        String path = key.getKey();
        for (Map.Entry<String, String> e : STRUCTURE_NAMES.entrySet()) {
            if (path.equals(e.getKey()) || path.startsWith(e.getKey() + "_") || path.endsWith("_" + e.getKey())) {
                return e.getValue();
            }
        }
        return path;
    }

    private void tryDiscover(Player p, PlayerData d, String key, long value, Component reason) {
        if (value <= 0 || d.discoveries.contains(key)) return;
        long got = plugin.mana().earn(p, Source.EXPLORE, value, reason, key, false);
        if (got <= 0) return;
        d.discoveries.add(key);
        String id = d.uuid.toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO discoveries(uuid, key, ts) VALUES(?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, key);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }
}
