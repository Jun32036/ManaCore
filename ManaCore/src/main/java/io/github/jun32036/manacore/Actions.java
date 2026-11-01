package io.github.jun32036.manacore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 玩家動作的共用邏輯：指令與圖形介面都呼叫這裡，規則只寫一次。
 * 回傳 true 代表動作成立；失敗時已經對玩家說明原因。
 */
public final class Actions {

    public record Entry(String time, String who, String text, long delta, String source) {}

    private final ManaCorePlugin plugin;

    public Actions(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 感謝 ----------------

    public boolean thank(Player p, Player t, String reason) {
        if (!plugin.getConfig().getBoolean("sources.thanks.enabled", true)) return false;
        PlayerData d = plugin.mana().get(p);
        PlayerData td = t == null ? null : plugin.mana().get(t);
        if (d == null || td == null) {
            plugin.msg().send(p, "no-player", "name", t == null ? "?" : t.getName());
            return false;
        }
        if (t.equals(p)) {
            plugin.msg().raw(p, "<gray>謝謝自己也很好，但這裡不算 mana 喔。</gray>");
            return false;
        }
        if (plugin.mana().sameIp(d, td)) {
            plugin.msg().send(p, "same-ip");
            return false;
        }
        if (d.thankedToday.contains(t.getUniqueId())) {
            plugin.msg().send(p, "thanks-already", "name", t.getName());
            return false;
        }
        int max = thanksPerDay();
        if (max > 0 && d.thankedToday.size() >= max) {
            plugin.msg().send(p, "thanks-limit", "max", max);
            return false;
        }
        String r = reason == null ? "" : reason.strip();
        if (r.length() > 60) r = r.substring(0, 60);

        d.thankedToday.add(t.getUniqueId());
        String day = plugin.mana().today(), giver = p.getUniqueId().toString(), receiver = t.getUniqueId().toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO thanks_log(day, giver, receiver) VALUES(?,?,?)")) {
                ps.setString(1, day);
                ps.setString(2, giver);
                ps.setString(3, receiver);
                ps.executeUpdate();
            }
            return null;
        });
        plugin.msg().send(p, "thanks-sent", "name", t.getName());
        plugin.msg().send(t, "thanks-received", "name", p.getName(), "reason", r.isEmpty() ? "" : "：「" + r + "」");
        long amount = plugin.getConfig().getLong("sources.thanks.amount", 5);
        int perWeek = plugin.getConfig().getInt("sources.thanks.max-per-pair-week", 3);
        String reasonText = r;
        // 同一對玩家 7 天內只有前幾次感謝產生 mana，固定小圈圈天天互謝就刷不了
        pairDaysThisWeek("SELECT COUNT(DISTINCT day) FROM thanks_log WHERE giver=? AND receiver=? AND day>=?", giver, receiver)
                .thenAccept(n -> plugin.sync(() -> {
                    if (perWeek > 0 && n > perWeek) {
                        if (p.isOnline()) plugin.msg().raw(p, "<gray>（這週已經感謝過 <n> <k> 次，感謝已送達，但這次不再產生 mana。多感謝不同的人吧！）</gray>",
                                "n", t.getName(), "k", perWeek);
                        return;
                    }
                    plugin.mana().earnAnywhere(t.getUniqueId(), Source.THANKS, amount, Component.text("被 " + p.getName() + " 感謝"),
                            "來自 " + p.getName() + (reasonText.isEmpty() ? "" : "：" + reasonText));
                }));
        return true;
    }

    /** 某對玩家在包含今天的最近 7 天裡，有幾天發生過這件事 */
    private CompletableFuture<Integer> pairDaysThisWeek(String sql, String a, String b) {
        String since = java.time.LocalDate.parse(plugin.mana().today()).minusDays(6).toString();
        return plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, a);
                ps.setString(2, b);
                ps.setString(3, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public int thanksPerDay() {
        return plugin.getConfig().getInt("sources.thanks.max-given-per-day", 5);
    }

    // ---------------- 贈與 / 捐獻 ----------------

    public boolean gift(Player p, Player t, long amount, String note) {
        if (!plugin.getConfig().getBoolean("gift.enabled", true)) {
            plugin.msg().raw(p, "<gray>目前沒有開放贈與。</gray>");
            return false;
        }
        PlayerData d = plugin.mana().get(p);
        PlayerData td = t == null ? null : plugin.mana().get(t);
        if (d == null || td == null) {
            plugin.msg().send(p, "no-player", "name", t == null ? "?" : t.getName());
            return false;
        }
        if (t.equals(p)) {
            plugin.msg().raw(p, "<gray>不能送給自己。</gray>");
            return false;
        }
        if (plugin.mana().sameIp(d, td)) {
            plugin.msg().send(p, "same-ip");
            return false;
        }
        if (amount <= 0) return false;
        long max = plugin.getConfig().getLong("gift.max-per-gift", 0);
        if (max > 0 && amount > max) {
            plugin.msg().raw(p, "<gray>單次最多只能送 <max>。</gray>", "max", max);
            return false;
        }
        String n = note == null ? "" : note.strip();
        if (!plugin.mana().spend(p, amount, Source.GIFT_OUT, "給 " + t.getName() + (n.isEmpty() ? "" : "：" + n))) return false;
        plugin.mana().adjust(t.getUniqueId(), amount, Source.GIFT_IN, "來自 " + p.getName() + (n.isEmpty() ? "" : "：" + n));
        plugin.msg().send(p, "gift-sent", "name", t.getName(), "amount", amount);
        plugin.msg().send(t, "gift-received", "name", p.getName(), "amount", amount, "note", n.isEmpty() ? "" : "：「" + n + "」");
        return true;
    }

    public boolean donate(Player p, long amount) {
        if (amount <= 0) {
            plugin.msg().raw(p, "<gray>你現在沒有 mana 可以捐。</gray>");
            return false;
        }
        if (!plugin.mana().spend(p, amount, Source.DONATE, "捐給生命之樹")) return false;
        plugin.tree().add(amount);
        plugin.msg().send(p, "donated", "amount", amount);
        return true;
    }

    // ---------------- 作品 ----------------

    public int maxWorks() {
        return plugin.getConfig().getInt("sources.works.max-works-per-player", 3);
    }

    public double worksRadius() {
        return plugin.getConfig().getDouble("sources.works.radius", 32);
    }

    public void addWork(Player p, String name, Consumer<Places.Work> after) {
        List<Places.Work> mine = plugin.places().worksOf(p.getUniqueId());
        if (mine.size() >= maxWorks()) {
            plugin.msg().raw(p, "<gray>你最多只能登記 <max> 件作品，先移除一件。</gray>", "max", maxWorks());
            return;
        }
        String n = name == null || name.isBlank() ? p.getName() + " 的作品 " + (mine.size() + 1) : name.strip();
        if (n.length() > 32) n = n.substring(0, 32);
        plugin.places().addWork(p.getUniqueId(), n, p.getLocation(), w -> {
            plugin.msg().raw(p, "<green>已把這裡登記為作品「<n>」。訪客在附近 <r> 格內可以留言。</green>",
                    "n", w.name(), "r", (int) worksRadius());
            if (after != null) after.accept(w);
        });
    }

    /** 在別人的作品留言。作者可能不在線，所以從資料庫讀作者最後的 IP 判斷是否同一人。 */
    public void comment(Player p, Places.Work w, String message, Runnable after) {
        if (!plugin.getConfig().getBoolean("sources.works.enabled", true)) return;
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        UUID ownerId = w.owner();
        if (ownerId.equals(p.getUniqueId())) {
            plugin.msg().raw(p, "<gray>這是你自己的作品。</gray>");
            return;
        }
        if (!w.near(p.getLocation(), worksRadius())) {
            plugin.msg().raw(p, "<gray>要站在作品附近 <r> 格內才能留言。</gray>", "r", (int) worksRadius());
            return;
        }
        String ownerName = String.valueOf(Bukkit.getOfflinePlayer(ownerId).getName());
        if (d.commentedToday.contains(ownerId)) {
            plugin.msg().raw(p, "<gray>你今天已經給 <n> 留過言了，明天再來看看吧。</gray>", "n", ownerName);
            return;
        }
        int maxLen = plugin.getConfig().getInt("sources.works.comment-max-length", 100);
        String text = message.strip();
        if (text.length() > maxLen) text = text.substring(0, maxLen);
        String msgText = text;
        String day = plugin.mana().today();
        boolean blockIp = plugin.getConfig().getBoolean("block-same-ip", true);

        String since = java.time.LocalDate.parse(day).minusDays(6).toString();
        int perWeek = plugin.getConfig().getInt("sources.works.max-per-pair-week", 3);
        plugin.db().query(c -> {
            String ip = "";
            try (PreparedStatement ps = c.prepareStatement("SELECT last_ip FROM accounts WHERE uuid=?")) {
                ps.setString(1, ownerId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) ip = rs.getString(1);
                }
            }
            int days = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(DISTINCT day) FROM comments WHERE owner=? AND visitor=? AND day>=?")) {
                ps.setString(1, ownerId.toString());
                ps.setString(2, p.getUniqueId().toString());
                ps.setString(3, since);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) days = rs.getInt(1);
                }
            }
            return new Object[]{ip, days};
        }).thenAccept(res -> plugin.sync(() -> {
            String ownerIp = (String) res[0];
            int daysBefore = (Integer) res[1];
            if (!p.isOnline()) return;
            if (blockIp && ownerIp != null && !ownerIp.isEmpty() && ownerIp.equals(d.ip)) {
                plugin.msg().send(p, "same-ip");
                return;
            }
            if (!d.commentedToday.add(ownerId)) return;
            plugin.db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO comments(ts, day, owner, visitor, visitor_name, message) VALUES(?,?,?,?,?,?)")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, day);
                    ps.setString(3, ownerId.toString());
                    ps.setString(4, p.getUniqueId().toString());
                    ps.setString(5, p.getName());
                    ps.setString(6, "[" + w.name() + "] " + msgText);
                    ps.executeUpdate();
                }
                return null;
            });
            plugin.msg().raw(p, "<green>你在「<w>」留下了留言，<n> 會看到的。</green>", "w", w.name(), "n", ownerName);
            long amount = plugin.getConfig().getLong("sources.works.amount", 2);
            if (perWeek > 0 && daysBefore >= perWeek)
                plugin.msg().raw(p, "<gray>（這週已經給 <n> 留言 <k> 天了，留言照樣送達，但這次不產生 mana。）</gray>", "n", ownerName, "k", perWeek);
            else
                plugin.mana().earnAnywhere(ownerId, Source.WORKS, amount, Component.text(p.getName() + " 參觀了「" + w.name() + "」"), "訪客 " + p.getName());
            Player op = Bukkit.getPlayer(ownerId);
            if (op != null) plugin.msg().raw(op, "<green><v> 在「<w>」留言：</green><white><m></white>", "v", p.getName(), "w", w.name(), "m", msgText);
            plugin.mentors().onCommented(ownerId);
            if (after != null) after.run();
        }));
    }

    // ---------------- 查詢 ----------------

    public CompletableFuture<List<Entry>> comments(UUID owner, int limit, boolean markSeen) {
        String id = owner.toString();
        return plugin.db().query(c -> {
            List<Entry> out = new ArrayList<>();
            SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, visitor_name, message FROM comments WHERE owner=? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, id);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Entry(f.format(new Date(rs.getLong(1))), rs.getString(2), rs.getString(3), 0, "comment"));
                }
            }
            if (markSeen) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE comments SET seen=1 WHERE owner=? AND seen=0")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
            return out;
        });
    }

    public CompletableFuture<Integer> unseenComments(UUID owner) {
        String id = owner.toString();
        return plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM comments WHERE owner=? AND seen=0")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<Entry>> ledger(UUID uuid, int limit) {
        String id = uuid.toString();
        return plugin.db().query(c -> {
            List<Entry> out = new ArrayList<>();
            SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, delta, source, detail FROM ledger WHERE uuid=? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, id);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Entry(f.format(new Date(rs.getLong(1))), "", rs.getString(4) == null ? "" : rs.getString(4), rs.getLong(2), rs.getString(3)));
                }
            }
            return out;
        });
    }
}
