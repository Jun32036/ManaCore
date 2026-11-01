package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import io.github.jun32036.manacore.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 一個箱子介面：每格可以綁一個點擊動作。所有點擊都會被取消，玩家拿不走圖示。 */
public final class Menu implements InventoryHolder {

    private final Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    public Menu(int rows, String titleMiniMessage) {
        this.inv = Bukkit.createInventory(this, rows * 9, Msg.mm(titleMiniMessage));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public int size() {
        return inv.getSize();
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inv.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
        return this;
    }

    public Menu set(int slot, ItemStack item) {
        return set(slot, item, null);
    }

    /** 外框填滿玻璃片 */
    public Menu border() {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        int rows = inv.getSize() / 9;
        for (int i = 0; i < inv.getSize(); i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) {
                if (inv.getItem(i) == null) inv.setItem(i, pane);
            }
        }
        return this;
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    /** 這個選單是否仍是玩家目前開著的那一個（非同步資料回來時用） */
    public boolean isOpenFor(Player p) {
        return p.isOnline() && p.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    // ---------------- 圖示 ----------------

    public static ItemStack icon(Material m, String name, String... lore) {
        return icon(m, 1, name, List.of(lore));
    }

    public static ItemStack icon(Material m, int amount, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        it.editMeta(meta -> {
            meta.displayName(noItalic(Msg.mm(name)));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(noItalic(Msg.mm(s)));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack it = icon(Material.PLAYER_HEAD, 1, name, lore);
        it.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(owner));
        return it;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ---------------- 事件 ----------------

    public static final class Listen implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            Consumer<InventoryClickEvent> a = menu.actions.get(e.getRawSlot());
            if (a != null) a.accept(e);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder(false) instanceof Menu) e.setCancelled(true);
        }
    }
}
