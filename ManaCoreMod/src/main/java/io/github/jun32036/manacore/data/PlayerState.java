package io.github.jun32036.manacore.data;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 一位玩家的進度（存進世界資料夾的 manacore.json）。 */
public final class PlayerState {
    public long balance;
    /** 目前記錄的遊戲日（跨日時清空「今天」的計數） */
    public long day = -1;
    /** 今天各來源已得到的 mana */
    public Map<String, Long> today = new HashMap<>();
    /** 今天各手藝已得到的 mana */
    public Map<String, Long> skillToday = new HashMap<>();
    /** 今天圖鑑「合成」分類已得到的 mana */
    public long dexCraftToday;
    /** 今天已交的城鎮訂單（訂單 id） */
    public Set<String> ordersToday = new HashSet<>();

    public Map<String, Double> xp = new HashMap<>();
    public Map<String, Double> points = new HashMap<>();
    /** 圖鑑與探索：已發現的 key（dex:fish:cod、biome:…、structure:…） */
    public Set<String> found = new HashSet<>();

    public long alchemy;
    public Set<String> known = new HashSet<>();

    /** 社區工程（個人的建設目標）：工程 → 物品 → 已交數量 */
    public Map<String, Map<String, Integer>> projectHave = new HashMap<>();
    public Map<String, Long> projectMana = new HashMap<>();
    public Set<String> projectsDone = new HashSet<>();

    /** 最近的收支紀錄 */
    public Deque<String> log = new ArrayDeque<>();

    public long today(String source) {
        return today.getOrDefault(source, 0L);
    }

    public void rollDay(long now) {
        if (day == now) return;
        day = now;
        today.clear();
        skillToday.clear();
        dexCraftToday = 0;
        ordersToday.clear();
    }

    public void addLog(String line) {
        log.addFirst(line);
        while (log.size() > 40) log.removeLast();
    }
}
