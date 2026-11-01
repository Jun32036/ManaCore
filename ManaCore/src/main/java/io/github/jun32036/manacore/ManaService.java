package io.github.jun32036.manacore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.github.jun32036.manacore.economy.Skill;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * mana 帳本核心：在線玩家快取、產生（受每日上限）、花費、轉移、歸土、跨日重置。
 * 所有公開方法都必須在主執行緒呼叫。
 */
public final class ManaService {

    private final ManaCorePlugin plugin;
    private final Database db;
    private final Map<UUID, PlayerData> online = new ConcurrentHashMap<>();
    private String currentDay;

    public ManaService(ManaCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
        this.currentDay = computeToday();
    }

    // ---------------- 日期 ----------------

    private String computeToday() {
        ZoneId zone;
        try {
            zone = ZoneId.of(plugin.getConfig().getString("timezone", "Asia/Taipei"));
        } catch (Exception e) {
            zone = ZoneId.of("Asia/Taipei");
        }
        return LocalDate.now(zone).toString();
    }

    public String today() {
        return currentDay;
    }

    /** 每 20 秒由排程呼叫：跨日時重置每日計數並執行歸土。 */
    public void tickDay() {
        String now = computeToday();
        if (now.equals(currentDay)) return;
        currentDay = now;
        for (PlayerData d : online.values()) d.resetDaily();
        runDecayIfNeeded();
        plugin.onNewDay();
    }

    // ---------------- 載入 / 卸載 ----------------

    public PlayerData get(UUID uuid) {
        return online.get(uuid);
    }

    public PlayerData get(Player p) {
        return online.get(p.getUniqueId());
    }

    public Collection<PlayerData> all() {
        return online.values();
    }

    public void load(Player p, Runnable afterLoad) {
        UUID uuid = p.getUniqueId();
        String id = uuid.toString();
        String name = p.getName();
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
        String day = currentDay;

        db.query(c -> {
            PlayerData d = new PlayerData(uuid, name, ip);
            if (!Database.accountExists(c, id)) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO accounts(uuid, name, balance, harvest_points, last_ip, created) VALUES(?,?,0,0,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, name);
                    ps.setString(3, ip);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET name=?, last_ip=? WHERE uuid=?")) {
                    ps.setString(1, name);
                    ps.setString(2, ip);
                    ps.setString(3, id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT balance, harvest_points FROM accounts WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.balance = rs.getLong(1);
                        d.harvestPoints = rs.getLong(2);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT source, amount FROM daily WHERE day=? AND uuid=?")) {
                ps.setString(1, day);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        if (key.startsWith(CHALLENGE_DAILY_PREFIX)) {
                            d.challengesToday.add(key.substring(CHALLENGE_DAILY_PREFIX.length()));
                            continue;
                        }
                        if (key.startsWith(DEX_DAILY_PREFIX)) {
                            d.dexToday.put(key.substring(DEX_DAILY_PREFIX.length()), rs.getLong(2));
                            continue;
                        }
                        if (key.startsWith(SKILL_DAILY_PREFIX)) {
                            Skill sk = Skill.byKey(key.substring(SKILL_DAILY_PREFIX.length()));
                            if (sk != null) d.skillToday.put(sk, rs.getLong(2));
                            continue;
                        }
                        Source s = Source.byKey(key);
                        if (s != null) d.earnedToday.put(s, rs.getLong(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT skill, xp, points FROM crafts WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Skill sk = Skill.byKey(rs.getString(1));
                        if (sk == null) continue;
                        d.craftXp.put(sk, rs.getDouble(2));
                        d.craftPoints.put(sk, rs.getDouble(3));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT av FROM alchemy WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) d.alchemy = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT material FROM alchemy_known WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        org.bukkit.Material m = org.bukkit.Material.matchMaterial(rs.getString(1));
                        if (m != null) d.alchemyKnown.add(m);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT idx, bundles FROM order_contrib WHERE day=? AND uuid=?")) {
                ps.setString(1, day);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.ordersToday.put(rs.getInt(1), rs.getInt(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT receiver FROM thanks_log WHERE day=? AND giver=?")) {
                ps.setString(1, day);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.thankedToday.add(UUID.fromString(rs.getString(1)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT owner FROM comments WHERE day=? AND visitor=?")) {
                ps.setString(1, day);
                ps.setString(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.commentedToday.add(UUID.fromString(rs.getString(1)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT key FROM discoveries WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.discoveries.add(rs.getString(1));
                }
            }
            return d;
        }).thenAccept(d -> plugin.sync(() -> {
            Player now = Bukkit.getPlayer(uuid);
            if (now == null || !now.isOnline()) return;
            if (!day.equals(currentDay)) d.resetDaily();
            online.put(uuid, d);
            if (afterLoad != null) afterLoad.run();
        }));
    }

    public void unload(UUID uuid) {
        PlayerData d = online.remove(uuid);
        if (d != null) saveAllCrafts(d);
    }

    // ---------------- AFK ----------------

    public void touch(Player p) {
        PlayerData d = get(p);
        if (d != null) d.lastActivity = System.currentTimeMillis();
    }

    public boolean isAfk(PlayerData d) {
        long afkMs = plugin.getConfig().getLong("afk-seconds", 300) * 1000L;
        return System.currentTimeMillis() - d.lastActivity > afkMs;
    }

    public boolean sameIp(PlayerData a, PlayerData b) {
        if (!plugin.getConfig().getBoolean("block-same-ip", true)) return false;
        return a.ip != null && !a.ip.isEmpty() && a.ip.equals(b.ip);
    }

    // ---------------- 產生 mana ----------------

    /**
     * 0 代表不設上限。
     * 不帶預設值呼叫 getLong：伺服器上的舊設定檔沒有這個鍵時，會改讀插件內建 config.yml 的值
     * （帶了預設值 0 反而會讓 1.3.0 新來源在舊設定檔上變成「不限」）。
     */
    public long cap(Source s) {
        return plugin.getConfig().getLong("sources." + s.key + ".daily-cap");
    }

    /** 需要玩家「正在玩」才算數的來源 */
    private static boolean needsActive(Source s) {
        return s == Source.CHAT || s == Source.EXPLORE || s == Source.HARVEST || s == Source.OFFERING || s == Source.CRAFT;
    }

    /** 今天這個來源還能拿多少（-1 = 不限） */
    public long remaining(PlayerData d, Source s) {
        long cap = cap(s);
        if (cap <= 0) return -1;
        return Math.max(0, cap - d.earned(s));
    }

    /**
     * 讓在線玩家產生 mana，套用 AFK 判定與每日上限。
     * @param reason 顯示給玩家的原因
     * @param quiet  true 時改用動作列提示（給頻繁的小額來源）
     * @return 實際得到的數量
     */
    public long earn(Player p, Source s, long amount, Component reason, String detail, boolean quiet) {
        PlayerData d = get(p);
        if (d == null || amount <= 0 || !s.earning) return 0;
        if (needsActive(s) && isAfk(d)) return 0;

        long granted = amount;
        long rem = remaining(d, s);
        if (rem >= 0) granted = Math.min(granted, rem);
        if (granted <= 0) {
            if (d.capNotified.add(s)) plugin.msg().send(p, "cap-reached", "reason", s.display);
            return 0;
        }

        d.balance += granted;
        d.earnedToday.merge(s, granted, Long::sum);
        String id = d.uuid.toString();
        String day = currentDay;
        long g = granted;
        db.run(c -> {
            Database.addBalance(c, id, g);
            Database.addDaily(c, day, id, s.key, g);
            Database.ledger(c, id, g, s.key, detail);
            return null;
        });

        Component line = plugin.msg().render("earned", "amount", granted, "reason", reason);
        if (quiet) p.sendActionBar(line);
        else plugin.msg().send(p, "earned", "amount", granted, "reason", reason);
        return granted;
    }

    /** 給可能不在線的玩家產生 mana（例如導師下線時新手完成引導）。上限在資料庫端判定。 */
    public void earnAnywhere(UUID uuid, Source s, long amount, Component reason, String detail) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null && get(p) != null) {
            earn(p, s, amount, reason, detail, false);
            return;
        }
        String id = uuid.toString();
        String day = currentDay;
        long cap = cap(s);
        db.run(c -> {
            if (!Database.accountExists(c, id)) return null;
            long g = amount;
            if (cap > 0) g = Math.min(g, Math.max(0, cap - Database.getDaily(c, day, id, s.key)));
            if (g <= 0) return null;
            Database.addBalance(c, id, g);
            Database.addDaily(c, day, id, s.key, g);
            Database.ledger(c, id, g, s.key, detail);
            return null;
        });
    }

    /** daily 表中「每種手藝」的紀錄 key 前綴（和來源 key 放在同一張表） */
    public static final String SKILL_DAILY_PREFIX = "craft:";
    public static final String DEX_DAILY_PREFIX = "dexcat:";
    public static final String CHALLENGE_DAILY_PREFIX = "challenge:";

    /** 記錄今天已拿過的合作挑戰（重新登入也記得） */
    public boolean markChallenge(PlayerData d, String key) {
        if (!d.challengesToday.add(key)) return false;
        String id = d.uuid.toString(), day = currentDay, k = CHALLENGE_DAILY_PREFIX + key;
        db.run(c -> {
            Database.addDaily(c, day, id, k, 1);
            return null;
        });
        return true;
    }

    /** 記錄圖鑑某分類今天拿到的 mana */
    public void addDexDaily(PlayerData d, String category, long amount) {
        if (amount <= 0) return;
        d.dexToday.merge(category, amount, Long::sum);
        String id = d.uuid.toString(), day = currentDay, key = DEX_DAILY_PREFIX + category;
        db.run(c -> {
            Database.addDaily(c, day, id, key, amount);
            return null;
        });
    }

    /** 記錄某種手藝今天拿到的 mana（總量已經由 earn 記在 craft 來源下） */
    public void addSkillDaily(PlayerData d, Skill s, long amount) {
        if (amount <= 0) return;
        d.skillToday.merge(s, amount, Long::sum);
        String id = d.uuid.toString(), day = currentDay, key = SKILL_DAILY_PREFIX + s.key;
        db.run(c -> {
            Database.addDaily(c, day, id, key, amount);
            return null;
        });
    }

    /** 寫回手藝經驗與點數累積（絕對值） */
    public void saveCraft(PlayerData d, Skill s) {
        String id = d.uuid.toString();
        double xp = d.xp(s), pts = d.craftPoints.getOrDefault(s, 0.0);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO crafts(uuid, skill, xp, points) VALUES(?,?,?,?) ON CONFLICT(uuid, skill) DO UPDATE SET xp=excluded.xp, points=excluded.points")) {
                ps.setString(1, id);
                ps.setString(2, s.key);
                ps.setDouble(3, xp);
                ps.setDouble(4, pts);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void saveAllCrafts(PlayerData d) {
        for (Skill s : Skill.values()) if (d.craftXp.containsKey(s) || d.craftPoints.containsKey(s)) saveCraft(d, s);
    }

    // ---------------- 花費與轉移 ----------------

    public boolean spend(Player p, long amount, Source s, String detail) {
        PlayerData d = get(p);
        if (d == null || amount <= 0) return false;
        if (d.balance < amount) {
            plugin.msg().send(p, "not-enough", "need", amount, "have", d.balance);
            return false;
        }
        d.balance -= amount;
        String id = d.uuid.toString();
        db.run(c -> {
            Database.addBalance(c, id, -amount);
            Database.ledger(c, id, -amount, s.key, detail);
            return null;
        });
        return true;
    }

    /** 不受上限的直接調整（贈與入帳、管理員調整），玩家可不在線。 */
    public void adjust(UUID uuid, long delta, Source s, String detail) {
        PlayerData d = online.get(uuid);
        if (d != null) d.balance += delta;
        String id = uuid.toString();
        db.run(c -> {
            Database.addBalance(c, id, delta);
            Database.ledger(c, id, delta, s.key, detail);
            return null;
        });
    }

    public void addHarvestPoints(PlayerData d, long delta) {
        d.harvestPoints += delta;
        String id = d.uuid.toString();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET harvest_points = harvest_points + ? WHERE uuid=?")) {
                ps.setLong(1, delta);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------------- 歸土 ----------------

    /** 啟動時與跨日時呼叫；以 kv.last_decay_day 確保同一天只執行一次。 */
    public void runDecayIfNeeded() {
        String day = currentDay;
        boolean enabled = plugin.getConfig().getBoolean("decay.enabled", true);
        long threshold = plugin.getConfig().getLong("decay.threshold", 500);
        double rate = plugin.getConfig().getDouble("decay.rate", 0.05);

        db.query(c -> {
            String last = Database.getKv(c, "last_decay_day");
            if (last == null) {
                Database.setKv(c, "last_decay_day", day);
                return null;
            }
            if (last.compareTo(day) >= 0) return null;
            Database.setKv(c, "last_decay_day", day);
            if (!enabled || rate <= 0) return null;
            return decayNow(c, threshold, rate);
        }).thenAccept(result -> {
            if (result != null) plugin.sync(() -> applyDecayResult(result));
        });
    }

    /** 管理員手動觸發（測試用），不檢查日期。 */
    public void forceDecay() {
        long threshold = plugin.getConfig().getLong("decay.threshold", 500);
        double rate = plugin.getConfig().getDouble("decay.rate", 0.05);
        db.query(c -> decayNow(c, threshold, rate))
                .thenAccept(result -> plugin.sync(() -> applyDecayResult(result)));
    }

    private Map<UUID, Long> decayNow(java.sql.Connection c, long threshold, double rate) throws java.sql.SQLException {
        Map<UUID, Long> out = new HashMap<>();
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, balance FROM accounts WHERE balance > ?")) {
            ps.setLong(1, threshold);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2))});
            }
        }
        c.setAutoCommit(false);
        try {
            long total = 0;
            for (String[] r : rows) {
                long excess = Long.parseLong(r[1]) - threshold;
                long amt = (long) Math.ceil(excess * rate);
                if (amt <= 0) continue;
                Database.addBalance(c, r[0], -amt);
                Database.ledger(c, r[0], -amt, Source.DECAY.key, "超過 " + threshold + " 的部分歸土");
                out.put(UUID.fromString(r[0]), amt);
                total += amt;
            }
            if (total > 0) LifeTree.addTotalSql(c, total);
            c.commit();
        } catch (java.sql.SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        return out;
    }

    private void applyDecayResult(Map<UUID, Long> result) {
        long total = 0;
        for (Map.Entry<UUID, Long> e : result.entrySet()) {
            total += e.getValue();
            PlayerData d = online.get(e.getKey());
            if (d != null) {
                d.balance -= e.getValue();
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) plugin.msg().send(p, "decayed", "amount", e.getValue());
            }
        }
        if (total > 0) plugin.tree().addCachedOnly(total);
        plugin.getLogger().info("今日歸土完成：" + result.size() + " 位玩家，共 " + total + " mana 流入生命之樹");
    }

    // ---------------- 其他 ----------------

    public Set<UUID> onlineIds() {
        return new HashSet<>(online.keySet());
    }
}
