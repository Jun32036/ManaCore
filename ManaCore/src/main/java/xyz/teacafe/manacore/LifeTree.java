package xyz.teacafe.manacore;

import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 生命之樹：歸土與捐獻的累計，達到階段時全服解鎖祝福（執行設定好的主控台指令）。 */
public final class LifeTree {

    public record Stage(long threshold, String name, List<String> commands) {}

    private final ManaCorePlugin plugin;
    private final Database db;
    private long total;
    private int reached; // 已達成的階段數
    private List<Stage> stages = new ArrayList<>();

    public LifeTree(ManaCorePlugin plugin, Database db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void reloadStages() {
        List<Stage> list = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("life-tree.stages")) {
            long th = m.get("threshold") instanceof Number n ? n.longValue() : 0;
            String name = String.valueOf(m.get("name") == null ? "?" : m.get("name"));
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) for (Object o : l) cmds.add(String.valueOf(o));
            list.add(new Stage(th, name, cmds));
        }
        list.sort((a, b) -> Long.compare(a.threshold(), b.threshold()));
        stages = list;
    }

    /** 啟動時同步讀取（資料庫已開啟）。 */
    public void load() throws Exception {
        reloadStages();
        long[] r = db.query(c -> {
            String t = Database.getKv(c, "tree_total");
            if (t == null) Database.setKv(c, "tree_total", "0");
            String s = Database.getKv(c, "tree_stage");
            if (s == null) Database.setKv(c, "tree_stage", "0");
            return new long[]{t == null ? 0 : Long.parseLong(t), s == null ? 0 : Long.parseLong(s)};
        }).get();
        total = r[0];
        reached = (int) r[1];
    }

    public long total() { return total; }
    public List<Stage> stages() { return stages; }
    public int reached() { return reached; }

    public Stage nextStage() {
        return reached < stages.size() ? stages.get(reached) : null;
    }

    public Stage currentStage() {
        return reached > 0 && reached <= stages.size() ? stages.get(reached - 1) : null;
    }

    /** 玩家捐獻或其他需要寫入資料庫的增加。 */
    public void add(long delta) {
        db.run(c -> {
            addTotalSql(c, delta);
            return null;
        });
        addCachedOnly(delta);
    }

    /** 資料庫已經更新過（例如歸土），只更新快取並檢查階段。 */
    public void addCachedOnly(long delta) {
        total += delta;
        checkStages();
    }

    /** 管理員直接設定總量（不會回頭撤銷已解鎖的祝福）。 */
    public void set(long value) {
        total = value;
        db.run(c -> {
            Database.setKv(c, "tree_total", String.valueOf(value));
            return null;
        });
        checkStages();
    }

    private void checkStages() {
        while (reached < stages.size() && total >= stages.get(reached).threshold()) {
            Stage st = stages.get(reached);
            reached++;
            int now = reached;
            db.run(c -> {
                Database.setKv(c, "tree_stage", String.valueOf(now));
                return null;
            });
            Bukkit.getServer().broadcast(plugin.msg().render("tree-stage", "name", st.name()));
            for (String cmd : st.commands()) {
                String line = cmd.replace("{stage}", String.valueOf(now)).replace("{name}", st.name());
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                } catch (Exception e) {
                    plugin.getLogger().warning("生命之樹祝福指令執行失敗：" + line + "（" + e.getMessage() + "）");
                }
            }
        }
    }

    public static void addTotalSql(Connection c, long delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kv(k, v) VALUES('tree_total', ?) ON CONFLICT(k) DO UPDATE SET v = CAST(v AS INTEGER) + CAST(excluded.v AS INTEGER)")) {
            ps.setString(1, String.valueOf(delta));
            ps.executeUpdate();
        }
    }

    /** 設定檔更動後：重讀階段，並以目前總量補足已達成的階段數（不重複執行已解鎖的指令）。 */
    public void reload() {
        reloadStages();
        checkStages();
    }
}
