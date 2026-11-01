package xyz.teacafe.manacore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生命祭壇：照公定價用 mana 換出物資；也收少量季節祭品。
 * 祭壇只「換出」，不收購一般物資，避免玩家蓋刷物農場把物資轉成 mana。
 */
public final class AltarMenu implements Listener {

    public record Offer(String id, Material material, int amount, long price, int slot, String category) {}

    private static final int SLOT_BALANCE = 45;
    private static final int SLOT_TREE = 49;
    private static final int SLOT_OFFERING = 53;

    private final ManaCorePlugin plugin;
    private final Map<Integer, Offer> bySlot = new HashMap<>();
    private final Map<Material, Long> offerings = new LinkedHashMap<>();

    public AltarMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    /** 給 /mana price 或其他插件查公定價 */
    public List<Offer> offers() {
        return new ArrayList<>(bySlot.values());
    }

    public void reload() {
        bySlot.clear();
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("altar.items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection s = items.getConfigurationSection(id);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("material", ""));
                int slot = s.getInt("slot", -1);
                if (m == null || !m.isItem() || slot < 0 || slot > 44) {
                    plugin.getLogger().warning("祭壇品項 " + id + " 設定有誤（material 或 slot），已略過");
                    continue;
                }
                bySlot.put(slot, new Offer(id, m, Math.max(1, s.getInt("amount", 1)), Math.max(0, s.getLong("price", 1)), slot, s.getString("category", "")));
            }
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
        @Override
        public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) {
            plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
            return;
        }
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, 54, Msg.mm(plugin.getConfig().getString("altar.title", "生命祭壇")));
        h.inv = inv;
        for (Offer o : bySlot.values()) inv.setItem(o.slot(), offerIcon(o));
        fillInfo(inv, d);
        p.openInventory(inv);
    }

    private ItemStack offerIcon(Offer o) {
        ItemStack it = new ItemStack(o.material(), Math.min(o.amount(), o.material().getMaxStackSize()));
        it.editMeta(meta -> {
            List<Component> lore = new ArrayList<>();
            if (!o.category().isEmpty()) lore.add(plain(o.category(), NamedTextColor.DARK_GRAY));
            lore.add(plain("公定價：" + o.price() + " mana", NamedTextColor.GREEN));
            lore.add(plain("一次換 " + o.amount() + " 個", NamedTextColor.GRAY));
            lore.add(Component.empty());
            lore.add(plain("點一下兌換", NamedTextColor.YELLOW));
            meta.lore(lore);
        });
        return it;
    }

    private void fillInfo(Inventory inv, PlayerData d) {
        ItemStack bal = new ItemStack(Material.AMETHYST_SHARD);
        bal.editMeta(m -> {
            m.displayName(plain("你的 mana：" + d.balance, NamedTextColor.LIGHT_PURPLE));
            m.lore(List.of(plain("mana 是拿來用的，不是拿來存的", NamedTextColor.GRAY)));
        });
        inv.setItem(SLOT_BALANCE, bal);

        LifeTree tree = plugin.tree();
        ItemStack t = new ItemStack(Material.FLOWERING_AZALEA);
        LifeTree.Stage next = tree.nextStage();
        LifeTree.Stage cur = tree.currentStage();
        t.editMeta(m -> {
            m.displayName(plain("生命之樹", NamedTextColor.GREEN));
            List<Component> lore = new ArrayList<>();
            lore.add(plain("目前階段：" + (cur == null ? "種子" : cur.name()), NamedTextColor.GRAY));
            lore.add(plain("累積：" + tree.total() + (next == null ? "" : " / " + next.threshold() + "（下一階段：" + next.name() + "）"), NamedTextColor.GRAY));
            lore.add(plain("捐獻：/mana donate <數量>", NamedTextColor.YELLOW));
            m.lore(lore);
        });
        inv.setItem(SLOT_TREE, t);

        if (plugin.getConfig().getBoolean("sources.offering.enabled", true) && !offerings.isEmpty()) {
            ItemStack o = new ItemStack(Material.HONEY_BOTTLE);
            o.editMeta(m -> {
                m.displayName(plain("獻上本季祭品", NamedTextColor.GOLD));
                List<Component> lore = new ArrayList<>();
                lore.add(plain("本季祭壇需要：", NamedTextColor.GRAY));
                for (Map.Entry<Material, Long> e : offerings.entrySet())
                    lore.add(Component.text("・").append(Component.translatable(e.getKey().translationKey()))
                            .append(Component.text(" → " + e.getValue() + " mana")).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                long rem = plugin.mana().remaining(d, Source.OFFERING);
                lore.add(plain("今天還能獻上：" + (rem < 0 ? "不限" : rem + " mana"), NamedTextColor.GRAY));
                lore.add(plain("點一下，從背包獻上", NamedTextColor.YELLOW));
                m.lore(lore);
            });
            inv.setItem(SLOT_OFFERING, o);
        }
    }

    private static Component plain(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        PlayerData d = plugin.mana().get(p);
        if (d == null) return;
        plugin.mana().touch(p);
        if (slot == SLOT_OFFERING) {
            offer(p, d);
        } else {
            Offer o = bySlot.get(slot);
            if (o == null) return;
            buy(p, o);
        }
        fillInfo(e.getView().getTopInventory(), d);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder(false) instanceof Holder) e.setCancelled(true);
    }

    private void buy(Player p, Offer o) {
        if (!plugin.mana().spend(p, o.price(), Source.PURCHASE, o.amount() + "x " + o.material().getKey())) return;
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
        plugin.msg().send(p, "bought", "price", o.price(), "item", Component.translatable(o.material().translationKey()), "amount", o.amount());
        plugin.mentors().onExchange(p.getUniqueId());
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
