package xyz.teacafe.manacore.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import xyz.teacafe.manacore.ManaCorePlugin;
import xyz.teacafe.manacore.Msg;
import xyz.teacafe.manacore.Places;
import xyz.teacafe.manacore.PlayerData;
import xyz.teacafe.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** /manaadmin 管理指令 */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "give", "take", "event", "check", "altar", "landmark", "tree", "decay", "orders", "project", "reload");

    private final ManaCorePlugin plugin;

    public AdminCommand(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String[] a = args.length <= 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "give", "take" -> adjust(s, a, sub.equals("take"));
            case "event" -> event(s, a);
            case "check" -> check(s, a);
            case "altar" -> altar(s, a);
            case "landmark" -> landmark(s, a);
            case "tree" -> tree(s, a);
            case "orders" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reroll")) {
                    plugin.orders().loadToday(true);
                    s.sendMessage(Msg.mm("<green>已重新抽選今天的城鎮訂單（今天的交貨進度已清除）。</green>"));
                } else {
                    s.sendMessage(Msg.mm("<green>── 今天的城鎮訂單 ──</green>"));
                    for (var o : plugin.orders().today())
                        s.sendMessage(Msg.mm("<white><i>.</white> <id> <gray><n>/<b> 份</gray>", "i", o.idx + 1, "id", o.def.id(), "n", o.delivered, "b", o.def.bundles()));
                    s.sendMessage(Msg.mm("<gray>/manaadmin orders reroll 重新抽選</gray>"));
                }
            }
            case "project" -> project(s, a);
            case "decay" -> {
                plugin.mana().forceDecay();
                s.sendMessage(Msg.mm("<green>已手動執行一次歸土（不影響每日自動歸土）。</green>"));
            }
            case "reload" -> {
                plugin.reloadAll();
                s.sendMessage(Msg.mm("<green>ManaCore 設定已重新載入。</green>"));
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        String[] lines = {
                "<green>── ManaCore 管理 ──</green>",
                "<white>give|take <玩家> <數量> [原因]</white> <gray>調整 mana（不受上限，可離線）</gray>",
                "<white>event <玩家|*> <數量> <活動名稱></white> <gray>發放活動 mana（* = 所有非 AFK 在線玩家）</gray>",
                "<white>check <玩家></white> <gray>查看餘額</gray>",
                "<white>altar set|remove</white> <gray>把準心對著的方塊設為 / 取消生命祭壇</gray>",
                "<white>landmark add <名稱> <半徑> | remove <名稱> | list</white> <gray>探索地標</gray>",
                "<white>tree [set <數值>]</white> <gray>生命之樹</gray>",
                "<white>decay</white> <gray>立刻執行一次歸土（測試用）</gray>",
                "<white>orders [reroll]</white> <gray>查看 / 重新抽選今天的城鎮訂單</gray>",
                "<white>project list | reset <id></white> <gray>社區工程（內容在 config.yml 的 projects）</gray>",
                "<white>reload</white> <gray>重新讀取 config.yml</gray>"
        };
        for (String l : lines) s.sendMessage(Msg.mm(l));
    }

    private void adjust(CommandSender s, String[] a, boolean take) {
        if (a.length < 2) {
            s.sendMessage(Msg.mm("<gray>用法：/manaadmin " + (take ? "take" : "give") + " <玩家> <數量> [原因]</gray>"));
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
        long amount = parse(a[1]);
        if (t == null || amount <= 0) {
            s.sendMessage(Msg.mm("<red>找不到玩家或數量錯誤。</red>"));
            return;
        }
        String reason = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
        long delta = take ? -amount : amount;
        plugin.mana().adjust(t.getUniqueId(), delta, Source.ADMIN, s.getName() + (reason.isEmpty() ? "" : "：" + reason));
        s.sendMessage(Msg.mm("<green>已將 <n> 的 mana <d>。</green>", "n", String.valueOf(t.getName()), "d", (take ? "-" : "+") + amount));
        Player op = t.getPlayer();
        if (op != null && !take) plugin.msg().send(op, "earned", "amount", amount, "reason", reason.isEmpty() ? "管理員發放" : reason);
    }

    private void event(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(Msg.mm("<gray>用法：/manaadmin event <玩家|*> <數量> <活動名稱></gray>"));
            return;
        }
        long amount = parse(a[1]);
        if (amount <= 0) {
            s.sendMessage(Msg.mm("<red>數量錯誤。</red>"));
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
        List<Player> targets = new ArrayList<>();
        if (a[0].equals("*")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                PlayerData d = plugin.mana().get(p);
                if (d != null && !plugin.mana().isAfk(d)) targets.add(p);
            }
        } else {
            Player p = Bukkit.getPlayerExact(a[0]);
            if (p != null) targets.add(p);
        }
        int n = 0;
        for (Player p : targets) if (plugin.mana().earn(p, Source.EVENT, amount, Component.text("參加「" + name + "」"), name, false) > 0) n++;
        s.sendMessage(Msg.mm("<green>活動「<e>」：<n> 位玩家獲得 mana。</green>", "e", name, "n", n));
    }

    private void check(CommandSender s, String[] a) {
        if (a.length < 1) return;
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) {
            s.sendMessage(Msg.mm("<red>找不到玩家。</red>"));
            return;
        }
        PlayerData d = plugin.mana().get(t.getUniqueId());
        if (d != null) {
            s.sendMessage(Msg.mm("<green><n>：<b> mana（在線）</green>", "n", d.name, "b", d.balance));
            return;
        }
        String id = t.getUniqueId().toString();
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        }).thenAccept(b -> plugin.sync(() -> s.sendMessage(Msg.mm(b == null ? "<gray><n> 還沒有帳戶。</gray>" : "<green><n>：<b> mana（離線）</green>",
                "n", String.valueOf(t.getName()), "b", String.valueOf(b)))));
    }

    private void altar(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("請在遊戲內使用");
            return;
        }
        Block b = p.getTargetBlockExact(6);
        if (b == null || b.getType().isAir()) {
            p.sendMessage(Msg.mm("<red>請把準心對著 6 格內的一個方塊。</red>"));
            return;
        }
        boolean remove = a.length > 0 && a[0].equalsIgnoreCase("remove");
        boolean changed = plugin.places().setAltar(b, !remove);
        p.sendMessage(Msg.mm(changed
                ? (remove ? "<green>已取消這個生命祭壇。</green>" : "<green>已把這個方塊設為生命祭壇，玩家右鍵即可兌換。</green>")
                : "<gray>沒有變動。</gray>"));
    }

    private void landmark(CommandSender s, String[] a) {
        String op = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (op) {
            case "add" -> {
                if (!(s instanceof Player p) || a.length < 3) {
                    s.sendMessage(Msg.mm("<gray>用法（遊戲內）：/manaadmin landmark add <名稱> <半徑></gray>"));
                    return;
                }
                long r = parse(a[2]);
                if (r <= 0) {
                    s.sendMessage(Msg.mm("<red>半徑錯誤。</red>"));
                    return;
                }
                Location l = p.getLocation();
                plugin.places().putLandmark(new Places.Landmark(a[1], l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), r));
                s.sendMessage(Msg.mm("<green>已建立地標「<n>」，半徑 <r> 格。</green>", "n", a[1], "r", r));
            }
            case "remove" -> {
                if (a.length < 2) return;
                s.sendMessage(Msg.mm(plugin.places().removeLandmark(a[1]) ? "<green>已移除。</green>" : "<gray>沒有這個地標。</gray>"));
            }
            default -> {
                s.sendMessage(Msg.mm("<green>── 地標 ──</green>"));
                for (Places.Landmark l : plugin.places().landmarks())
                    s.sendMessage(Msg.mm("<white><n></white> <gray><w> <x>, <y>, <z> 半徑 <r></gray>",
                            "n", l.name(), "w", l.world(), "x", (int) l.x(), "y", (int) l.y(), "z", (int) l.z(), "r", (int) l.radius()));
            }
        }
    }

    private void tree(CommandSender s, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("set")) {
            long v = parse(a[1]);
            if (v < 0) return;
            plugin.tree().set(v);
        }
        var t = plugin.tree();
        s.sendMessage(Msg.mm("<green>生命之樹：<total>，已解鎖 <r>/<all> 階段</green>", "total", t.total(), "r", t.reached(), "all", t.stages().size()));
    }

    private void project(CommandSender s, String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("reset")) {
            s.sendMessage(Msg.mm(plugin.projects().reset(a[1]) ? "<green>已重置工程 <id> 的進度。</green>" : "<red>沒有這項工程：<id></red>", "id", a[1]));
            return;
        }
        s.sendMessage(Msg.mm("<green>── 社區工程 ──</green>"));
        for (var pr : plugin.projects().all())
            s.sendMessage(Msg.mm("<white><id></white> <gray><n> <p>%<d></gray>", "id", pr.id, "n", pr.name,
                    "p", Math.round(pr.progress() * 100), "d", pr.done ? " 已完工" : ""));
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return ManaCommand.filter(SUBS, args[0]);
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "give", "take", "check" -> null;
                case "event" -> {
                    List<String> l = new ArrayList<>(List.of("*"));
                    Bukkit.getOnlinePlayers().forEach(p -> l.add(p.getName()));
                    yield ManaCommand.filter(l, args[1]);
                }
                case "altar" -> ManaCommand.filter(List.of("set", "remove"), args[1]);
                case "landmark" -> ManaCommand.filter(List.of("add", "remove", "list"), args[1]);
                case "tree" -> ManaCommand.filter(List.of("set"), args[1]);
                case "orders" -> ManaCommand.filter(List.of("reroll"), args[1]);
                case "project" -> ManaCommand.filter(List.of("list", "reset"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("project") && args[1].equalsIgnoreCase("reset"))
            return ManaCommand.filter(plugin.projects().all().stream().map(pr -> pr.id).toList(), args[2]);
        if (args.length == 3 && sub.equals("landmark") && args[1].equalsIgnoreCase("remove"))
            return ManaCommand.filter(plugin.places().landmarks().stream().map(Places.Landmark::name).toList(), args[2]);
        return List.of();
    }
}
