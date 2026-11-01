package io.github.jun32036.botbridge;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /bot 指令：把玩家的要求轉交給 ClaudeBot。
 * 以系統訊息送給機器人（玩家打字無法偽造系統訊息），格式「⟦BOT⟧ 玩家名稱 動作 參數」。
 */
public final class BotBridgePlugin extends JavaPlugin implements TabExecutor {

    static final String MARK = "⟦BOT⟧";

    /** 動作 → 說明（順序即 /bot 說明 的順序） */
    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();
    static {
        ACTIONS.put("說明", "列出 ClaudeBot 會做的事");
        ACTIONS.put("來", "走到你旁邊");
        ACTIONS.put("跟", "跟著你走");
        ACTIONS.put("停", "停下來");
        ACTIONS.put("謝", "用 /thanks 感謝你");
        ACTIONS.put("參觀", "到你附近的作品留言");
        ACTIONS.put("送", "送你 mana，例如 /bot 送 5");
        ACTIONS.put("狀態", "報告自己有多少 mana");
        ACTIONS.put("導師", "邀請你當它的導師");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("bot");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    private String botName() {
        return getConfig().getString("bot-name", "ClaudeBot");
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("這個指令要在遊戲裡使用。");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage("§a── /bot：叫 " + botName() + " 幫忙 ──");
            ACTIONS.forEach((k, v) -> p.sendMessage("§f/bot " + k + " §7" + v));
            return true;
        }
        String action = args[0];
        if (!ACTIONS.containsKey(action)) {
            p.sendMessage("§c" + botName() + " 不會「" + action + "」，輸入 /bot 看看它會做什麼。");
            return true;
        }
        String extra = args.length > 1 ? String.join(" ", List.of(args).subList(1, args.length)) : "";
        if (action.equals("送") && !extra.matches("\\d{1,4}")) {
            p.sendMessage("§c用法：/bot 送 數量（例如 /bot 送 5）");
            return true;
        }
        Player bot = Bukkit.getPlayerExact(botName());
        if (bot == null) {
            p.sendMessage("§7" + botName() + " 現在不在線上。");
            return true;
        }
        if (bot.equals(p)) return true;
        bot.sendMessage(MARK + " " + p.getName() + " " + action + (extra.isEmpty() ? "" : " " + extra));
        p.sendMessage("§7已請 " + botName() + "：" + action + (extra.isEmpty() ? "" : " " + extra));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
        if (args.length == 1) return ACTIONS.keySet().stream().filter(x -> x.startsWith(args[0])).toList();
        if (args.length == 2 && args[0].equals("送")) return List.of("1", "5", "10");
        return List.of();
    }
}
