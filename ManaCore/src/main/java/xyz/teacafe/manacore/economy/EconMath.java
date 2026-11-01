package xyz.teacafe.manacore.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 經濟系統裡「不碰伺服器」的計算：新鮮度、多樣性、等級、訂單抽選、手續費、方塊座標編碼。
 * 全部是純函式或小型狀態物件，方便單元測試，也方便日後調整數值時預先試算。
 */
public final class EconMath {

    private EconMath() {}

    // ======================================================================
    // 新鮮度：同一種手藝做越多，每次得到的點數越少；休息或換別的事做，會慢慢恢復
    // ======================================================================

    public static final class Freshness {
        private double value = 1.0;
        private long lastMs;

        public Freshness(long nowMs) {
            this.lastMs = nowMs;
        }

        /** 目前的新鮮度（先套用到 now 為止的恢復） */
        public double peek(long nowMs, double recoverPerMinute) {
            return recovered(value, nowMs - lastMs, recoverPerMinute);
        }

        /**
         * 用掉一次：回傳實際有效的點數（原始點數 × 新鮮度），並依有效點數降低新鮮度。
         * @param decayPerPoint 每 1 有效點數降低多少新鮮度（0.004 = 0.4%）
         * @param floor         新鮮度最低降到多少
         */
        public double consume(double rawPoints, long nowMs, double recoverPerMinute, double decayPerPoint, double floor) {
            value = recovered(value, nowMs - lastMs, recoverPerMinute);
            lastMs = nowMs;
            double eff = rawPoints * value;
            value = Math.max(floor, value - eff * decayPerPoint);
            return eff;
        }

        static double recovered(double v, long elapsedMs, double perMinute) {
            if (elapsedMs <= 0) return v;
            return Math.min(1.0, v + perMinute * (elapsedMs / 60_000.0));
        }
    }

    // ======================================================================
    // 多樣性：最近做的事越多樣（不同作物、不同料理、不同方塊），點數越高
    // ======================================================================

    public static final class Variety {
        private final Deque<String> recent = new ArrayDeque<>();
        private final int window;

        public Variety(int window) {
            this.window = Math.max(1, window);
        }

        public void record(String key) {
            recent.addLast(key);
            while (recent.size() > window) recent.removeFirst();
        }

        public int distinct() {
            return new HashSet<>(recent).size();
        }

        /** base + 每種不同的東西 perDistinct，最多 1.0 */
        public double factor(double base, double perDistinct) {
            return varietyFactor(distinct(), base, perDistinct);
        }
    }

    public static double varietyFactor(int distinct, double base, double perDistinct) {
        return Math.max(0, Math.min(1.0, base + perDistinct * Math.max(0, distinct)));
    }

    // ======================================================================
    // 結伴與合作
    // ======================================================================

    /** 結伴加成：附近有其他玩家時的倍率 */
    public static double companionFactor(int companions, double bonus) {
        return companions > 0 ? 1.0 + bonus : 1.0;
    }

    /** 合作挑戰：人越多每人拿越多（鼓勵組隊），超過 maxExtra 人不再增加 */
    public static long groupReward(long base, int participants, double perExtra, int maxExtra) {
        if (participants <= 0 || base <= 0) return 0;
        int extra = Math.min(Math.max(0, participants - 1), Math.max(0, maxExtra));
        return Math.round(base * (1.0 + perExtra * extra));
    }

    // ======================================================================
    // 等級
    // ======================================================================

    /** thresholds[i] 是達到 Lv(i+1) 需要的經驗；回傳 1 起算的等級 */
    public static int level(double xp, List<? extends Number> thresholds) {
        int lv = 1;
        for (int i = 0; i < thresholds.size(); i++) {
            if (xp >= thresholds.get(i).doubleValue()) lv = i + 1;
            else break;
        }
        return lv;
    }

    /** 下一級需要的經驗；已滿級回傳 -1 */
    public static double nextLevelXp(int level, List<? extends Number> thresholds) {
        return level < thresholds.size() ? thresholds.get(level).doubleValue() : -1;
    }

    // ======================================================================
    // 城鎮訂單：依日期決定、每種手藝最多 maxPerSkill 張，所以每天的需求都不一樣
    // ======================================================================

    public record Candidate(String id, String skill) {}

    public static List<String> pickOrders(String day, Collection<Candidate> pool, int count, int maxPerSkill) {
        List<Candidate> list = new ArrayList<>(pool);
        list.sort((a, b) -> a.id().compareTo(b.id())); // 先排序，同一天不論設定檔順序都抽到一樣的結果
        Random r = new Random(day.hashCode() * 31L + 7);
        java.util.Collections.shuffle(list, r);
        Map<String, Integer> perSkill = new HashMap<>();
        List<String> out = new ArrayList<>();
        for (Candidate c : list) {
            if (out.size() >= count) break;
            int n = perSkill.getOrDefault(c.skill(), 0);
            if (maxPerSkill > 0 && n >= maxPerSkill) continue;
            perSkill.put(c.skill(), n + 1);
            out.add(c.id());
        }
        // 手藝種類不夠多時，放寬限制補滿
        if (out.size() < count) {
            for (Candidate c : list) {
                if (out.size() >= count) break;
                if (!out.contains(c.id())) out.add(c.id());
            }
        }
        return out;
    }

    // ======================================================================
    // 其他
    // ======================================================================

    /** 委託手續費（無條件進位，至少 minFee） */
    public static long fee(long reward, double rate, long minFee) {
        if (reward <= 0 || rate <= 0) return 0;
        return Math.max(minFee, (long) Math.ceil(reward * rate));
    }

    /** 方塊在區塊內的位置編碼成一個 long（給區塊的持久資料記錄「玩家放置的方塊」） */
    public static long packBlock(int x, int y, int z) {
        return ((long) (y + 4096) << 8) | ((long) (z & 15) << 4) | (x & 15);
    }

    public static int unpackX(long v) { return (int) (v & 15); }
    public static int unpackZ(long v) { return (int) ((v >> 4) & 15); }
    public static int unpackY(long v) { return (int) (v >> 8) - 4096; }

    /** 礦石 id 正規化：深板岩礦與一般礦視為同一種（圖鑑與點數用） */
    public static String normalizeOre(String id) {
        return id.startsWith("deepslate_") ? id.substring("deepslate_".length()) : id;
    }
}
