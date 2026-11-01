package xyz.teacafe.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import xyz.teacafe.manacore.ManaCorePlugin;
import xyz.teacafe.manacore.PlayerData;
import xyz.teacafe.manacore.Source;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * 圖鑑：第一次釣到某種魚、第一次收成某種作物、第一次做出某道料理、第一次繁殖某種動物……
 * 都是一次性的 mana。獎勵的是「廣度」而不是「數量」：刷同一樣東西一千次只算一次，
 * 嘗試新東西才有收穫。每個分類每收集 milestone-every 種再給一次里程碑獎勵。
 */
public final class DexService {

    public enum Category {
        FISH("fish", "魚獲與寶物", Material.TROPICAL_FISH, 2),
        CROP("crop", "作物", Material.CARROT, 2),
        ANIMAL("animal", "繁殖過的動物", Material.EGG, 3),
        TAME("tame", "馴服的夥伴", Material.BONE, 5),
        DISH("dish", "料理", Material.PUMPKIN_PIE, 2),
        GATHER("gather", "礦物與木材", Material.RAW_IRON, 2),
        CRAFT("craft", "合成過的物品", Material.CRAFTING_TABLE, 1),
        BOSS("boss", "合作討伐", Material.DRAGON_HEAD, 10);

        public final String key;
        public final String display;
        public final Material icon;
        public final long defaultAmount;

        Category(String key, String display, Material icon, long defaultAmount) {
            this.key = key;
            this.display = display;
            this.icon = icon;
            this.defaultAmount = defaultAmount;
        }
    }

    public static final String PREFIX = "dex:";

    private final ManaCorePlugin plugin;

    public DexService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("sources.dex.enabled", true);
    }

    public long amount(Category c) {
        return plugin.getConfig().getLong("sources.dex.amounts." + c.key, c.defaultAmount);
    }

    public static String key(Category c, String id) {
        return PREFIX + c.key + ":" + id;
    }

    public boolean has(PlayerData d, Category c, String id) {
        return d.discoveries.contains(key(c, id));
    }

    public int count(PlayerData d, Category c) {
        String pre = PREFIX + c.key + ":";
        int n = 0;
        for (String k : d.discoveries) if (k.startsWith(pre)) n++;
        return n;
    }

    /** 某分類已收集的 id（排序過） */
    public List<String> entries(PlayerData d, Category c) {
        String pre = PREFIX + c.key + ":";
        List<String> out = new ArrayList<>();
        for (String k : d.discoveries) if (k.startsWith(pre)) out.add(k.substring(pre.length()));
        out.sort(String::compareTo);
        return out;
    }

    /**
     * 嘗試登錄新條目。今天圖鑑 mana 已達上限時不登錄（之後再做一次仍算第一次），和探索的規則一樣。
     * @param display 給玩家看的名稱（通常是可翻譯的物品名稱）
     */
    public void discover(Player p, Category c, String id, Component display) {
        if (!enabled() || id == null || id.isEmpty()) return;
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        String key = key(c, id);
        if (d.discoveries.contains(key)) return;
        long amount = amount(c);
        if (amount <= 0) return;
        // 分類自己的每日上限（例如「合成」很便宜，限制每天最多幾點，避免狂合成各種木材製品）
        long catCap = plugin.getConfig().getLong("sources.dex.category-daily-cap." + c.key, c == Category.CRAFT ? 5 : 0);
        if (catCap > 0) {
            long left = catCap - d.dexToday.getOrDefault(c.key, 0L);
            if (left <= 0) return; // 今天這個分類滿了：不登錄，明天再做一次仍算第一次
            amount = Math.min(amount, left);
        }
        long got = plugin.mana().earn(p, Source.DEX, amount,
                Component.text("圖鑑新登錄【" + c.display + "】").append(display), key, false);
        if (got <= 0) return;
        plugin.mana().addDexDaily(d, c.key, got);
        d.discoveries.add(key);
        String uid = d.uuid.toString();
        plugin.db().run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO discoveries(uuid, key, ts) VALUES(?,?,?)")) {
                ps.setString(1, uid);
                ps.setString(2, key);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);

        int every = plugin.getConfig().getInt("sources.dex.milestone-every", 10);
        long bonus = plugin.getConfig().getLong("sources.dex.milestone-bonus", 5);
        int n = count(d, c);
        if (every > 0 && bonus > 0 && n % every == 0 && c != Category.CRAFT) {
            plugin.mana().earn(p, Source.DEX, bonus, Component.text("圖鑑【" + c.display + "】收集滿 " + n + " 種"), "milestone:" + c.key + ":" + n, false);
        }
    }
}
