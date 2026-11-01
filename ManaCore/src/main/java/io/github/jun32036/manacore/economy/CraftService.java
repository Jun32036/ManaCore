package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生活手藝：採集、種植、畜牧、釣魚、烹飪、建造。
 *
 * 每次動作得到的點數 = 基礎點數 × 多樣性 × 結伴加成 × 新鮮度。
 * - 多樣性：最近做的東西越多樣（種好幾種作物、做好幾種料理）越高，單一作物單調刷的只有一半。
 * - 結伴：附近有其他玩家一起時加成，鼓勵一起玩但不強迫。
 * - 新鮮度：同一種手藝連續做太多會遞減，休息或換別的手藝就恢復。
 * 點數滿 points-per-mana 換 1 mana，受「單一手藝每日上限」與「手藝總上限」管理，
 * 所以想拿滿就要換著玩；超過上限後仍然累積經驗（等級解鎖更高級的城鎮訂單）。
 */
public final class CraftService {

    private static final List<Integer> DEFAULT_LEVELS = List.of(0, 40, 120, 260, 480, 800, 1250, 1850, 2600, 3500);
    private static final List<String> DEFAULT_TITLES = List.of("見習", "入門", "熟練", "老手", "匠人", "名匠", "大師", "宗師", "傳說", "生生不息");

    private final ManaCorePlugin plugin;
    /** 新鮮度與多樣性放在這裡（不放 PlayerData），重新登入不會重置 */
    private final Map<UUID, Map<Skill, EconMath.Freshness>> freshness = new HashMap<>();
    private final Map<UUID, Map<Skill, EconMath.Variety>> variety = new HashMap<>();

    public CraftService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 設定 ----------------

    private ConfigurationSection cfg() {
        return plugin.section("sources.craft");
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("sources.craft.enabled", true);
    }

    public long perSkillCap() {
        return plugin.getConfig().getLong("sources.craft.per-skill-cap", 10);
    }

    public double pointsPerMana() {
        return Math.max(0.1, plugin.getConfig().getDouble("sources.craft.points-per-mana", 10));
    }

    /** 設定檔 sources.craft.points.<key> 的基礎點數 */
    public double points(String key, double def) {
        // contains() 也會看插件內建的預設值，舊設定檔缺少這個鍵時仍讀得到內建數值
        String path = "sources.craft.points." + key;
        return plugin.getConfig().contains(path) ? plugin.getConfig().getDouble(path) : def;
    }

    private double d(String path, double def) {
        return plugin.getConfig().getDouble("sources.craft." + path, def);
    }

    @SuppressWarnings("unchecked")
    public List<Number> levels() {
        List<?> l = plugin.getConfig().getList("sources.craft.levels");
        if (l == null || l.isEmpty() || !(l.get(0) instanceof Number)) return (List<Number>) (List<?>) DEFAULT_LEVELS;
        return (List<Number>) l;
    }

    public String title(int level) {
        List<String> t = plugin.getConfig().getStringList("sources.craft.titles");
        if (t.isEmpty()) t = DEFAULT_TITLES;
        return t.get(Math.max(0, Math.min(t.size() - 1, level - 1)));
    }

    // ---------------- 查詢（選單用） ----------------

    public int level(PlayerData d, Skill s) {
        return EconMath.level(d.xp(s), levels());
    }

    public double freshness(UUID id, Skill s) {
        return fresh(id, s).peek(System.currentTimeMillis(), d("freshness.recover-per-minute", 0.01));
    }

    public int distinct(UUID id, Skill s) {
        return variety(id, s).distinct();
    }

    public double varietyFactor(UUID id, Skill s) {
        return variety(id, s).factor(d("variety.base", 0.5), d("variety.per-distinct", 0.125));
    }

    private EconMath.Freshness fresh(UUID id, Skill s) {
        return freshness.computeIfAbsent(id, k -> new EnumMap<>(Skill.class))
                .computeIfAbsent(s, k -> new EconMath.Freshness(System.currentTimeMillis()));
    }

    private EconMath.Variety variety(UUID id, Skill s) {
        int window = plugin.getConfig().getInt("sources.craft.variety.window", 32);
        return variety.computeIfAbsent(id, k -> new EnumMap<>(Skill.class))
                .computeIfAbsent(s, k -> new EconMath.Variety(window));
    }

    // ---------------- 主流程 ----------------

    /**
     * 玩家做了一次手藝動作。
     * @param raw     基礎點數
     * @param itemKey 這次做的「東西」（作物種類、料理、方塊…），用來算多樣性
     * @param what    紀錄用的說明
     * @return 實際有效點數（建造用它來撤銷「放了又拆」）
     */
    public double award(Player p, Skill s, double raw, String itemKey, String what) {
        double eff = measure(p, s, raw, itemKey);
        if (eff > 0) settle(p, s, eff, what);
        return eff;
    }

    /**
     * 只計算有效點數（消耗新鮮度、記錄多樣性、套用結伴），先不給經驗與 mana。
     * 建造用：方塊要在原處放滿一段時間才 {@link #settle} 入帳。
     */
    public double measure(Player p, Skill s, double raw, String itemKey) {
        if (!enabled() || raw <= 0) return 0;
        PlayerData d = plugin.mana().get(p);
        if (d == null || plugin.mana().isAfk(d)) return 0;
        long now = System.currentTimeMillis();

        EconMath.Variety v = variety(p.getUniqueId(), s);
        v.record(itemKey);
        double vf = v.factor(d("variety.base", 0.5), d("variety.per-distinct", 0.125));
        int companions = plugin.coop().companions(p);
        double cf = EconMath.companionFactor(companions, d("companion.bonus", 0.25));
        return fresh(p.getUniqueId(), s).consume(raw * vf * cf, now,
                d("freshness.recover-per-minute", 0.01), d("freshness.decay-per-point", 0.005), d("freshness.floor", 0.3));
    }

    /** 把已計算好的有效點數入帳：加經驗、累積點數、換 mana */
    public void settle(Player p, Skill s, double eff, String what) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || eff <= 0) return;
        addXp(p, d, s, eff);
        credit(p, d, s, eff, what);
    }

    /** 直接加經驗（城鎮訂單、社區工程用），不產生 mana */
    public void addXp(Player p, Skill s, double xp) {
        PlayerData d = plugin.mana().get(p);
        if (d != null && xp > 0) {
            addXp(p, d, s, xp);
            plugin.mana().saveCraft(d, s);
        }
    }

    private void addXp(Player p, PlayerData d, Skill s, double xp) {
        int before = level(d, s);
        d.craftXp.merge(s, xp, Double::sum);
        int after = level(d, s);
        if (after > before) {
            plugin.msg().raw(p, "<gold>🎉 <s> 升到 <white>Lv<lv></white>「<t>」！</gold><gray>可以接更高級的城鎮訂單了。</gray>",
                    "s", s.display, "lv", after, "t", title(after));
            p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            plugin.mana().saveCraft(d, s);
        }
    }

    private void credit(Player p, PlayerData d, Skill s, double eff, String what) {
        long cap = perSkillCap();
        long skillRem = cap <= 0 ? Long.MAX_VALUE : Math.max(0, cap - d.skillEarned(s));
        long totalRem = plugin.mana().remaining(d, Source.CRAFT);
        if (skillRem == 0) {
            if (d.capNotifiedSkills.add(s))
                plugin.msg().raw(p, "<gray>今天「<s>」的 mana 已達上限（經驗照樣累積）。換個手藝、接城鎮訂單或委託吧！</gray>", "s", s.display);
            return;
        }
        if (totalRem == 0) {
            if (d.capNotified.add(Source.CRAFT))
                plugin.msg().raw(p, "<gray>今天生活手藝的 mana 已達上限（經驗照樣累積）。城鎮訂單、委託板、社區工程還能拿 mana 喔。</gray>");
            return;
        }
        double ppm = pointsPerMana();
        double acc = d.craftPoints.getOrDefault(s, 0.0) + eff;
        long n = (long) Math.floor(acc / ppm);
        if (n <= 0) {
            d.craftPoints.put(s, acc);
            return;
        }
        n = Math.min(n, skillRem);
        if (totalRem > 0) n = Math.min(n, totalRem);
        long got = plugin.mana().earn(p, Source.CRAFT, n, Component.text("生活手藝・" + s.display), s.key + "：" + what, true);
        acc -= n * ppm;
        d.craftPoints.put(s, acc);
        plugin.mana().addSkillDaily(d, s, got);
        plugin.mana().saveCraft(d, s);
    }
}
