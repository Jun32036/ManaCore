package io.github.jun32036.manacore;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** 作品、地標、祭壇方塊的位置資料，全部快取在記憶體。 */
public final class Places {

    public record Work(long id, UUID owner, String name, String world, double x, double y, double z) {
        public boolean near(Location l, double radius) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    public record Landmark(String name, String world, double x, double y, double z, double radius) {
        public boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }
    }

    private final ManaCorePlugin plugin;
    private final Database db;
    private final Map<UUID, List<Work>> works = new HashMap<>();
    private final Map<String, Landmark> landmarks = new HashMap<>();
    private final Set<String> altars = new HashSet<>();

    public Places(ManaCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void load() throws Exception {
        db.query(c -> {
            try (Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT id, owner, name, world, x, y, z FROM works")) {
                    while (rs.next()) {
                        Work w = new Work(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7));
                        works.computeIfAbsent(w.owner(), k -> new ArrayList<>()).add(w);
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT name, world, x, y, z, radius FROM landmarks")) {
                    while (rs.next()) {
                        Landmark l = new Landmark(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6));
                        landmarks.put(l.name(), l);
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT world, x, y, z FROM altars")) {
                    while (rs.next()) altars.add(rs.getString(1) + ":" + rs.getInt(2) + ":" + rs.getInt(3) + ":" + rs.getInt(4));
                }
            }
            return null;
        }).get();
    }

    // ---------------- 作品 ----------------

    public List<Work> worksOf(UUID owner) {
        return works.getOrDefault(owner, List.of());
    }

    /** 附近所有人的作品（不含 exclude 自己的），由近到遠 */
    public List<Work> worksNear(Location l, double radius, UUID exclude) {
        List<Work> out = new ArrayList<>();
        for (List<Work> list : works.values())
            for (Work w : list)
                if (!w.owner().equals(exclude) && w.near(l, radius)) out.add(w);
        out.sort((a, b) -> Double.compare(dist2(a, l), dist2(b, l)));
        return out;
    }

    private static double dist2(Work w, Location l) {
        double dx = l.getX() - w.x(), dy = l.getY() - w.y(), dz = l.getZ() - w.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public Work workNear(UUID owner, Location l, double radius) {
        for (Work w : worksOf(owner)) if (w.near(l, radius)) return w;
        return null;
    }

    public void addWork(UUID owner, String name, Location l, Consumer<Work> done) {
        String world = l.getWorld().getName();
        double x = l.getX(), y = l.getY(), z = l.getZ();
        db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO works(owner, name, world, x, y, z) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                ps.setString(3, world);
                ps.setDouble(4, x);
                ps.setDouble(5, y);
                ps.setDouble(6, z);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }).thenAccept(id -> plugin.sync(() -> {
            Work w = new Work(id, owner, name, world, x, y, z);
            works.computeIfAbsent(owner, k -> new ArrayList<>()).add(w);
            done.accept(w);
        }));
    }

    public boolean removeWork(UUID owner, Work w) {
        List<Work> list = works.get(owner);
        if (list == null || !list.remove(w)) return false;
        long id = w.id();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM works WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
        return true;
    }

    // ---------------- 地標 ----------------

    public Collection<Landmark> landmarks() {
        return landmarks.values();
    }

    public void putLandmark(Landmark l) {
        landmarks.put(l.name(), l);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO landmarks(name, world, x, y, z, radius) VALUES(?,?,?,?,?,?)")) {
                ps.setString(1, l.name());
                ps.setString(2, l.world());
                ps.setDouble(3, l.x());
                ps.setDouble(4, l.y());
                ps.setDouble(5, l.z());
                ps.setDouble(6, l.radius());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public boolean removeLandmark(String name) {
        if (landmarks.remove(name) == null) return false;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM landmarks WHERE name=?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            return null;
        });
        return true;
    }

    // ---------------- 祭壇方塊 ----------------

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    public boolean isAltar(Block b) {
        return !altars.isEmpty() && altars.contains(key(b));
    }

    public boolean setAltar(Block b, boolean on) {
        String k = key(b);
        boolean changed = on ? altars.add(k) : altars.remove(k);
        if (!changed) return false;
        String w = b.getWorld().getName();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(on
                    ? "INSERT OR IGNORE INTO altars(world, x, y, z) VALUES(?,?,?,?)"
                    : "DELETE FROM altars WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, w);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            }
            return null;
        });
        return true;
    }

    public int altarCount() {
        return altars.size();
    }
}
