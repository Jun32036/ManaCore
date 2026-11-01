package io.github.jun32036.manacore.gui;

import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * 翻頁清單：放在 6 列選單的內框（第 1～4 列、第 1～7 欄，共 28 格），底列 48／50 是上一頁／下一頁。
 */
public final class Pager {

    public record Entry(ItemStack icon, Consumer<InventoryClickEvent> action) {}

    public static final int PER_PAGE = 28;
    public static final int SLOT_PREV = 48, SLOT_PAGE = 49, SLOT_NEXT = 50;

    private Pager() {}

    /** 6 列選單內框的格子（由左到右、由上到下） */
    public static List<Integer> innerSlots(int rows) {
        List<Integer> out = new ArrayList<>();
        for (int r = 1; r < rows - 1; r++) for (int c = 1; c <= 7; c++) out.add(r * 9 + c);
        return out;
    }

    /** 填入第 page 頁（0 起算）；entries 為空時顯示 emptyText */
    public static void fill(Menu m, List<Entry> entries, int page, IntConsumer openPage, String emptyText, String emptyHint) {
        List<Integer> slots = innerSlots(m.size() / 9);
        int per = slots.size();
        int pages = Math.max(1, (entries.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        int from = page * per;
        for (int i = 0; i < per; i++) {
            int idx = from + i;
            if (idx < entries.size()) {
                Entry e = entries.get(idx);
                m.set(slots.get(i), e.icon(), e.action());
            } else m.set(slots.get(i), Menu.filler(), null);
        }
        if (entries.isEmpty()) {
            m.set(slots.get(per / 2 - 4), Menu.icon(Material.STRUCTURE_VOID, 1, "<gray>" + emptyText, emptyHint == null ? List.of() : List.of("<dark_gray>" + emptyHint)));
        }
        int cur = page;
        if (pages > 1) {
            m.set(SLOT_PAGE, Menu.icon(Material.PAPER, Math.max(1, Math.min(64, page + 1)), "<white>第 " + (page + 1) + " / " + pages + " 頁", List.of("<gray>共 " + entries.size() + " 項")));
            if (page > 0) m.set(SLOT_PREV, Menu.icon(Material.SPECTRAL_ARROW, "<white>上一頁"), e -> openPage.accept(cur - 1));
            else m.set(SLOT_PREV, Menu.filler(), null);
            if (page < pages - 1) m.set(SLOT_NEXT, Menu.icon(Material.SPECTRAL_ARROW, "<white>下一頁"), e -> openPage.accept(cur + 1));
            else m.set(SLOT_NEXT, Menu.filler(), null);
        }
    }
}
