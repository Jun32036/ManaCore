package io.github.jun32036.manacore.economy;

import org.bukkit.entity.Player;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 手藝被動加成：等級越高，做同一件事的收穫越好（Jobs Reborn 式），讓等級不只是解鎖訂單。
 * 加成 = per-level × (等級 − 1)，最高 max。
 * - 種植／採集／釣魚／烹飪：有機率多拿一份
 * - 畜牧：繁殖有機率生雙胞胎
 * - 建造：祭壇的建材類折扣
 */
public final class Passives {

    private final ManaCorePlugin plugin;

    public Passives(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("sources.craft.passives.enabled", true);
    }

    /** 這位玩家在某種手藝的加成比例（0～max） */
    public double bonus(Player p, Skill s) {
        if (!enabled()) return 0;
        PlayerData d = plugin.mana().get(p);
        if (d == null) return 0;
        return bonusAt(s, plugin.craft().level(d, s));
    }

    /** 不帶預設值呼叫 getDouble：舊設定檔缺少這個鍵時才會讀到插件內建的預設值 */
    private double cfg(String path, double fallback) {
        return plugin.getConfig().contains(path) ? plugin.getConfig().getDouble(path) : fallback;
    }

    /** 擲一次：是否觸發「多一份」 */
    public boolean roll(Player p, Skill s) {
        double b = bonus(p, s);
        return b > 0 && ThreadLocalRandom.current().nextDouble() < b;
    }

    /** n 次獨立擲骰中觸發幾次（熔爐一次拿出一疊時用），最多 cap */
    public int rolls(Player p, Skill s, int n, int cap) {
        double b = bonus(p, s);
        if (b <= 0) return 0;
        int hit = 0;
        for (int i = 0; i < n && hit < cap; i++) if (ThreadLocalRandom.current().nextDouble() < b) hit++;
        return hit;
    }

    /** 建造等級的祭壇折扣（0.18 = 打 82 折） */
    public double altarDiscount(Player p) {
        return bonus(p, Skill.BUILD);
    }

    public long discounted(Player p, long price) {
        double d = altarDiscount(p);
        if (d <= 0 || price <= 0) return price;
        return Math.max(1, Math.round(price * (1 - d)));
    }

    /** 給選單顯示：某手藝在某等級的加成 */
    public double bonusAt(Skill s, int lv) {
        String base = "sources.craft.passives." + s.key + ".";
        double per = cfg(base + "per-level", 0.04);
        double max = cfg(base + "max", 0.36);
        return Math.max(0, Math.min(max, per * (lv - 1)));
    }

    public static String describe(Skill s) {
        return switch (s) {
            case FARM -> "收成時多拿一份";
            case GATHER -> "挖到礦石時多拿一份";
            case HUSBANDRY -> "繁殖時生雙胞胎";
            case FISHING -> "釣到的東西再多一份";
            case COOKING -> "從熔爐拿出熟食時多一個";
            case BUILD -> "祭壇建材折扣";
        };
    }
}
