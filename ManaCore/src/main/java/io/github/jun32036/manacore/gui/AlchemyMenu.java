package io.github.jun32036.manacore.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.economy.AlchemyService;

import java.util.ArrayList;
import java.util.List;

import static io.github.jun32036.manacore.gui.Menu.icon;
import static io.github.jun32036.manacore.gui.Menu.tr;

/** 煉金爐（等價交換）的介面 */
public final class AlchemyMenu {

    private final ManaCorePlugin plugin;

    public AlchemyMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player p, int page) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        AlchemyService al = plugin.alchemy();
        if (!al.enabled()) {
            plugin.msg().raw(p, "<gray>煉金爐目前沒有開放。</gray>");
            return;
        }
        Menu m = new Menu(plugin, p, 6, "<dark_purple>煉金爐 · 等價交換", Menu.ALCHEMY);
        m.header(plugin, p, () -> plugin.openCategory(p, Dialogs.TRADE));

        m.set(2, plugin.theme().button(p, "alchemy", Material.AMETHYST_SHARD, 1, "<light_purple>煉金值：<white>" + d.alchemy, List.of(
                "<gray>投入多餘的物品換成煉金值，",
                "<gray>再換出<white>曾經投入過</white>的任何物品",
                "<gray>投入時損耗 " + al.lossPercent() + "%，換出不再損耗",
                "",
                "<dark_gray>煉金值不能換成 mana，也不能贈與",
                "<dark_gray>100 煉金值 ≈ 1 mana 的物資價值")));

        int heldSlot = p.getInventory().getHeldItemSlot();
        ItemStack held = p.getInventory().getItem(heldSlot);
        long hv = held == null ? 0 : al.valueOf(held);
        List<String> lore = new ArrayList<>();
        if (held == null || held.getType().isAir()) {
            lore.add("<gray>先把要投入的物品拿在主手上");
        } else if (hv <= 0) {
            lore.add("<gray>主手：" + tr(held.getType()) + " ×" + held.getAmount());
            lore.add("<red>" + (AlchemyService.isPlain(held) ? "煉金爐不認識這個物品" : "帶附魔／自訂名稱的物品不收"));
        } else {
            lore.add("<gray>主手：<white>" + tr(held.getType()) + " ×" + held.getAmount());
            lore.add("<gray>價值 <white>" + hv + "</white>／個，投入可得 <white>" + al.yieldFor(held.getType(), held.getAmount()));
            lore.add("");
            lore.add("<yellow>點一下：投入這一疊");
            lore.add("<yellow>Shift 點：投入背包裡全部同種物品");
        }
        ItemStack depositIcon = held == null || held.getType().isAir() ? plugin.theme().button(p, "deposit", Material.HOPPER, 1, "<light_purple>投入物品", lore)
                : icon(held.getType(), Math.max(1, held.getAmount()), "<light_purple>投入物品", lore);
        if (hv > 0) Menu.glint(depositIcon);
        m.set(6, depositIcon, e -> {
            ItemStack now = p.getInventory().getItem(heldSlot);
            if (now == null || now.getType().isAir()) { Menu.fail(p); open(p, page); return; }
            long got = e.isShiftClick() ? al.depositAll(p, now.getType()) : al.depositSlot(p, heldSlot);
            if (got <= 0) Menu.fail(p);
            open(p, page);
        });

        List<Pager.Entry> entries = new ArrayList<>();
        for (Material mat : al.withdrawable(d)) {
            long v = plugin.values().value(mat);
            long can = Math.min(mat.getMaxStackSize(), d.alchemy / v);
            List<String> l = new ArrayList<>();
            l.add("<gray>價值 <white>" + v + "</white> 煉金值／個");
            l.add("<gray>你現在能換 <white>" + can + "</white> 個");
            l.add("");
            l.add(can > 0 ? "<yellow>點一下：換 1 個" : "<red>煉金值不夠");
            if (can > 1) l.add("<yellow>Shift 點：換一組（最多 " + can + "）");
            ItemStack ic = icon(mat, 1, "<white>" + tr(mat), l);
            if (can > 0) Menu.glint(ic);
            entries.add(new Pager.Entry(ic, e -> {
                int n = al.withdraw(p, mat, e.isShiftClick() ? mat.getMaxStackSize() : 1);
                if (n > 0) Menu.success(p); else Menu.fail(p);
                open(p, page);
            }));
        }
        Pager.fill(m, entries, page, pg -> open(p, pg), "還沒學會任何物品", "把物品拿在手上，點上面的「投入物品」");
        m.frame();
        m.open(p);
    }
}
