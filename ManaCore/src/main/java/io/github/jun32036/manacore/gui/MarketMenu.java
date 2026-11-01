package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.economy.AlchemyService;
import io.github.jun32036.manacore.economy.MarketService;

import java.util.ArrayList;
import java.util.List;

import static io.github.jun32036.manacore.gui.Menu.icon;
import static io.github.jun32036.manacore.gui.Menu.tr;

/** 玩家市集（公定價）的介面 */
public final class MarketMenu {

    private final ManaCorePlugin plugin;

    public MarketMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    public void open(Player p, int page) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        MarketService ms = plugin.market();
        if (!ms.enabled()) {
            plugin.msg().raw(p, "<gray>市集目前沒有開放。</gray>");
            return;
        }
        Menu m = new Menu(6, "<dark_aqua>玩家市集 · 公定價");
        m.header(plugin, p, () -> plugin.menu().openMain(p));

        m.set(2, icon(Material.KNOWLEDGE_BOOK, 1, "<green>市集怎麼用？", List.of(
                "<gray>把多餘的東西掛出來賣給其他玩家",
                "<gray>價格由全服價值表決定，<white>不講價、不比價",
                "<gray>賣出時手續費 " + Math.round(ms.feeRate() * 100) + "% 流入生命之樹",
                "<gray>掛 " + plugin.getConfig().getLong("market.expire-days", 7) + " 天沒賣出會退回",
                "",
                "<dark_gray>帶附魔／自訂名稱的物品沒有公定價，",
                "<dark_gray>請用委託板或贈與")));

        int heldSlot = p.getInventory().getHeldItemSlot();
        ItemStack held = p.getInventory().getItem(heldSlot);
        long price = held == null || !AlchemyService.isPlain(held) ? 0 : ms.price(held.getType(), held.getAmount());
        List<String> lore = new ArrayList<>();
        if (held == null || held.getType().isAir()) lore.add("<gray>先把要賣的物品拿在主手上");
        else if (price <= 0) { lore.add("<gray>主手：" + tr(held.getType()) + " ×" + held.getAmount()); lore.add("<red>這個物品沒有公定價"); }
        else {
            lore.add("<gray>主手：<white>" + tr(held.getType()) + " ×" + held.getAmount());
            lore.add("<gray>公定價 <white>" + price + "</white> mana，賣出實收 <white>" + (price - ms.fee(price)));
            lore.add("");
            lore.add("<yellow>點一下：整疊上架");
        }
        ItemStack sellIcon = held == null || held.getType().isAir() ? icon(Material.CHEST, 1, "<aqua>上架主手物品", lore)
                : icon(held.getType(), Math.max(1, held.getAmount()), "<aqua>上架主手物品", lore);
        if (price > 0) Menu.glint(sellIcon);
        m.set(6, sellIcon, e -> { if (ms.list(p, heldSlot)) Menu.success(p); else Menu.fail(p); open(p, page); });

        int returned = ms.returnedCount(p.getUniqueId());
        int mineN = ms.mine(p.getUniqueId()).size();
        ItemStack mineIcon = icon(Material.ENDER_CHEST, Math.max(1, Math.min(64, mineN)), "<gold>我的攤位",
                List.of("<gray>掛著的：<white>" + mineN, returned > 0 ? "<yellow>有 " + returned + " 批退回的物品待領取！" : "<gray>沒有待領取的物品", "", "<yellow>點一下查看"));
        if (returned > 0) Menu.glint(mineIcon);
        m.set(5, mineIcon, e -> { Menu.click(p); openMine(p, 0); });

        List<Pager.Entry> entries = new ArrayList<>();
        for (MarketService.Lot l : ms.open()) {
            boolean own = l.seller.equals(p.getUniqueId());
            boolean can = !own && d.balance >= l.price;
            List<String> ll = new ArrayList<>();
            ll.add("<gray>賣家：<white>" + esc(l.sellerName));
            ll.add("<gray>價格：<aqua>" + l.price + " mana</aqua> <dark_gray>（整批 " + l.amount + " 個）");
            ll.add("");
            ll.add(own ? "<gray>你的攤位（到「我的攤位」收回）" : can ? "<yellow>點一下購買" : "<red>mana 不夠");
            ItemStack ic = icon(l.material, Math.min(l.amount, l.material.getMaxStackSize()), (own ? "<gray>" : "<white>") + tr(l.material) + " ×" + l.amount, ll);
            if (can) Menu.glint(ic);
            entries.add(new Pager.Entry(ic, e -> {
                if (own) return;
                if (ms.buy(p, l)) Menu.success(p); else Menu.fail(p);
                open(p, page);
            }));
        }
        Pager.fill(m, entries, page, pg -> open(p, pg), "市集上還沒有東西", "把物品拿在手上，點上面的「上架」");
        m.frame(Menu.LIFE);
        m.open(p);
    }

    public void openMine(Player p, int page) {
        MarketService ms = plugin.market();
        Menu m = new Menu(6, "<gold>我的攤位");
        m.header(plugin, p, () -> open(p, 0));
        List<Pager.Entry> entries = new ArrayList<>();
        for (MarketService.Lot l : ms.mine(p.getUniqueId())) {
            boolean returned = "returned".equals(l.status);
            List<String> ll = new ArrayList<>();
            ll.add("<gray>公定價：<aqua>" + l.price + " mana");
            ll.add(returned ? "<yellow>已過期退回，等你領取" : "<gray>掛著等人購買");
            ll.add("");
            ll.add(returned ? "<yellow>點一下：領回" : "<red>Shift + 右鍵：收回");
            ItemStack ic = icon(l.material, Math.min(l.amount, l.material.getMaxStackSize()), "<white>" + tr(l.material) + " ×" + l.amount, ll);
            if (returned) Menu.glint(ic);
            entries.add(new Pager.Entry(ic, e -> {
                if (returned || e.getClick() == ClickType.SHIFT_RIGHT) {
                    if (ms.remove(p, l)) Menu.success(p); else Menu.fail(p);
                    openMine(p, page);
                }
            }));
        }
        Pager.fill(m, entries, page, pg -> openMine(p, pg), "你沒有掛任何東西", null);
        m.frame(Menu.LIFE);
        m.open(p);
    }
}
