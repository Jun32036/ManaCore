package xyz.teacafe.manacore;

/**
 * mana 的流動來源。產生類（earning）受每日上限管理；其餘是轉移或消耗。
 */
public enum Source {
    CHAT("chat", "聊天互動", true),
    THANKS("thanks", "被感謝", true),
    MENTOR("mentor", "帶新手", true),
    EXPLORE("explore", "探索", true),
    WORKS("works", "作品被參觀", true),
    HARVEST("harvest", "生產收成", true),
    EVENT("event", "活動參與", true),
    OFFERING("offering", "祭品", true),

    GIFT_IN("gift_in", "收到贈與", false),
    GIFT_OUT("gift_out", "贈與他人", false),
    DONATE("donate", "獻給生命之樹", false),
    DECAY("decay", "歸土", false),
    PURCHASE("purchase", "祭壇兌換", false),
    ADMIN("admin", "管理員調整", false);

    public final String key;
    public final String display;
    public final boolean earning;

    Source(String key, String display, boolean earning) {
        this.key = key;
        this.display = display;
        this.earning = earning;
    }

    public static Source byKey(String key) {
        for (Source s : values()) if (s.key.equals(key)) return s;
        return null;
    }
}
