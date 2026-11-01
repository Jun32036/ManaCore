package io.github.jun32036.manacore.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import io.github.jun32036.manacore.ManaCorePlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 測試服上的 config.yml 是 1.2.0 的舊版。JavaPlugin 會把插件內建的 config.yml 設成預設值，
 * 這裡模擬同樣的情況，確認 1.3.0 的新功能在舊設定檔上讀得到內建數值（而不是 0 / 空白）。
 */
class OldConfigTest {

    private static YamlConfiguration load() throws Exception {
        YamlConfiguration server = YamlConfiguration.loadConfiguration(
                new File(Objects.requireNonNull(OldConfigTest.class.getResource("/config-1.2.0.yml")).toURI()));
        YamlConfiguration builtIn = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(OldConfigTest.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8));
        server.setDefaults(builtIn);
        return server;
    }

    @Test
    void newCapsComeFromBuiltInDefaults() throws Exception {
        YamlConfiguration c = load();
        assertFalse(c.isSet("sources.craft"), "前提：舊設定檔沒有 craft 區塊");
        assertEquals(30, c.getLong("sources.craft.daily-cap"));
        assertEquals(40, c.getLong("sources.order.daily-cap"));
        assertEquals(60, c.getLong("sources.challenge.daily-cap"));
        assertEquals(30, c.getLong("sources.chat.daily-cap"), "舊設定照舊");
        assertTrue(c.contains("sources.craft.points.ores.diamond_ore"));
        assertEquals(5.0, c.getDouble("sources.craft.points.ores.diamond_ore"), 1e-9);
        assertEquals(30, c.getLong("sources.challenge.bosses.ender_dragon"));
        assertEquals(10, c.getList("sources.craft.levels").size());
    }

    @Test
    void sectionsFallBackToBuiltIn() throws Exception {
        YamlConfiguration c = load();
        var pool = ManaCorePlugin.section(c, "sources.order.pool");
        assertNotNull(pool);
        assertTrue(pool.getKeys(false).size() >= 40, "訂單池應該讀到內建的內容");
        assertEquals("BREAD", pool.getString("bread.item"));
        var projects = ManaCorePlugin.section(c, "projects");
        assertNotNull(projects);
        assertTrue(projects.getKeys(false).contains("stone_bridge"));
        // 舊設定檔本來就有的區塊，用伺服器自己的
        assertEquals(c.getConfigurationSection("altar.items"), ManaCorePlugin.section(c, "altar.items"));
    }
}
