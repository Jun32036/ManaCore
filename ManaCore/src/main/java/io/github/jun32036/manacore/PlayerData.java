package io.github.jun32036.manacore;

import io.github.jun32036.manacore.economy.Skill;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 在線玩家的記憶體快取，只在主執行緒讀寫。 */
public final class PlayerData {
    public final UUID uuid;
    public String name;
    public String ip;

    public long balance;
    public long harvestPoints;
    /** 今天各來源已得到的 mana */
    public final Map<Source, Long> earnedToday = new EnumMap<>(Source.class);
    /** 今天已經感謝過的對象 */
    public final Set<UUID> thankedToday = new HashSet<>();
    /** 今天已經留言過的作品主人 */
    public final Set<UUID> commentedToday = new HashSet<>();
    /** 已經發現過的生態域 / 結構 / 地標 key */
    public final Set<String> discoveries = new HashSet<>();
    /** 今天已經提醒過「達上限」的來源 */
    public final Set<Source> capNotified = new HashSet<>();

    /** 今天各手藝已得到的 mana（生活手藝有「每種手藝」的上限，逼大家換著玩） */
    public final Map<Skill, Long> skillToday = new EnumMap<>(Skill.class);
    /** 今天圖鑑各分類已得到的 mana（分類可以有自己的每日上限） */
    public final Map<String, Long> dexToday = new HashMap<>();
    /** 手藝累積經驗（決定等級，等級解鎖較難的城鎮訂單） */
    public final Map<Skill, Double> craftXp = new EnumMap<>(Skill.class);
    /** 手藝點數累積器（滿 points-per-mana 換 1 mana） */
    public final Map<Skill, Double> craftPoints = new EnumMap<>(Skill.class);
    /** 今天已經提醒過「這種手藝達上限」 */
    public final Set<Skill> capNotifiedSkills = java.util.EnumSet.noneOf(Skill.class);
    /** 今天已經拿過獎勵的合作挑戰（首領種類 / 突襲） */
    public final Set<String> challengesToday = new HashSet<>();
    /** 煉金值（等價交換用，和 mana 分開） */
    public long alchemy;
    /** 煉金爐學會的物品（投入過的） */
    public final Set<org.bukkit.Material> alchemyKnown = java.util.EnumSet.noneOf(org.bukkit.Material.class);
    /** 今天各張城鎮訂單交過幾份：訂單序號 → 份數 */
    public final Map<Integer, Integer> ordersToday = new HashMap<>();

    public long lastActivity = System.currentTimeMillis();
    public long lastChatCredit;
    public String lastChatText = "";

    public PlayerData(UUID uuid, String name, String ip) {
        this.uuid = uuid;
        this.name = name;
        this.ip = ip;
    }

    public long earned(Source s) {
        return earnedToday.getOrDefault(s, 0L);
    }

    public long skillEarned(Skill s) {
        return skillToday.getOrDefault(s, 0L);
    }

    public double xp(Skill s) {
        return craftXp.getOrDefault(s, 0.0);
    }

    public void resetDaily() {
        earnedToday.clear();
        skillToday.clear();
        dexToday.clear();
        challengesToday.clear();
        capNotifiedSkills.clear();
        ordersToday.clear();
        thankedToday.clear();
        commentedToday.clear();
        capNotified.clear();
    }
}
