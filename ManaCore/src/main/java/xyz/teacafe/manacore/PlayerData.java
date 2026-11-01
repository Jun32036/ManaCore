package xyz.teacafe.manacore;

import java.util.EnumMap;
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

    public void resetDaily() {
        earnedToday.clear();
        thankedToday.clear();
        commentedToday.clear();
        capNotified.clear();
    }
}
