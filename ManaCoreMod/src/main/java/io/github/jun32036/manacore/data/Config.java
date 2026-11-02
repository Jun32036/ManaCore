package io.github.jun32036.manacore.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.jun32036.manacore.ManaCore;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 設定：config/manacore.json。第一次啟動時從模組內建的 manacore-defaults.json 複製一份。
 * 玩家的設定檔缺少的鍵，用內建預設值補上（升級模組後不必手動改設定檔）。
 */
public final class Config {

    public static final class DayCaps { public long craft, craftPerSkill, dex, order, explore, villager; }
    public static final class Freshness { public double decayPerPoint, floor, recoverPerMinute; }
    public static final class Variety { public int window; public double base, perDistinct; }
    public static final class Passive { public double perLevel, max; }
    public static final class Craft {
        public double pointsPerMana;
        public Freshness freshness;
        public Variety variety;
        public List<Integer> levels = new ArrayList<>();
        public List<String> titles = new ArrayList<>();
        public Map<String, Double> points = new HashMap<>();
        public Map<String, Double> ores = new HashMap<>();
        public Map<String, Double> dishes = new HashMap<>();
        public Map<String, Passive> passives = new HashMap<>();
    }
    public static final class Dex { public Map<String, Long> amounts = new HashMap<>(); public int craftPerDay, milestoneEvery; public long milestoneBonus; }
    public static final class Advancement { public long task, goal, challenge; public Map<String, Long> overrides = new HashMap<>(); }
    public static final class Explore { public long biome, structure; public Map<String, Long> structureOverrides = new HashMap<>(); }
    public static final class OrderDef { public String id, item, skill; public int amount, level; public long reward; }
    public static final class Orders { public int perDay, maxPerSkill; public List<OrderDef> pool = new ArrayList<>(); }
    public static final class Request { public String item; public int amount; public long reward; }
    public static final class Villager { public double chancePerDay; public int maxActive, radius; public Map<String, List<Request>> requests = new HashMap<>(); }
    public static final class AltarItem { public String item; public int amount; public long price; }
    public static final class AltarCategory { public String id, name, icon; public boolean discount; public List<AltarItem> items = new ArrayList<>(); }
    public static final class Alchemy { public int lossPercent; public boolean learnRequired; }
    public static final class Reward { public long mana; public Map<String, Integer> items = new HashMap<>(); }
    public static final class Project { public String id, name, description, icon; public Map<String, Integer> needs = new HashMap<>(); public long mana; public Reward reward = new Reward(); }

    public DayCaps dayCaps;
    public Craft craft;
    public Dex dex;
    public Advancement advancement;
    public Explore explore;
    public Orders orders;
    public Villager villager;
    public List<AltarCategory> altar = new ArrayList<>();
    public Alchemy alchemy;
    public Map<String, Long> values = new HashMap<>();
    public List<Project> projects = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Config load() {
        JsonObject defaults;
        try (InputStream in = Config.class.getResourceAsStream("/manacore-defaults.json")) {
            defaults = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("模組內的 manacore-defaults.json 讀取失敗", e);
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("manacore.json");
        JsonObject merged = defaults.deepCopy();
        try {
            if (Files.exists(file)) {
                JsonObject user = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String k : user.keySet()) merged.add(k, user.get(k)); // 第一層：使用者有寫的區塊整塊覆蓋
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(defaults), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            ManaCore.LOG.warn("config/manacore.json 讀取失敗，使用內建預設值：{}", e.toString());
        }
        return GSON.fromJson(merged, Config.class);
    }
}
