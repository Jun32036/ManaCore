package xyz.teacafe.manacore.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconMathTest {

    private static final double DECAY = 0.005, FLOOR = 0.3, RECOVER = 0.01;

    @Test
    void freshnessDropsWithRepetitionAndRecoversWithRest() {
        EconMath.Freshness f = new EconMath.Freshness(0);
        double first = f.consume(10, 0, RECOVER, DECAY, FLOOR);
        assertEquals(10, first, 1e-9, "第一次是滿的");
        // 連續做 300 次（每次 1 點，同一時間）→ 降到下限
        for (int i = 0; i < 300; i++) f.consume(1, 0, RECOVER, DECAY, FLOOR);
        assertEquals(FLOOR, f.peek(0, RECOVER), 1e-9);
        // 休息 30 分鐘恢復 30%
        assertEquals(0.6, f.peek(30 * 60_000L, RECOVER), 1e-9);
        // 休息很久回滿
        assertEquals(1.0, f.peek(1000 * 60_000L, RECOVER), 1e-9);
    }

    @Test
    void grindingOneSkillGivesFarLessThanFreshStart() {
        // 「刷」：一分鐘內做 200 次 1 點的動作
        EconMath.Freshness f = new EconMath.Freshness(0);
        double total = 0;
        for (int i = 0; i < 200; i++) total += f.consume(1, i * 300L, RECOVER, DECAY, FLOOR);
        assertTrue(total < 130, "連續刷 200 點，有效點數應該明顯少於 200，實際 " + total);
        assertTrue(total > 60, "但也不會少到沒有意義，實際 " + total);
    }

    @Test
    void varietyRewardsDiversity() {
        EconMath.Variety mono = new EconMath.Variety(32);
        for (int i = 0; i < 32; i++) mono.record("wheat");
        assertEquals(0.625, mono.factor(0.5, 0.125), 1e-9);

        EconMath.Variety mixed = new EconMath.Variety(32);
        String[] crops = {"wheat", "carrot", "potato", "beetroot"};
        for (int i = 0; i < 32; i++) mixed.record(crops[i % 4]);
        assertEquals(1.0, mixed.factor(0.5, 0.125), 1e-9);

        // 視窗只看最近 N 次
        EconMath.Variety w = new EconMath.Variety(4);
        for (String c : new String[]{"a", "b", "c", "d", "a", "a", "a", "a"}) w.record(c);
        assertEquals(1, w.distinct());
    }

    @Test
    void groupRewardEncouragesTeamsButIsCapped() {
        assertEquals(30, EconMath.groupReward(30, 1, 0.25, 3));
        assertEquals(38, EconMath.groupReward(30, 2, 0.25, 3)); // 37.5 → 38
        assertEquals(53, EconMath.groupReward(30, 4, 0.25, 3)); // 52.5 → 53
        assertEquals(53, EconMath.groupReward(30, 10, 0.25, 3), "超過 max-extra 不再增加");
        assertEquals(0, EconMath.groupReward(30, 0, 0.25, 3));
        assertEquals(1.25, EconMath.companionFactor(1, 0.25), 1e-9);
        assertEquals(1.0, EconMath.companionFactor(0, 0.25), 1e-9);
    }

    @Test
    void levels() {
        List<Integer> th = List.of(0, 40, 120, 260);
        assertEquals(1, EconMath.level(0, th));
        assertEquals(1, EconMath.level(39.9, th));
        assertEquals(2, EconMath.level(40, th));
        assertEquals(4, EconMath.level(99999, th));
        assertEquals(120, EconMath.nextLevelXp(2, th), 1e-9);
        assertEquals(-1, EconMath.nextLevelXp(4, th), 1e-9);
    }

    @Test
    void ordersAreDeterministicPerDayDiverseAndRotate() {
        List<EconMath.Candidate> pool = new ArrayList<>();
        String[] skills = {"cooking", "fishing", "farm", "husbandry", "gather", "build"};
        for (String s : skills) for (int i = 0; i < 6; i++) pool.add(new EconMath.Candidate(s + i, s));

        List<String> a = EconMath.pickOrders("2026-11-01", pool, 6, 2);
        List<String> reordered = new ArrayList<>(pool.reversed()).stream().map(c -> c.id()).toList();
        List<EconMath.Candidate> pool2 = new ArrayList<>();
        for (String id : reordered) pool2.add(new EconMath.Candidate(id, id.replaceAll("\\d", "")));
        assertEquals(a, EconMath.pickOrders("2026-11-01", pool2, 6, 2), "同一天、設定順序不同，結果一樣");
        assertEquals(6, a.size());

        Map<String, Integer> perSkill = new HashMap<>();
        for (String id : a) perSkill.merge(id.replaceAll("\\d", ""), 1, Integer::sum);
        assertTrue(perSkill.values().stream().allMatch(n -> n <= 2), "每種手藝最多 2 張：" + perSkill);

        // 一週內每天都不一樣
        List<List<String>> week = new ArrayList<>();
        for (int d = 1; d <= 7; d++) week.add(EconMath.pickOrders("2026-11-0" + d, pool, 6, 2));
        assertNotEquals(week.get(0), week.get(1));
        assertEquals(7, week.stream().distinct().count());
    }

    @Test
    void ordersFillUpWhenPoolIsNarrow() {
        List<EconMath.Candidate> pool = List.of(
                new EconMath.Candidate("a", "farm"), new EconMath.Candidate("b", "farm"),
                new EconMath.Candidate("c", "farm"), new EconMath.Candidate("d", "farm"));
        assertEquals(4, EconMath.pickOrders("x", pool, 6, 2).size());
    }

    @Test
    void feeAndBlockPacking() {
        assertEquals(1, EconMath.fee(5, 0.1, 1));
        assertEquals(2, EconMath.fee(20, 0.1, 1));
        assertEquals(5, EconMath.fee(50, 0.1, 1));
        assertEquals(0, EconMath.fee(0, 0.1, 1));

        for (int[] c : new int[][]{{0, -64, 0}, {15, 319, 15}, {-1, 0, -17}, {1234567, 64, -987654}}) {
            long v = EconMath.packBlock(c[0], c[1], c[2]);
            assertEquals(c[0] & 15, EconMath.unpackX(v));
            assertEquals(c[1], EconMath.unpackY(v));
            assertEquals(c[2] & 15, EconMath.unpackZ(v));
        }
        assertNotEquals(EconMath.packBlock(1, 64, 2), EconMath.packBlock(2, 64, 1));
        assertEquals("iron_ore", EconMath.normalizeOre("deepslate_iron_ore"));
        assertEquals("coal_ore", EconMath.normalizeOre("coal_ore"));
    }
}
