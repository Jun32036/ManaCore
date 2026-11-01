package xyz.teacafe.manacore.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import xyz.teacafe.manacore.ManaCorePlugin;
import xyz.teacafe.manacore.PlayerData;
import xyz.teacafe.manacore.Source;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天互動 mana（1.2.0 起）：
 * - 一則回覆只算「回給一個人」：提到誰的名字就算回給誰，否則算回給最近一則有內容、還沒被回過的訊息。
 *   （舊版一則訊息會讓 60 秒內所有說過話的人都拿到 mana，人多時等於說話就有錢，洗頻也有錢）
 * - 只有「有內容」的發言才能被回覆而得到 mana：太短、全是同一個字、只有符號或數字的不算。
 * - 回覆本身也要有內容，否則不算接話。
 * - 同一對玩家之間，每天最多讓對方拿到 max-per-pair-day 點；想拿滿要和多個人聊。
 * - 短時間連發太多則（洗頻）的玩家，這段時間的發言都不算。
 */
public final class ChatListener implements Listener {

    private static final class Said {
        final UUID speaker;
        final String name;
        final long at;
        final String ip;
        final boolean substantive;
        boolean credited;

        Said(UUID speaker, String name, long at, String ip, boolean substantive) {
            this.speaker = speaker;
            this.name = name;
            this.at = at;
            this.ip = ip;
            this.substantive = substantive;
        }
    }

    private final ManaCorePlugin plugin;
    private final Deque<Said> recent = new ArrayDeque<>();
    /** 每位玩家最近的發言時間（偵測洗頻） */
    private final Map<UUID, Deque<Long>> rate = new HashMap<>();
    private final Map<UUID, Long> mutedUntil = new HashMap<>();
    /** 「日期|發言者|回覆者」→ 今天已經因為這對玩家拿到的 chat mana */
    private final Map<String, Integer> pairToday = new HashMap<>();
    private String pairDay = "";

    public ChatListener(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
        plugin.sync(() -> handle(uuid, text));
    }

    /** 去掉空白、標點、符號後還剩幾個字；全是同一個字或純數字視為沒有內容 */
    static int meaningfulLength(String text) {
        String core = text.replaceAll("[\\s\\p{P}\\p{S}]", "");
        if (core.isEmpty() || core.matches("\\d+")) return 0;
        if (core.codePoints().distinct().count() <= 1) return 1;
        return core.codePointCount(0, core.length());
    }

    private void handle(UUID uuid, String text) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        PlayerData me = plugin.mana().get(p);
        if (me == null) return;
        me.lastActivity = System.currentTimeMillis();

        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("sources.chat.enabled", true)) return;
        long window = cfg.getLong("sources.chat.window-seconds", 60) * 1000L;
        int minReply = cfg.getInt("sources.chat.min-length", 2);
        int minSpeaker = cfg.getInt("sources.chat.min-length-speaker", 3);
        long minInterval = cfg.getLong("sources.chat.min-interval-seconds", 15) * 1000L;
        long amount = cfg.getLong("sources.chat.amount", 1);
        int perPair = cfg.getInt("sources.chat.max-per-pair-day", 5);
        int burst = cfg.getInt("sources.chat.spam-burst", 6);
        long burstWindow = cfg.getLong("sources.chat.spam-window-seconds", 30) * 1000L;
        long muteMs = cfg.getLong("sources.chat.spam-cooldown-seconds", 60) * 1000L;

        long now = System.currentTimeMillis();
        String today = plugin.mana().today();
        if (!today.equals(pairDay)) {
            pairDay = today;
            pairToday.clear();
        }

        // 洗頻偵測：burstWindow 內超過 burst 則，接下來 muteMs 內的發言都不計
        Deque<Long> times = rate.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > burstWindow) times.pollFirst();
        if (times.size() > burst) mutedUntil.put(uuid, now + muteMs);
        boolean spamming = now < mutedUntil.getOrDefault(uuid, 0L);

        String norm = text.replaceAll("\\s+", "").toLowerCase();
        boolean repeat = norm.equals(me.lastChatText);
        me.lastChatText = norm;
        int len = meaningfulLength(text);

        while (!recent.isEmpty() && now - recent.peekFirst().at > window) recent.pollFirst();

        boolean validReply = !repeat && !spamming && len >= minReply;
        if (validReply) {
            Said target = addressed(text, uuid);
            if (target == null) target = latestFromOthers(uuid);
            if (target != null && target.substantive && !target.credited) credit(target, p, me, amount, minInterval, perPair, now);
        }

        boolean substantive = !repeat && !spamming && len >= minSpeaker;
        recent.addLast(new Said(uuid, p.getName(), now, me.ip == null ? "" : me.ip, substantive));
        while (recent.size() > 200) recent.pollFirst();
    }

    /** 訊息裡提到誰的名字，就是在回誰（取最近一則） */
    private Said addressed(String text, UUID self) {
        String low = text.toLowerCase();
        for (Iterator<Said> it = recent.descendingIterator(); it.hasNext(); ) {
            Said s = it.next();
            if (!s.speaker.equals(self) && low.contains(s.name.toLowerCase())) return s;
        }
        return null;
    }

    /** 沒有指名時：算給最近一則「有內容、還沒被回過」的別人的訊息，洗頻和空洞訊息直接跳過 */
    private Said latestFromOthers(UUID self) {
        for (Iterator<Said> it = recent.descendingIterator(); it.hasNext(); ) {
            Said s = it.next();
            if (!s.speaker.equals(self) && s.substantive && !s.credited) return s;
        }
        return null;
    }

    private void credit(Said target, Player replier, PlayerData me, long amount, long minInterval, int perPair, long now) {
        target.credited = true;
        PlayerData speaker = plugin.mana().get(target.speaker);
        Player sp = Bukkit.getPlayer(target.speaker);
        if (speaker == null || sp == null) return;
        if (plugin.getConfig().getBoolean("block-same-ip", true) && target.ip.equals(me.ip)) return;
        if (now - speaker.lastChatCredit < minInterval) return;
        String key = target.speaker + "|" + replier.getUniqueId();
        int used = pairToday.getOrDefault(key, 0);
        if (perPair > 0 && used >= perPair) return;
        long got = plugin.mana().earn(sp, Source.CHAT, amount,
                Component.text("和 " + replier.getName() + " 聊天"), "接話者 " + replier.getName(), true);
        if (got > 0) {
            speaker.lastChatCredit = now;
            pairToday.put(key, used + (int) got);
        }
    }
}
