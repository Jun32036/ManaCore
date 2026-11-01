package io.github.jun32036.manacore.command;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;

/** /thanks <玩家> [原因]：感謝不花自己的 mana，由世界替你回報對方。 */
public final class ThanksCommand implements TabExecutor {

    private final ManaCorePlugin plugin;

    public ThanksCommand(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) return true;
        if (args.length < 1) {
            if (plugin.dialogs().enabled()) plugin.dialogs().openThanks(p); else plugin.menu().openThanks(p);
            return true;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) {
            plugin.msg().send(p, "no-player", "name", args[0]);
            return true;
        }
        String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
        plugin.actions().thank(p, t, reason);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? null : List.of();
    }
}
