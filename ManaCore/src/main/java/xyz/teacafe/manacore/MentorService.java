package xyz.teacafe.manacore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 導師制度：導師邀請新手 → 新手接受 → 新手完成「第一次兌換、作品第一次被留言、遊玩滿 N 分鐘」→ 雙方各得 mana。
 */
public final class MentorService {

    public static final class Pair {
        public final UUID newbie;
        public final UUID mentor;
        public String status; // pending / active / done
        public boolean exchanged;
        public boolean commented;

        Pair(UUID newbie, UUID mentor, String status, boolean exchanged, boolean commented) {
            this.newbie = newbie;
            this.mentor = mentor;
            this.status = status;
            this.exchanged = exchanged;
            this.commented = commented;
        }
    }

    private final ManaCorePlugin plugin;
    private final Database db;
    private final Map<UUID, Pair> byNewbie = new HashMap<>();

    public MentorService(ManaCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void load() throws Exception {
        db.query(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT newbie, mentor, status, exchanged, commented FROM mentors")) {
                while (rs.next()) {
                    Pair p = new Pair(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getInt(4) == 1, rs.getInt(5) == 1);
                    byNewbie.put(p.newbie, p);
                }
            }
            return null;
        }).get();
    }

    public Pair of(UUID newbie) {
        return byNewbie.get(newbie);
    }

    /** 這位導師帶過或正在帶的新手 */
    public java.util.List<Pair> menteesOf(UUID mentor) {
        java.util.List<Pair> out = new java.util.ArrayList<>();
        for (Pair p : byNewbie.values()) if (p.mentor.equals(mentor)) out.add(p);
        return out;
    }

    /** 還沒有導師（或只有未接受邀請）的新手 */
    public boolean canBeInvited(Player newbie) {
        Pair p = byNewbie.get(newbie.getUniqueId());
        return isNewbie(newbie) && (p == null || "pending".equals(p.status));
    }

    public int playMinutes(Player p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200;
    }

    public boolean isNewbie(Player p) {
        var cfg = plugin.getConfig();
        long days = cfg.getLong("sources.mentor.newbie-max-days", 7);
        int minutes = cfg.getInt("sources.mentor.newbie-max-minutes", 180);
        long first = p.getFirstPlayed();
        boolean recent = first == 0 || System.currentTimeMillis() - first <= days * 86_400_000L;
        return recent && playMinutes(p) < minutes;
    }

    /** @return 錯誤訊息；成功回傳 null */
    public String request(Player mentor, Player newbie) {
        if (!plugin.getConfig().getBoolean("sources.mentor.enabled", true)) return "導師制度目前沒有開放。";
        if (mentor.equals(newbie)) return "不能當自己的導師。";
        PlayerData a = plugin.mana().get(mentor), b = plugin.mana().get(newbie);
        if (a == null || b == null) return "資料還在載入，請稍後再試。";
        if (plugin.mana().sameIp(a, b)) return "同一個網路的帳號之間無法這樣做。";
        if (!isNewbie(newbie)) return newbie.getName() + " 已經不是新手了。";
        Pair existing = byNewbie.get(newbie.getUniqueId());
        // 還沒被接受的邀請可以被新的邀請取代；已接受或已完成的不行
        if (existing != null && !"pending".equals(existing.status)) return newbie.getName() + " 已經有導師了。";
        Pair p = new Pair(newbie.getUniqueId(), mentor.getUniqueId(), "pending", false, false);
        byNewbie.put(p.newbie, p);
        save(p, true);
        return null;
    }

    /** @return 錯誤訊息；成功回傳 null */
    public String accept(Player newbie) {
        Pair p = byNewbie.get(newbie.getUniqueId());
        if (p == null || !"pending".equals(p.status)) return "目前沒有待接受的導師邀請。";
        p.status = "active";
        save(p, false);
        return null;
    }

    public void onExchange(UUID newbie) {
        Pair p = byNewbie.get(newbie);
        if (p == null || !"active".equals(p.status) || p.exchanged) return;
        p.exchanged = true;
        save(p, false);
        check(p);
    }

    public void onCommented(UUID newbie) {
        Pair p = byNewbie.get(newbie);
        if (p == null || !"active".equals(p.status) || p.commented) return;
        p.commented = true;
        save(p, false);
        check(p);
    }

    /** 每分鐘由排程呼叫，處理「遊玩時間」這個條件。 */
    public void tick() {
        for (Pair p : byNewbie.values()) if ("active".equals(p.status)) check(p);
    }

    private void check(Pair p) {
        Player newbie = Bukkit.getPlayer(p.newbie);
        if (newbie == null) return;
        int need = plugin.getConfig().getInt("sources.mentor.required-playtime-minutes", 60);
        if (!p.exchanged || !p.commented || playMinutes(newbie) < need) return;
        p.status = "done";
        save(p, false);
        long amount = plugin.getConfig().getLong("sources.mentor.amount", 10);
        String mentorName = Bukkit.getOfflinePlayer(p.mentor).getName();
        plugin.mana().earnAnywhere(p.newbie, Source.MENTOR, amount, Component.text("完成新手引導"), "導師 " + mentorName);
        plugin.mana().earnAnywhere(p.mentor, Source.MENTOR, amount, Component.text("帶 " + newbie.getName() + " 完成新手引導"), "新手 " + newbie.getName());
        Player mentor = Bukkit.getPlayer(p.mentor);
        if (mentor != null) plugin.msg().raw(mentor, "<green><name> 完成了新手引導，謝謝你的陪伴！</green>", "name", newbie.getName());
    }

    /** 給 /mana mentor 顯示進度用 */
    public String progress(Pair p, Player newbie) {
        int need = plugin.getConfig().getInt("sources.mentor.required-playtime-minutes", 60);
        int played = newbie == null ? 0 : playMinutes(newbie);
        return (p.exchanged ? "✔" : "✘") + " 第一次在祭壇兌換　"
                + (p.commented ? "✔" : "✘") + " 作品第一次被留言　"
                + (played >= need ? "✔" : "✘") + " 遊玩 " + Math.min(played, need) + "/" + need + " 分鐘";
    }

    private void save(Pair p, boolean insert) {
        String nb = p.newbie.toString(), mt = p.mentor.toString(), st = p.status;
        int ex = p.exchanged ? 1 : 0, cm = p.commented ? 1 : 0;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(insert
                    ? "INSERT OR REPLACE INTO mentors(newbie, mentor, status, exchanged, commented, created) VALUES(?,?,?,?,?,?)"
                    : "UPDATE mentors SET mentor=?, status=?, exchanged=?, commented=? WHERE newbie=?")) {
                if (insert) {
                    ps.setString(1, nb);
                    ps.setString(2, mt);
                    ps.setString(3, st);
                    ps.setInt(4, ex);
                    ps.setInt(5, cm);
                    ps.setLong(6, System.currentTimeMillis());
                } else {
                    ps.setString(1, mt);
                    ps.setString(2, st);
                    ps.setInt(3, ex);
                    ps.setInt(4, cm);
                    ps.setString(5, nb);
                }
                ps.executeUpdate();
            }
            return null;
        });
    }
}
