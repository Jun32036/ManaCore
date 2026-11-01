package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 社區工程：服主在設定檔定義「全服一起蓋的東西」（石橋、公共農場、節慶廣場），
 * 需要大量物資與 mana。玩家可以：
 * - 捐物資：生產型玩家（農夫、礦工、廚師）的產出有了去處，每 items-per-mana 個換 1 mana（有每日上限）；
 *   超過上限仍然可以捐，會記在榮譽榜上。
 * - 投入 mana：mana 最主要的出口，存太多的人可以讓它變成看得見的公共建設。
 * 完工時執行設定好的指令（開放傳送點、煙火、公告），並給所有出過力的人一筆完工獎勵。
 */
public final class ProjectService {

    public record Need(Material material, int goal, int perMana) {}

    public static final class Project {
        public final String id;
        public final String name;
        public final String description;
        public final Material icon;
        public final Map<Material, Need> needs;
        public final long manaGoal;
        public final List<String> onComplete;
        public final Map<Material, Integer> have = new HashMap<>();
        public long mana;
        public boolean done;
        /** 玩家 → {物品數, mana, 名字} */
        public final Map<UUID, Object[]> contrib = new HashMap<>();

        Project(String id, String name, String description, Material icon, Map<Material, Need> needs, long manaGoal, List<String> onComplete) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
            this.needs = needs;
            this.manaGoal = manaGoal;
            this.onComplete = onComplete;
        }

        public int have(Material m) {
            return have.getOrDefault(m, 0);
        }

        public boolean ready() {
            for (Need n : needs.values()) if (have(n.material()) < n.goal()) return false;
            return mana >= manaGoal;
        }

        /** 0~1 的整體進度（物資與 mana 平均） */
        public double progress() {
            double sum = 0;
            int parts = 0;
            for (Need n : needs.values()) {
                sum += Math.min(1.0, have(n.material()) / (double) n.goal());
                parts++;
            }
            if (manaGoal > 0) {
                sum += Math.min(1.0, mana / (double) manaGoal);
                parts++;
            }
            return parts == 0 ? 1 : sum / parts;
        }

        /** 貢獻排行（依物品數＋mana×10 粗略排序） */
        public List<Object[]> top(int n) {
            List<Object[]> list = new ArrayList<>(contrib.values());
            list.sort((a, b) -> Long.compare(score(b), score(a)));
            return list.subList(0, Math.min(n, list.size()));
        }

        private static long score(Object[] c) {
            return ((Number) c[0]).longValue() + ((Number) c[1]).longValue() * 10;
        }
    }

    private final ManaCorePlugin plugin;
    private final Map<String, Project> projects = new LinkedHashMap<>();
    /** 還沒湊滿 1 mana 的捐獻零頭：玩家|工程|物品 → 數量 */
    private final Map<String, Integer> leftovers = new HashMap<>();

    public ProjectService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("sources.project.enabled", true);
    }

    public List<Project> all() {
        return new ArrayList<>(projects.values());
    }

    public Project get(String id) {
        return projects.get(id);
    }

    // ---------------- 載入 ----------------

    public void load() throws Exception {
        projects.clear();
        ConfigurationSection list = plugin.section("projects");
        int defPerMana = plugin.getConfig().getInt("sources.project.items-per-mana", 32);
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(id);
                if (s == null) continue;
                Map<Material, Need> needs = new LinkedHashMap<>();
                ConfigurationSection ns = s.getConfigurationSection("needs");
                if (ns != null) {
                    for (String k : ns.getKeys(false)) {
                        Material m = Material.matchMaterial(k);
                        if (m == null || !m.isItem()) {
                            plugin.getLogger().warning("社區工程 " + id + " 的物資 " + k + " 不是有效物品，已略過");
                            continue;
                        }
                        int goal, perMana = s.getInt("items-per-mana", defPerMana);
                        if (ns.isConfigurationSection(k)) {
                            goal = ns.getInt(k + ".amount", 64);
                            perMana = ns.getInt(k + ".items-per-mana", perMana);
                        } else goal = ns.getInt(k, 64);
                        needs.put(m, new Need(m, Math.max(1, goal), Math.max(1, perMana)));
                    }
                }
                Material icon = Material.matchMaterial(s.getString("icon", "BRICKS"));
                projects.put(id, new Project(id, s.getString("name", id), s.getString("description", ""),
                        icon == null ? Material.BRICKS : icon, needs, Math.max(0, s.getLong("mana-goal", 0)), s.getStringList("on-complete")));
            }
        }
        plugin.db().query(c -> {
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT project, status, mana FROM project_state")) {
                    while (rs.next()) {
                        Project p = projects.get(rs.getString(1));
                        if (p == null) continue;
                        p.done = "done".equals(rs.getString(2));
                        p.mana = rs.getLong(3);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT project, material, have FROM project_needs")) {
                    while (rs.next()) {
                        Project p = projects.get(rs.getString(1));
                        Material m = Material.matchMaterial(rs.getString(2));
                        if (p != null && m != null) p.have.put(m, rs.getInt(3));
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT project, uuid, name, items, mana FROM project_contrib")) {
                    while (rs.next()) {
                        Project p = projects.get(rs.getString(1));
                        if (p != null) p.contrib.put(UUID.fromString(rs.getString(2)), new Object[]{rs.getLong(4), rs.getLong(5), rs.getString(3)});
                    }
                }
            }
            return null;
        }).get();
    }

    // ---------------- 貢獻 ----------------

    /** 捐出背包裡所有這種物資（最多捐到目標為止） */
    public boolean donateItems(Player p, Project pr, Material m) {
        if (!enabled() || pr.done) return false;
        PlayerData d = plugin.mana().get(p);
        Need need = pr.needs.get(m);
        if (d == null || need == null) return false;
        int missing = need.goal() - pr.have(m);
        if (missing <= 0) {
            plugin.msg().raw(p, "<gray>這項物資已經湊齊了，看看其他需要的東西吧！</gray>");
            return false;
        }
        int have = OrderService.countPlain(p, m);
        if (have <= 0) {
            plugin.msg().raw(p, "<gray>你身上沒有 <i>。</gray>", "i", Component.translatable(m.translationKey()));
            return false;
        }
        int give = Math.min(have, missing);
        OrderService.removePlain(p, m, give);
        pr.have.merge(m, give, Integer::sum);
        record(pr, p, give, 0);
        String pid = pr.id, mat = m.getKey().toString();
        int now = pr.have(m);
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO project_needs(project, material, have) VALUES(?,?,?) ON CONFLICT(project, material) DO UPDATE SET have=excluded.have")) {
                ps.setString(1, pid);
                ps.setString(2, mat);
                ps.setInt(3, now);
                ps.executeUpdate();
            }
            return null;
        });

        // 換算 mana（零頭留到下次）
        String lk = p.getUniqueId() + "|" + pr.id + "|" + m.getKey();
        int total = leftovers.getOrDefault(lk, 0) + give;
        long mana = total / need.perMana();
        leftovers.put(lk, (int) (total % need.perMana()));
        long got = 0;
        if (mana > 0) {
            if (plugin.mana().remaining(d, Source.PROJECT) == 0)
                plugin.msg().raw(p, "<gray>今天社區工程的 mana 已達上限，這次的捐獻會記在榮譽榜上，謝謝你！</gray>");
            else got = plugin.mana().earn(p, Source.PROJECT, mana, Component.text("為「" + pr.name + "」捐物資"), pr.id + " " + give + "x" + m.getKey().getKey(), false);
        }
        plugin.msg().raw(p, "<green>你為「<n>」捐了 <i> ×<a>。</green>", "n", pr.name, "i", Component.translatable(m.translationKey()), "a", give);
        if (got == 0 && mana == 0)
            plugin.msg().raw(p, "<dark_gray>（每 <k> 個換 1 mana，零頭會累積到下次）</dark_gray>", "k", need.perMana());
        plugin.craft().addXp(p, Skill.BUILD, give / (double) need.perMana());
        checkDone(pr);
        return true;
    }

    public boolean invest(Player p, Project pr, long amount) {
        if (!enabled() || pr.done || amount <= 0) return false;
        long missing = pr.manaGoal - pr.mana;
        if (missing <= 0) {
            plugin.msg().raw(p, "<gray>這項工程的 mana 已經湊齊了，還缺的是物資。</gray>");
            return false;
        }
        long a = Math.min(amount, missing);
        if (!plugin.mana().spend(p, a, Source.INVEST, "投入「" + pr.name + "」")) return false;
        pr.mana += a;
        record(pr, p, 0, a);
        saveState(pr);
        plugin.msg().raw(p, "<green>你為「<n>」投入了 <a> mana，它會變成大家看得見的建設。</green>", "n", pr.name, "a", a);
        checkDone(pr);
        return true;
    }

    private void record(Project pr, Player p, long items, long mana) {
        Object[] c = pr.contrib.computeIfAbsent(p.getUniqueId(), k -> new Object[]{0L, 0L, p.getName()});
        c[0] = ((Number) c[0]).longValue() + items;
        c[1] = ((Number) c[1]).longValue() + mana;
        c[2] = p.getName();
        String pid = pr.id, uid = p.getUniqueId().toString(), name = p.getName();
        plugin.db().run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO project_contrib(project, uuid, name, items, mana) VALUES(?,?,?,?,?) ON CONFLICT(project, uuid) DO UPDATE SET name=excluded.name, items=items+excluded.items, mana=mana+excluded.mana")) {
                ps.setString(1, pid);
                ps.setString(2, uid);
                ps.setString(3, name);
                ps.setLong(4, items);
                ps.setLong(5, mana);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void saveState(Project pr) {
        String pid = pr.id, st = pr.done ? "done" : "open";
        long mana = pr.mana;
        long fin = pr.done ? System.currentTimeMillis() : 0;
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO project_state(project, status, mana, finished) VALUES(?,?,?,?) ON CONFLICT(project) DO UPDATE SET status=excluded.status, mana=excluded.mana, finished=excluded.finished")) {
                ps.setString(1, pid);
                ps.setString(2, st);
                ps.setLong(3, mana);
                ps.setLong(4, fin);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void checkDone(Project pr) {
        if (pr.done || !pr.ready()) return;
        pr.done = true;
        saveState(pr);
        Bukkit.getServer().broadcast(plugin.msg().render("prefix").append(
                Component.text("🏗 社區工程「" + pr.name + "」完工了！謝謝 " + pr.contrib.size() + " 位一起出力的玩家。", NamedTextColor.GOLD)));
        StringBuilder top = new StringBuilder();
        for (Object[] c : pr.top(3)) top.append(top.isEmpty() ? "" : "、").append(c[2]);
        if (!top.isEmpty()) Bukkit.getServer().broadcast(Component.text("　出力最多：" + top, NamedTextColor.YELLOW));
        for (String cmd : pr.onComplete) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{name}", pr.name));
            } catch (Exception e) {
                plugin.getLogger().warning("社區工程完工指令失敗：" + cmd + "（" + e.getMessage() + "）");
            }
        }
        long bonus = plugin.getConfig().getLong("sources.project.completion-bonus", 10);
        if (bonus > 0)
            for (UUID id : pr.contrib.keySet())
                plugin.mana().earnAnywhere(id, Source.EVENT, bonus, Component.text("「" + pr.name + "」完工"), "工程完工：" + pr.id);
    }

    /** 管理員：重置某項工程的進度（重新開放） */
    public boolean reset(String id) {
        Project pr = projects.get(id);
        if (pr == null) return false;
        pr.done = false;
        pr.mana = 0;
        pr.have.clear();
        pr.contrib.clear();
        plugin.db().run(c -> {
            for (String t : List.of("project_state", "project_needs", "project_contrib")) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE project=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
        return true;
    }
}
