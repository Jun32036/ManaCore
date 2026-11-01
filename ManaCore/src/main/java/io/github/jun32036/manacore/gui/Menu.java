package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
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
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.Msg;
import io.github.jun32036.manacore.PlayerData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一個箱子介面：每格可以綁一個點擊動作。所有點擊都會被取消，玩家拿不走圖示。
 *
 * 1.5.0 起所有選單共用同一套版面：
 * - 第 0 列是標題列：左上「返回」、正中「你的 mana」、右上「關閉」
 * - 邊框顏色代表分區：綠＝靠自己的雙手、粉＝和大家一起、藍＝生生不息（mana 去處）、紫＝煉金、灰＝資訊
 * - 清單類頁面用 {@link Pager} 翻頁
 */
public final class Menu implements InventoryHolder {

    public static final Material SELF = Material.LIME_STAINED_GLASS_PANE;
    public static final Material SOCIAL = Material.PINK_STAINED_GLASS_PANE;
    public static final Material LIFE = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Material ALCHEMY = Material.PURPLE_STAINED_GLASS_PANE;
    public static final Material INFO = Material.GRAY_STAINED_GLASS_PANE;
    /** 不能點的空格一律用深色玻璃擋住 */
    public static final Material FILLER = Material.BLACK_STAINED_GLASS_PANE;

    public static ItemStack filler() {
        return icon(FILLER, " ");
    }

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

    /**
     * 把所有還是空的格子擋起來：標題列用分區顏色的玻璃片，其餘一律深色玻璃。
     * 先放內容再呼叫。
     */
    public Menu frame(Material accent) {
        ItemStack a = icon(accent, " "), f = filler();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) != null) continue;
            inv.setItem(i, i < 9 ? a : f);
        }
        return this;
    }

    public Menu border() {
        return frame(INFO);
    }

    /** 標題列：返回（可省略）、你的 mana、關閉 */
    public Menu header(ManaCorePlugin plugin, Player p, Runnable back) {
        if (back != null) set(0, icon(Material.ARROW, "<white>返回", "<gray>回上一頁"), e -> { click(p); back.run(); });
        PlayerData d = plugin.mana().get(p);
        if (d != null) set(4, plugin.icons().mana(1, "<aqua>你的 mana：<white>" + d.balance, List.of("<gray>mana 是拿來用的，不是拿來存的")));
        set(8, icon(Material.BARRIER, "<red>關閉"), e -> p.closeInventory());
        return this;
    }

    /** 分區標籤：有名字的玻璃片 */
    public Menu label(int slot, Material pane, String text) {
        return set(slot, icon(pane, text));
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    /** 這個選單是否仍是玩家目前開著的那一個（非同步資料回來時用） */
    public boolean isOpenFor(Player p) {
        return p.isOnline() && p.getOpenInventory().getTopInventory().getHolder(false) == this;
    }

    // ---------------- 音效 ----------------

    public static void click(Player p) {
        p.playSound(p, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    public static void success(Player p) {
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.3f);
    }

    public static void fail(Player p) {
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.7f);
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

    /** 加上附魔光澤（用來標示「現在就能做」的項目） */
    public static ItemStack glint(ItemStack it) {
        it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
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

    /** 物品的可翻譯名稱（放在 MiniMessage 裡） */
    public static String tr(Material m) {
        return "<lang:" + m.translationKey() + ">";
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
