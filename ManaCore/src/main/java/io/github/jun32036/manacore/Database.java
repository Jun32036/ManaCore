package io.github.jun32036.manacore;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 存取。所有 SQL 都在同一條背景執行緒依序執行，所以寫入順序與主執行緒送出的順序一致。
 * 餘額一律以「差額」寫入（balance = balance + ?），記憶體快取與資料庫因此永遠可以對帳。
 */
public final class Database {

    @FunctionalInterface
    public interface SqlWork<T> { T run(Connection c) throws SQLException; }

    private final Logger log;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ManaCore-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    public Database(Logger log) { this.log = log; }

    public void open(File file) throws Exception {
        file.getParentFile().mkdirs();
        CompletableFuture.runAsync(() -> {
            try {
                conn = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.getAbsolutePath(), new Properties());
                try (Statement s = conn.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=NORMAL");
                    s.execute("CREATE TABLE IF NOT EXISTS accounts(uuid TEXT PRIMARY KEY, name TEXT, balance INTEGER NOT NULL DEFAULT 0, harvest_points INTEGER NOT NULL DEFAULT 0, last_ip TEXT, created INTEGER)");
                    s.execute("CREATE TABLE IF NOT EXISTS daily(day TEXT, uuid TEXT, source TEXT, amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, uuid, source))");
                    s.execute("CREATE TABLE IF NOT EXISTS ledger(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, uuid TEXT, delta INTEGER, source TEXT, detail TEXT)");
                    s.execute("CREATE INDEX IF NOT EXISTS ledger_uuid ON ledger(uuid, id)");
                    s.execute("CREATE TABLE IF NOT EXISTS discoveries(uuid TEXT, key TEXT, ts INTEGER, PRIMARY KEY(uuid, key))");
                    s.execute("CREATE TABLE IF NOT EXISTS thanks_log(day TEXT, giver TEXT, receiver TEXT, PRIMARY KEY(day, giver, receiver))");
                    s.execute("CREATE TABLE IF NOT EXISTS works(id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, name TEXT, world TEXT, x REAL, y REAL, z REAL)");
                    s.execute("CREATE TABLE IF NOT EXISTS comments(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, day TEXT, owner TEXT, visitor TEXT, visitor_name TEXT, message TEXT, seen INTEGER NOT NULL DEFAULT 0)");
                    s.execute("CREATE INDEX IF NOT EXISTS comments_owner ON comments(owner, id)");
                    s.execute("CREATE TABLE IF NOT EXISTS mentors(newbie TEXT PRIMARY KEY, mentor TEXT, status TEXT, exchanged INTEGER NOT NULL DEFAULT 0, commented INTEGER NOT NULL DEFAULT 0, created INTEGER)");
                    s.execute("CREATE TABLE IF NOT EXISTS landmarks(name TEXT PRIMARY KEY, world TEXT, x REAL, y REAL, z REAL, radius REAL)");
                    s.execute("CREATE TABLE IF NOT EXISTS altars(world TEXT, x INTEGER, y INTEGER, z INTEGER, PRIMARY KEY(world, x, y, z))");
                    s.execute("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)");
                    // ---- 1.3.0 經濟系統 ----
                    s.execute("CREATE TABLE IF NOT EXISTS crafts(uuid TEXT, skill TEXT, xp REAL NOT NULL DEFAULT 0, points REAL NOT NULL DEFAULT 0, PRIMARY KEY(uuid, skill))");
                    s.execute("CREATE TABLE IF NOT EXISTS order_progress(day TEXT, idx INTEGER, delivered INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, idx))");
                    s.execute("CREATE TABLE IF NOT EXISTS order_contrib(day TEXT, idx INTEGER, uuid TEXT, bundles INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day, idx, uuid))");
                    s.execute("CREATE TABLE IF NOT EXISTS commissions(id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, poster TEXT, poster_name TEXT, poster_ip TEXT, kind TEXT, material TEXT, amount INTEGER, reward INTEGER, note TEXT, status TEXT, taker TEXT, taker_name TEXT, items BLOB, claimed INTEGER NOT NULL DEFAULT 1, finished INTEGER)");
                    s.execute("CREATE INDEX IF NOT EXISTS commissions_status ON commissions(status)");
                    s.execute("CREATE TABLE IF NOT EXISTS project_state(project TEXT PRIMARY KEY, status TEXT, mana INTEGER NOT NULL DEFAULT 0, finished INTEGER)");
                    s.execute("CREATE TABLE IF NOT EXISTS project_needs(project TEXT, material TEXT, have INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(project, material))");
                    // ---- 1.5.0 煉金與市集 ----
                    s.execute("CREATE TABLE IF NOT EXISTS alchemy(uuid TEXT PRIMARY KEY, av INTEGER NOT NULL DEFAULT 0)");
                    s.execute("CREATE TABLE IF NOT EXISTS alchemy_known(uuid TEXT, material TEXT, PRIMARY KEY(uuid, material))");
                    s.execute("CREATE TABLE IF NOT EXISTS market(id INTEGER PRIMARY KEY AUTOINCREMENT, created INTEGER, seller TEXT, seller_name TEXT, material TEXT, amount INTEGER, price INTEGER, status TEXT, items BLOB, finished INTEGER)");
                    s.execute("CREATE INDEX IF NOT EXISTS market_status ON market(status)");
                    s.execute("CREATE TABLE IF NOT EXISTS project_contrib(project TEXT, uuid TEXT, name TEXT, items INTEGER NOT NULL DEFAULT 0, mana INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(project, uuid))");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, exec).get(30, TimeUnit.SECONDS);
    }

    /** 背景執行，結果以 future 回傳（回呼仍在背景執行緒，需要碰 Bukkit 時請用 plugin.sync）。 */
    public <T> CompletableFuture<T> query(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.run(conn);
            } catch (SQLException e) {
                log.log(Level.SEVERE, "資料庫錯誤", e);
                throw new RuntimeException(e);
            }
        }, exec);
    }

    /** 背景執行、不需要結果的寫入。 */
    public void run(SqlWork<?> work) {
        exec.execute(() -> {
            try {
                work.run(conn);
            } catch (SQLException e) {
                log.log(Level.SEVERE, "資料庫錯誤", e);
            }
        });
    }

    public void close() {
        exec.shutdown();
        try {
            if (!exec.awaitTermination(20, TimeUnit.SECONDS)) log.warning("資料庫仍有未完成的寫入");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    // ---------- 共用 SQL ----------

    public static void addBalance(Connection c, String uuid, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE uuid = ?")) {
            ps.setLong(1, delta);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public static void addDaily(Connection c, String day, String uuid, String source, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily(day, uuid, source, amount) VALUES(?,?,?,?) ON CONFLICT(day, uuid, source) DO UPDATE SET amount = amount + excluded.amount")) {
            ps.setString(1, day);
            ps.setString(2, uuid);
            ps.setString(3, source);
            ps.setLong(4, delta);
            ps.executeUpdate();
        }
    }

    public static long getDaily(Connection c, String day, String uuid, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM daily WHERE day=? AND uuid=? AND source=?")) {
            ps.setString(1, day);
            ps.setString(2, uuid);
            ps.setString(3, source);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public static void ledger(Connection c, String uuid, long delta, String source, String detail) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ledger(ts, uuid, delta, source, detail) VALUES(?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, uuid);
            ps.setLong(3, delta);
            ps.setString(4, source);
            ps.setString(5, detail);
            ps.executeUpdate();
        }
    }

    public static String getKv(Connection c, String k) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE k=?")) {
            ps.setString(1, k);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void setKv(Connection c, String k, String v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kv(k, v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        }
    }

    public static boolean accountExists(Connection c, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
