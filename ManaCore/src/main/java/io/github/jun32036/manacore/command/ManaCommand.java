package io.github.jun32036.manacore.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import io.github.jun32036.manacore.LifeTree;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.MentorService;
import io.github.jun32036.manacore.Msg;
import io.github.jun32036.manacore.Places;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** /mana 玩家指令 */
public final class ManaCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "status", "skills", "dex", "orders", "board", "request", "projects", "alchemy", "market", "sell",
            "give", "donate", "tree", "work", "comment", "comments", "mentor", "log");

    private final ManaCorePlugin plugin;

    public ManaCommand(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("這個指令只能由玩家使用，主控台請用 /manaadmin");
            return true;
        }
        PlayerData d = plugin.mana().get(p);
        if (d == null) {
            plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        String[] rest = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "", "menu", "gui" -> plugin.openHub(p);
            case "chest" -> plugin.menu().openMain(p);
            case "status", "info" -> status(p, d);
            case "give" -> give(p, d, rest);
            case "donate" -> donate(p, d, rest);
            case "tree" -> tree(p);
            case "work", "works" -> work(p, rest);
            case "comment" -> comment(p, d, rest);
            case "comments" -> comments(p);
            case "mentor" -> mentor(p, rest);
            case "log" -> log(p);
            case "skills", "skill", "craft" -> plugin.econMenu().openSkills(p);
            case "dex", "collection" -> plugin.econMenu().openDex(p);
            case "orders", "order" -> plugin.econMenu().openOrders(p);
            case "board", "commissions" -> plugin.econMenu().openBoard(p, 0);
            case "alchemy", "alch", "transmute" -> plugin.alchemyMenu().open(p, 0);
            case "market", "shop", "ah" -> plugin.marketMenu().open(p, 0);
            case "sell" -> { if (plugin.market().list(p, p.getInventory().getHeldItemSlot())) plugin.marketMenu().open(p, 0); }
            case "request" -> request(p, rest);
            case "projects", "project" -> plugin.econMenu().openProjects(p);
            default -> help(p);
        }
        return true;
    }

    // ---------------- /mana ----------------

    private void status(Player p, PlayerData d) {
        var msg = plugin.msg();
        msg.send(p, "balance-header", "balance", d.balance);
        StringBuilder sb = new StringBuilder("<gray>今天：");
        for (Source s : Source.values()) {
            if (!s.earning) continue;
            long cap = plugin.mana().cap(s);
            long got = d.earned(s);
            if (got == 0 && cap == 0) continue;
            sb.append("<white>").append(s.display).append("</white> ").append(got);
            if (cap > 0) sb.append("/").append(cap);
            sb.append("　");
        }
        sb.append("</gray>");
        p.sendMessage(Msg.mm(sb.toString()));
        if (plugin.getConfig().getBoolean("decay.enabled", true)) {
            msg.send(p, "decay-notice", "threshold", plugin.getConfig().getLong("decay.threshold", 500),
                    "rate", Math.round(plugin.getConfig().getDouble("decay.rate", 0.05) * 100) + "%");
        }
        if (plugin.mana().isAfk(d)) msg.send(p, "afk");
    }

    private void help(Player p) {
        String[] lines = {
                "<green>── 生生不息 · mana ──</green>",
                "<white>/mana</white> <gray>打開 mana 選單（大部分功能都能用點的）</gray>",
                "<white>/mana status</white> <gray>用文字顯示 mana 與今天的進度</gray>",
                "<white>/mana skills</white> <gray>生活手藝：等級、新鮮度、今天的收入</gray>",
                "<white>/mana dex</white> <gray>圖鑑：第一次做到的事都有 mana</gray>",
                "<white>/mana orders</white> <gray>城鎮訂單：交出今天城鎮需要的東西</gray>",
                "<white>/mana board</white> <gray>委託板：求助與幫忙</gray>",
                "<white>/mana request <數量> <酬謝></white> <gray>徵求手上拿的物品</gray>",
                "<white>/mana request service <酬謝> <說明></white> <gray>請人幫忙做一件事</gray>",
                "<white>/mana projects</white> <gray>社區工程：一起蓋公共建設</gray>",
                "<white>/mana alchemy</white> <gray>煉金爐：多餘的物品等價換成其他物品</gray>",
                "<white>/mana market</white> <gray>玩家市集：公定價買賣</gray>",
                "<white>/mana sell</white> <gray>把手上的物品整疊上架</gray>",
                "<gray>小技巧：蹲下按 F 也能打開選單；舊版客戶端看不到對話框時用 /mana chest</gray>",
                "<white>/thanks <玩家> [原因]</white> <gray>感謝別人，對方會得到 mana</gray>",
                "<white>/mana give <玩家> <數量> [留言]</white> <gray>把 mana 送給別人</gray>",
                "<white>/mana donate <數量></white> <gray>獻給生命之樹，讓全服一起成長</gray>",
                "<white>/mana tree</white> <gray>生命之樹的進度</gray>",
                "<white>/mana work set <名稱></white> <gray>把腳下登記為你的作品</gray>",
                "<white>/mana work list|remove <編號></white>",
                "<white>/mana comment <作者> <留言></white> <gray>在別人作品附近留言（作者得 mana）</gray>",
                "<white>/mana comments</white> <gray>看你的作品收到的留言</gray>",
                "<white>/mana mentor <新手> | accept | status</white> <gray>導師制度</gray>",
                "<white>/mana log</white> <gray>最近 10 筆 mana 紀錄</gray>",
                "<gray>做同一件事越久收益越少，換著玩、和大家一起玩最划算。</gray>"
        };
        for (String l : lines) p.sendMessage(Msg.mm(l));
    }

    // ---------------- 贈與 / 捐獻 ----------------

    private void give(Player p, PlayerData d, String[] a) {
        if (a.length < 2) {
            plugin.msg().raw(p, "<gray>用法：/mana give <玩家> <數量> [留言]（或打 /mana 用選單送）</gray>");
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            plugin.msg().send(p, "no-player", "name", a[0]);
            return;
        }
        long amount = parsePositive(p, a[1]);
        if (amount <= 0) return;
        String note = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
        plugin.actions().gift(p, t, amount, note);
    }

    private void donate(Player p, PlayerData d, String[] a) {
        if (a.length < 1) {
            plugin.msg().raw(p, "<gray>用法：/mana donate <數量>（或 all）</gray>");
            return;
        }
        long amount = a[0].equalsIgnoreCase("all") ? d.balance : parsePositive(p, a[0]);
        if (amount <= 0) return;
        plugin.actions().donate(p, amount);
    }

    private void tree(Player p) {
        LifeTree t = plugin.tree();
        LifeTree.Stage cur = t.currentStage(), next = t.nextStage();
        p.sendMessage(Msg.mm("<green>── 生命之樹 ──</green>"));
        p.sendMessage(Msg.mm("<gray>目前階段：<white><s></white>　累積：<white><total></white></gray>",
                "s", cur == null ? "種子" : cur.name(), "total", t.total()));
        if (next != null) {
            long need = next.threshold();
            int filled = (int) Math.min(20, t.total() * 20 / Math.max(1, need));
            String bar = "<green>" + "█".repeat(filled) + "</green><dark_gray>" + "█".repeat(20 - filled) + "</dark_gray>";
            p.sendMessage(Msg.mm(bar + " <gray>下一階段「<n>」：<now>/<need></gray>", "n", next.name(), "now", t.total(), "need", need));
        } else {
            p.sendMessage(Msg.mm("<gold>所有祝福都已解鎖，謝謝每一位玩家！</gold>"));
        }
        p.sendMessage(Msg.mm("<dark_gray>生命之樹的養分來自歸土與大家的捐獻。</dark_gray>"));
    }

    // ---------------- 作品 ----------------

    private void work(Player p, String[] a) {
        String op = a.length == 0 ? "list" : a[0].toLowerCase();
        List<Places.Work> mine = plugin.places().worksOf(p.getUniqueId());
        switch (op) {
            case "set", "add" -> {
                String name = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null;
                plugin.actions().addWork(p, name, null);
            }
            case "remove", "del" -> {
                int idx = a.length > 1 ? parseIndex(a[1]) : -1;
                if (idx < 1 || idx > mine.size()) {
                    plugin.msg().raw(p, "<gray>用法：/mana work remove <編號>（編號見 /mana work list）</gray>");
                    return;
                }
                Places.Work w = mine.get(idx - 1);
                plugin.places().removeWork(p.getUniqueId(), w);
                plugin.msg().raw(p, "<green>已移除作品「<n>」。</green>", "n", w.name());
            }
            default -> {
                if (mine.isEmpty()) {
                    plugin.msg().raw(p, "<gray>你還沒有登記作品。站在作品裡輸入 /mana work set <名稱>。</gray>");
                    return;
                }
                p.sendMessage(Msg.mm("<green>── 你的作品 ──</green>"));
                for (int i = 0; i < mine.size(); i++) {
                    Places.Work w = mine.get(i);
                    p.sendMessage(Msg.mm("<white><i>.</white> <n> <dark_gray>(<world> <x>, <y>, <z>)</dark_gray>",
                            "i", i + 1, "n", w.name(), "world", w.world(), "x", (int) w.x(), "y", (int) w.y(), "z", (int) w.z()));
                }
            }
        }
    }

    private void comment(Player p, PlayerData d, String[] a) {
        if (a.length < 2) {
            plugin.msg().raw(p, "<gray>用法：/mana comment <作者> <留言>（要站在對方作品附近；也可以打 /mana 用選單留言）</gray>");
            return;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (owner == null || owner.getUniqueId().equals(p.getUniqueId())) {
            plugin.msg().raw(p, "<gray>找不到這位作者，或是你自己的作品。</gray>");
            return;
        }
        double radius = plugin.actions().worksRadius();
        Places.Work w = plugin.places().workNear(owner.getUniqueId(), p.getLocation(), radius);
        if (w == null) {
            plugin.msg().raw(p, "<gray>你附近 <r> 格內沒有 <n> 登記的作品。</gray>", "r", (int) radius, "n", a[0]);
            return;
        }
        plugin.actions().comment(p, w, String.join(" ", Arrays.copyOfRange(a, 1, a.length)), null);
    }

    private void comments(Player p) {
        String id = p.getUniqueId().toString();
        plugin.db().query(c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, visitor_name, message FROM comments WHERE owner=? ORDER BY id DESC LIMIT 10")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
                    while (rs.next()) out.add(new String[]{f.format(new Date(rs.getLong(1))), rs.getString(2), rs.getString(3)});
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE comments SET seen=1 WHERE owner=? AND seen=0")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            return out;
        }).thenAccept(list -> plugin.sync(() -> {
            if (list.isEmpty()) {
                plugin.msg().raw(p, "<gray>你的作品還沒有留言。</gray>");
                return;
            }
            p.sendMessage(Msg.mm("<green>── 最近的留言 ──</green>"));
            for (String[] r : list)
                p.sendMessage(Msg.mm("<dark_gray><t></dark_gray> <white><v></white><gray>：<m></gray>", "t", r[0], "v", r[1], "m", r[2]));
        }));
    }

    // ---------------- 導師 ----------------

    private void mentor(Player p, String[] a) {
        MentorService ms = plugin.mentors();
        String op = a.length == 0 ? "status" : a[0];
        if (op.equalsIgnoreCase("accept")) {
            String err = ms.accept(p);
            if (err != null) plugin.msg().raw(p, "<gray><e></gray>", "e", err);
            else {
                MentorService.Pair pair = ms.of(p.getUniqueId());
                plugin.msg().raw(p, "<green>你接受了導師的邀請！完成以下三件事，你和導師都會得到 mana：</green>");
                plugin.msg().raw(p, "<gray><pr></gray>", "pr", ms.progress(pair, p));
                Player m = Bukkit.getPlayer(pair.mentor);
                if (m != null) plugin.msg().raw(m, "<green><n> 接受了你的導師邀請。</green>", "n", p.getName());
            }
            return;
        }
        if (op.equalsIgnoreCase("status")) {
            MentorService.Pair pair = ms.of(p.getUniqueId());
            if (pair != null && "active".equals(pair.status)) {
                plugin.msg().raw(p, "<gray>新手引導進度：<pr></gray>", "pr", ms.progress(pair, p));
            } else if (pair != null && "done".equals(pair.status)) {
                plugin.msg().raw(p, "<green>你已經完成新手引導了。</green>");
            } else {
                plugin.msg().raw(p, "<gray>用法：/mana mentor <新手名稱> 邀請新手；新手輸入 /mana mentor accept 接受。</gray>");
            }
            return;
        }
        Player newbie = Bukkit.getPlayerExact(op);
        if (newbie == null) {
            plugin.msg().send(p, "no-player", "name", op);
            return;
        }
        String err = ms.request(p, newbie);
        if (err != null) {
            plugin.msg().raw(p, "<gray><e></gray>", "e", err);
            return;
        }
        plugin.msg().raw(p, "<green>已邀請 <n>，等對方接受。</green>", "n", newbie.getName());
        plugin.msg().raw(newbie, "<green><n> 想當你的導師，帶你認識這個世界！輸入 <white>/mana mentor accept</white> 接受。</green>", "n", p.getName());
    }

    // ---------------- 委託 ----------------

    private void request(Player p, String[] a) {
        var cs = plugin.commissions();
        if (a.length >= 3 && a[0].equalsIgnoreCase("service")) {
            long reward = parsePositive(p, a[1]);
            if (reward <= 0) return;
            cs.postService(p, reward, String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
            return;
        }
        if (a.length >= 2) {
            long amount = parsePositive(p, a[0]);
            long reward = amount > 0 ? parsePositive(p, a[1]) : -1;
            if (amount <= 0 || reward <= 0) return;
            cs.postItem(p, p.getInventory().getItemInMainHand().getType(), (int) Math.min(Integer.MAX_VALUE, amount), reward);
            return;
        }
        plugin.msg().raw(p, "<gray>用法：手拿物品輸入 <white>/mana request <數量> <酬謝></white>，或 <white>/mana request service <酬謝> <說明></white></gray>");
        plugin.msg().raw(p, "<gray>酬謝可選：<t></gray>", "t", cs.rewardTiers().toString());
    }

    // ---------------- 紀錄 ----------------

    private void log(Player p) {
        String id = p.getUniqueId().toString();
        plugin.db().query(c -> {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, delta, source, detail FROM ledger WHERE uuid=? ORDER BY id DESC LIMIT 10")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    SimpleDateFormat f = new SimpleDateFormat("MM/dd HH:mm");
                    while (rs.next()) out.add(new String[]{f.format(new Date(rs.getLong(1))), String.valueOf(rs.getLong(2)), rs.getString(3), rs.getString(4)});
                }
            }
            return out;
        }).thenAccept(list -> plugin.sync(() -> {
            if (list.isEmpty()) {
                plugin.msg().raw(p, "<gray>還沒有任何紀錄。</gray>");
                return;
            }
            p.sendMessage(Msg.mm("<green>── 最近 10 筆 ──</green>"));
            for (String[] r : list) {
                Source s = Source.byKey(r[2]);
                long delta = Long.parseLong(r[1]);
                p.sendMessage(Msg.mm("<dark_gray><t></dark_gray> " + (delta >= 0 ? "<green>+" : "<red>") + "<d></" + (delta >= 0 ? "green" : "red") + "> <white><s></white> <gray><detail></gray>",
                        "t", r[0], "d", delta, "s", s == null ? r[2] : s.display, "detail", r[3] == null ? "" : r[3]));
            }
        }));
    }

    // ---------------- 工具 ----------------

    private long parsePositive(Player p, String s) {
        try {
            long v = Long.parseLong(s);
            if (v > 0) return v;
        } catch (NumberFormatException ignored) {
        }
        plugin.msg().raw(p, "<gray>請輸入正整數。</gray>");
        return -1;
    }

    private static int parseIndex(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "give", "comment" -> null; // 交給 Bukkit 補玩家名稱
                case "mentor" -> {
                    List<String> l = new ArrayList<>(List.of("accept", "status"));
                    Bukkit.getOnlinePlayers().forEach(pl -> l.add(pl.getName()));
                    yield filter(l, args[1]);
                }
                case "work" -> filter(List.of("set", "list", "remove"), args[1]);
                case "donate" -> filter(List.of("all", "10", "50"), args[1]);
                case "request" -> filter(List.of("service", "1", "16", "32", "64"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("request")) {
            List<String> tiers = plugin.commissions().rewardTiers().stream().map(String::valueOf).toList();
            return filter(tiers, args[2]);
        }
        return List.of();
    }

    static List<String> filter(List<String> options, String prefix) {
        String low = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(low)).toList();
    }
}
