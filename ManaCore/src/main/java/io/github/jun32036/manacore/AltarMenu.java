package io.github.jun32036.manacore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.gui.Menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生命祭壇：照公定價用 mana 換出物資（一組一組換），分類翻頁；也收少量季節祭品。
 * 祭壇只「換出」，不收購一般物資，避免玩家蓋刷物農場把物資轉成 mana（那是城鎮訂單與社區工程的事）。
 *
 * 設定：altar.shop.<分類>.items.<品項> { material, amount, price }。1.4.0 以前的 altar.items（含 slot）已不再使用。
 */
public final class AltarMenu implements Listener {

    public record Offer(String id, Material material, int amount, long price) {}

    public record Category(String id, String name, Material icon, List<Offer> offers) {}

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_BALANCE = 46;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_TREE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_OFFERING = 53;

    private final ManaCorePlugin plugin;
    private final List<Category> categories = new ArrayList<>();
    private final Map<Material, Long> offerings = new LinkedHashMap<>();
    private boolean warnedLegacy;

    public AltarMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** 全部品項（給其他功能查公定價） */
    public List<Offer> offers() {
        List<Offer> out = new ArrayList<>();
        for (Category c : categories) out.addAll(c.offers());
        return out;
    }

    public List<Category> categories() {
        return categories;
    }

    public void reload() {
        categories.clear();
        ConfigurationSection shop = plugin.section("altar.shop");
        if (shop != null) {
            for (String cid : shop.getKeys(false)) {
                ConfigurationSection cs = shop.getConfigurationSection(cid);
                if (cs == null) continue;
                Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
                List<Offer> offers = new ArrayList<>();
                ConfigurationSection items = cs.getConfigurationSection("items");
                if (items != null) {
                    for (String id : items.getKeys(false)) {
                        ConfigurationSection s = items.getConfigurationSection(id);
                        if (s == null) continue;
                        Material m = Material.matchMaterial(s.getString("material", ""));
                        if (m == null || !m.isItem()) {
                            plugin.getLogger().warning("祭壇品項 " + cid + "." + id + " 的 material 無效，已略過");
                            continue;
                        }
                        offers.add(new Offer(id, m, Math.max(1, s.getInt("amount", 64)), Math.max(0, s.getLong("price", 1))));
                    }
                }
                if (!offers.isEmpty())
                    categories.add(new Category(cid, cs.getString("name", cid), icon == null || !icon.isItem() ? Material.CHEST : icon, offers));
            }
        }
        if (!warnedLegacy && plugin.getConfig().isSet("altar.items")) {
            warnedLegacy = true;
            plugin.getLogger().info("config.yml 的 altar.items 自 1.4.0 起不再使用，祭壇改用內建的 altar.shop 分類；要自訂請把 altar.shop 區塊複製到設定檔。");
        }
        offerings.clear();
        ConfigurationSection off = plugin.getConfig().getConfigurationSection("sources.offering.items");
        if (off != null) {
            for (String k : off.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m != null) offerings.put(m, off.getLong(k));
                else plugin.getLogger().warning("祭品 " + k + " 不是有效的物品 ID，已略過");
            }
        }
    }

    // ---------------- 介面 ----------------

    private static final class Holder implements InventoryHolder {
        Inventory inv;
        Category category; // null = 分類總覽
        int page;
        final Map<Integer, Object> bySlot = new HashMap<>(); // Category 或 Offer

        @Override
        public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) {
            plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
            return;
        }
        if (categories.isEmpty()) {
            plugin.msg().raw(p, "<gray>祭壇目前沒有任何品項。</gray>");
            return;
        }
        if (categories.size() == 1) openCategory(p, d, categories.get(0), 0);
        else openHome(p, d);
    }

    private Inventory create(Holder h) {
        Inventory inv = Bukkit.createInventory(h, 54, Msg.mm(plugin.getConfig().getString("altar.title", "<dark_green>生命祭壇 · 公定價")));
        h.inv = inv;
        return inv;
    }

    private void openHome(Player p, PlayerData d) {
        Holder h = new Holder();
        Inventory inv = create(h);
        int slot = 10;
        for (Category c : categories) {
            if (slot > 34) break;
            if (slot % 9 == 8) slot += 2;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + c.offers().size() + " 種物資");
            int shown = 0;
            for (Offer o : c.offers()) {
                if (shown++ >= 4) { lore.add("<dark_gray>…"); break; }
                lore.add("<dark_gray>・<lang:" + o.material().translationKey() + "> ×" + o.amount() + " <gray>" + o.price() + " mana");
            }
            lore.add("");
            lore.add("<yellow>點一下查看");
            inv.setItem(slot, Menu.icon(c.icon(), 1, "<aqua>" + c.name(), lore));
            h.bySlot.put(slot, c);
            slot++;
        }
        fillInfo(inv, h, d);
        p.openInventory(inv);
    }

    private void openCategory(Player p, PlayerData d, Category c, int page) {
        Holder h = new Holder();
        h.category = c;
        int pages = Math.max(1, (c.offers().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        h.page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = create(h);
        int from = h.page * PAGE_SIZE;
        for (int i = from; i < Math.min(c.offers().size(), from + PAGE_SIZE); i++) {
            Offer o = c.offers().get(i);
            inv.setItem(i - from, offerIcon(o, c, p));
            h.bySlot.put(i - from, o);
        }
        if (categories.size() > 1) inv.setItem(SLOT_BACK, Menu.icon(Material.ARROW, "<white>返回分類"));
        if (h.page > 0) inv.setItem(SLOT_PREV, Menu.icon(Material.PAPER, 1, "<white>上一頁", List.of("<gray>第 " + h.page + "/" + pages + " 頁")));
        if (h.page < pages - 1) inv.setItem(SLOT_NEXT, Menu.icon(Material.PAPER, 1, "<white>下一頁", List.of("<gray>第 " + (h.page + 2) + "/" + pages + " 頁")));
        fillInfo(inv, h, d);
        p.openInventory(inv);
    }

    /** 建材類（石材、木材、混凝土、玻璃光源）享建造等級折扣 */
    private boolean discountable(Category c) {
        return plugin.getConfig().getStringList("altar.discount-categories").contains(c.id());
    }

    private long priceFor(Player p, Offer o, Category c) {
        return discountable(c) ? plugin.passives().discounted(p, o.price()) : o.price();
    }

    private ItemStack offerIcon(Offer o, Category c, Player p) {
        ItemStack it = new ItemStack(o.material(), Math.min(o.amount(), o.material().getMaxStackSize()));
        long price = priceFor(p, o, c);
        it.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            lore.add(plain(c.name(), NamedTextColor.DARK_GRAY));
            if (price < o.price()) lore.add(plain("公定價：" + price + " mana（建造等級折扣，原價 " + o.price() + "）", NamedTextColor.GREEN));
            else lore.add(plain("公定價：" + price + " mana", NamedTextColor.GREEN));
            lore.add(plain("一次換 " + o.amount() + " 個", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(plain("點一下兌換一組，Shift 點兌換五組", NamedTextColor.YELLOW));
            meta.lore(lore);
        });
        return it;
    }

    private void fillInfo(Inventory inv, Holder h, PlayerData d) {
        inv.setItem(SLOT_BALANCE, plugin.icons().mana(1, "<aqua>你的 mana：<white>" + d.balance, List.of("<gray>mana 是拿來用的，不是拿來存的")));

        LifeTree tree = plugin.tree();
        LifeTree.Stage next = tree.nextStage();
        LifeTree.Stage cur = tree.currentStage();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>目前階段：<white>" + (cur == null ? "種子" : cur.name()));
        lore.add("<gray>累積：<white>" + tree.total() + (next == null ? "" : "</white> / " + next.threshold() + "（下一階段：" + next.name() + "）"));
        lore.add("<yellow>捐獻：/mana → 生命之樹");
        inv.setItem(SLOT_TREE, Menu.icon(Material.FLOWERING_AZALEA, 1, "<green>生命之樹", lore));

        if (plugin.getConfig().getBoolean("sources.offering.enabled", true) && !offerings.isEmpty()) {
            ItemStack o = new ItemStack(Material.HONEY_BOTTLE);
            o.editMeta(m -> {
                m.displayName(plain("獻上本季祭品", NamedTextColor.GOLD));
                List<Component> l = new ArrayList<>();
                l.add(plain("本季祭壇需要：", NamedTextColor.GRAY));
                for (Map.Entry<Material, Long> e : offerings.entrySet())
                    l.add(Component.text("・").append(Component.translatable(e.getKey().translationKey()))
                            .append(Component.text(" → " + e.getValue() + " mana")).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                long rem = plugin.mana().remaining(d, Source.OFFERING);
                l.add(plain("今天還能獻上：" + (rem < 0 ? "不限" : rem + " mana"), NamedTextColor.GRAY));
                l.add(plain("點一下，從背包獻上", NamedTextColor.YELLOW));
                m.lore(l);
            });
            inv.setItem(SLOT_OFFERING, o);
        }
    }

    private static Component plain(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        plugin.mana().touch(p);

        if (slot == SLOT_OFFERING) {
            offer(p, d);
            fillInfo(h.inv, h, d);
            return;
        }
        if (h.category != null) {
            if (slot == SLOT_BACK && categories.size() > 1) { click(p); openHome(p, d); return; }
            if (slot == SLOT_PREV && h.page > 0) { click(p); openCategory(p, d, h.category, h.page - 1); return; }
            if (slot == SLOT_NEXT && h.inv.getItem(SLOT_NEXT) != null) { click(p); openCategory(p, d, h.category, h.page + 1); return; }
        }
        Object target = h.bySlot.get(slot);
        if (target instanceof Category c) {
            click(p);
            openCategory(p, d, c, 0);
        } else if (target instanceof Offer o) {
            int lots = e.isShiftClick() ? 5 : 1; // Shift：一次買 5 組
            for (int i = 0; i < lots; i++) if (!buy(p, o, h.category == null ? o.price() : priceFor(p, o, h.category))) break;
            fillInfo(h.inv, h, d);
        }
    }

    private static void click(Player p) {
        p.playSound(p, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
    }

    private boolean buy(Player p, Offer o, long price) {
        if (!plugin.mana().spend(p, price, Source.PURCHASE, o.amount() + "x " + o.material().getKey())) return false;
        int left = o.amount();
        while (left > 0) {
            int n = Math.min(left, o.material().getMaxStackSize());
            left -= n;
            Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(o.material(), n));
            if (!over.isEmpty()) {
                over.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
                plugin.msg().send(p, "inventory-full");
            }
        }
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        plugin.msg().send(p, "bought", "price", price, "item", Component.translatable(o.material().translationKey()), "amount", o.amount());
        plugin.mentors().onExchange(p.getUniqueId());
        return true;
    }

    private void offer(Player p, PlayerData d) {
        if (!plugin.getConfig().getBoolean("sources.offering.enabled", true)) return;
        long rem = plugin.mana().remaining(d, Source.OFFERING);
        if (rem == 0) {
            plugin.msg().send(p, "cap-reached", "reason", Source.OFFERING.display);
            return;
        }
        long total = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.hasItemMeta() && it.getItemMeta().hasDisplayName()) continue; // 改過名的物品不收，避免誤收
            Long value = offerings.get(it.getType());
            if (value == null || value <= 0) continue;
            long budget = rem < 0 ? Long.MAX_VALUE : rem - total;
            if (budget < value) continue;
            int take = (int) Math.min(it.getAmount(), budget / value);
            if (take <= 0) continue;
            total += take * value;
            if (take >= it.getAmount()) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - take);
        }
        if (total == 0) {
            plugin.msg().raw(p, "<gray>背包裡沒有本季祭品（或今天的額度不夠換一個）。</gray>");
            return;
        }
        plugin.mana().earn(p, Source.OFFERING, total, Component.text("獻上祭品"), "祭品", false);
    }
}
