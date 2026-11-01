package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家市集：玩家把手上的物品掛到全服市集，價格由價值表決定、不能自訂（不講價、不比價）。
 * 買家付 mana 給賣家，另收手續費流入生命之樹。市集是玩家之間的 mana 轉移，不會產生 mana。
 * 掛太久沒賣掉的會下架退回（賣家離線時先存著，上線後到「我的攤位」領取）。
 */
public final class MarketService {

    public static final class Lot {
        public long id;
        public long created;
        public UUID seller;
        public String sellerName;
        public Material material;
        public int amount;
        public long price;
        public String status; // open / returned（待領取）/ sold / removed
    }

    private final ManaCorePlugin plugin;
    private final Map<Long, Lot> live = new LinkedHashMap<>();

    public MarketService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("market.enabled", true) && plugin.values().enabled();
    }

    public int maxLots() {
        return plugin.getConfig().getInt("market.max-lots-per-player", 10);
    }

    public double feeRate() {
        return plugin.getConfig().getDouble("market.fee-rate", 0.1);
    }

    public long fee(long price) {
        return EconMath.fee(price, feeRate(), plugin.getConfig().getLong("market.min-fee", 1));
    }

    /** 一批物品的價格（mana）：價值 × 數量 ÷ 100，無條件進位，至少 1 */
    public long price(Material m, int amount) {
        long v = plugin.values().value(m);
        if (v <= 0) return 0;
        return Math.max(1, (long) Math.ceil(v * amount / 100.0));
    }

    public void load() throws Exception {
        plugin.db().query(c -> {
            List<Lot> out = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, created, seller, seller_name, material, amount, price, status FROM market WHERE status IN ('open','returned')")) {
                while (rs.next()) {
                    Lot l = new Lot();
                    l.id = rs.getLong(1);
                    l.created = rs.getLong(2);
                    l.seller = UUID.fromString(rs.getString(3));
                    l.sellerName = rs.getString(4);
                    l.material = Material.matchMaterial(rs.getString(5));
                    l.amount = rs.getInt(6);
                    l.price = rs.getLong(7);
                    l.status = rs.getString(8);
                    if (l.material != null) out.add(l);
                }
            }
            return out;
        }).get().forEach(l -> live.put(l.id, l));
    }

    public List<Lot> open() {
        List<Lot> out = new ArrayList<>();
        for (Lot l : live.values()) if ("open".equals(l.status)) out.add(l);
        out.sort(Comparator.comparing((Lot l) -> l.material.name()).thenComparingLong(l -> l.created));
        return out;
    }

    public List<Lot> mine(UUID seller) {
        List<Lot> out = new ArrayList<>();
        for (Lot l : live.values()) if (seller.equals(l.seller)) out.add(l);
        out.sort(Comparator.comparingLong((Lot l) -> l.created).reversed());
        return out;
    }

    public int returnedCount(UUID seller) {
        int n = 0;
        for (Lot l : live.values()) if (seller.equals(l.seller) && "returned".equals(l.status)) n++;
        return n;
    }

    // ---------------- 上架 ----------------

    /** 把背包某一格整格上架；回傳是否成功（失敗已告知原因） */
    public boolean list(Player p, int slot) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) return false;
        if (!enabled()) {
            plugin.msg().raw(p, "<gray>市集目前沒有開放。</gray>");
            return false;
        }
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) {
            plugin.msg().raw(p, "<gray>請先把要賣的物品拿在主手上。</gray>");
            return false;
        }
        if (!AlchemyService.isPlain(it)) {
            plugin.msg().raw(p, "<gray>帶有附魔、自訂名稱或說明的物品沒有固定價格，不能上架（可以用委託板或贈與）。</gray>");
            return false;
        }
        long price = price(it.getType(), it.getAmount());
        if (price <= 0) {
            plugin.msg().raw(p, "<gray><i> 沒有固定價格，不能上架。</gray>", "i", Component.translatable(it.getType().translationKey()));
            return false;
        }
        long mineOpen = live.values().stream().filter(l -> p.getUniqueId().equals(l.seller) && "open".equals(l.status)).count();
        if (mineOpen >= maxLots()) {
            plugin.msg().raw(p, "<gray>你最多同時掛 <n> 批物品。</gray>", "n", maxLots());
            return false;
        }
        ItemStack stack = it.clone();
        p.getInventory().setItem(slot, null);
        Lot l = new Lot();
        l.created = System.currentTimeMillis();
        l.seller = p.getUniqueId();
        l.sellerName = p.getName();
        l.material = stack.getType();
        l.amount = stack.getAmount();
        l.price = price;
        l.status = "open";
        byte[] blob = stack.serializeAsBytes();
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO market(created, seller, seller_name, material, amount, price, status, items) VALUES(?,?,?,?,?,?,'open',?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, l.created);
                ps.setString(2, l.seller.toString());
                ps.setString(3, l.sellerName);
                ps.setString(4, l.material.getKey().toString());
                ps.setInt(5, l.amount);
                ps.setLong(6, l.price);
                ps.setBytes(7, blob);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1L; }
            }
        }).whenComplete((id, err) -> plugin.sync(() -> {
            if (err != null || id == null || id < 0) {
                give(p, stack);
                plugin.msg().raw(p, "<red>上架失敗，物品已退回。</red>");
                return;
            }
            l.id = id;
            live.put(id, l);
            plugin.msg().raw(p, "<green>已上架 <i> ×<n>，價格 <p> mana（賣出時手續費 <f>）。</green>",
                    "i", Component.translatable(l.material.translationKey()), "n", l.amount, "p", l.price, "f", fee(l.price));
        }));
        p.playSound(p, Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1.1f);
        return true;
    }

    // ---------------- 購買 ----------------

    public boolean buy(Player p, Lot l) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !"open".equals(l.status) || !live.containsKey(l.id)) return false;
        if (p.getUniqueId().equals(l.seller)) {
            plugin.msg().raw(p, "<gray>這是你自己的攤位，要收回請到「我的攤位」。</gray>");
            return false;
        }
        if (!plugin.mana().spend(p, l.price, Source.MARKET_BUY, l.amount + "x " + l.material.getKey().getKey() + " 向 " + l.sellerName)) return false;
        l.status = "sold";
        live.remove(l.id);
        long fee = fee(l.price), net = l.price - fee;
        plugin.mana().adjust(l.seller, net, Source.MARKET_SELL, l.amount + "x " + l.material.getKey().getKey() + " 賣給 " + p.getName());
        if (fee > 0) plugin.tree().add(fee);
        takeItems(l, items -> {
            for (ItemStack it : items) give(p, it);
        }, true);
        plugin.msg().raw(p, "<green>用 <p> mana 向 <s> 買到 <i> ×<n>。</green>", "p", l.price, "s", l.sellerName, "i", Component.translatable(l.material.translationKey()), "n", l.amount);
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) plugin.msg().raw(seller, "<green><b> 買了你的 <i> ×<n>，收到 <m> mana（手續費 <f> 流入生命之樹）。</green>",
                "b", p.getName(), "i", Component.translatable(l.material.translationKey()), "n", l.amount, "m", net, "f", fee);
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        return true;
    }

    // ---------------- 下架 / 領回 ----------------

    /** 賣家收回：在線直接給；成功回傳 true */
    public boolean remove(Player p, Lot l) {
        if (!p.getUniqueId().equals(l.seller) || !live.containsKey(l.id) || !("open".equals(l.status) || "returned".equals(l.status))) return false;
        live.remove(l.id);
        l.status = "removed";
        takeItems(l, items -> {
            for (ItemStack it : items) give(p, it);
            plugin.msg().raw(p, "<green>已收回 <i> ×<n>。</green>", "i", Component.translatable(l.material.translationKey()), "n", l.amount);
        }, true);
        return true;
    }

    /** 超過 expire-days 沒賣掉的下架；賣家在線就直接退，不在線標為待領取 */
    public void expireOld() {
        long days = plugin.getConfig().getLong("market.expire-days", 7);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        for (Lot l : new ArrayList<>(live.values())) {
            if (!"open".equals(l.status) || l.created > cutoff) continue;
            Player seller = Bukkit.getPlayer(l.seller);
            if (seller != null) {
                live.remove(l.id);
                l.status = "removed";
                takeItems(l, items -> {
                    for (ItemStack it : items) give(seller, it);
                    plugin.msg().raw(seller, "<gray>你掛在市集的 <i> ×<n> 超過 <d> 天沒賣出，已退回給你。</gray>", "i", Component.translatable(l.material.translationKey()), "n", l.amount, "d", days);
                }, true);
            } else {
                l.status = "returned";
                long id = l.id;
                plugin.db().run(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE market SET status='returned' WHERE id=?")) {
                        ps.setLong(1, id);
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
        }
    }

    /** 讀出物品 blob，在主執行緒交給 consumer；finished=true 時之後把資料列標為結束並清掉 blob */
    private void takeItems(Lot l, java.util.function.Consumer<List<ItemStack>> consumer, boolean finished) {
        long id = l.id;
        String st = l.status;
        plugin.db().query(c -> {
            byte[] blob = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM market WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) blob = rs.getBytes(1); }
            }
            return blob;
        }).whenComplete((blob, err) -> plugin.sync(() -> {
            List<ItemStack> items = new ArrayList<>();
            if (blob != null) {
                try { items.add(ItemStack.deserializeBytes(blob)); }
                catch (Exception e) { items.add(new ItemStack(l.material, l.amount)); }
            } else if (err == null) items.add(new ItemStack(l.material, l.amount));
            consumer.accept(items);
            if (finished) plugin.db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE market SET status=?, items=NULL, finished=? WHERE id=?")) {
                    ps.setString(1, st);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setLong(3, id);
                    ps.executeUpdate();
                }
                return null;
            });
        }));
    }

    private void give(Player p, ItemStack it) {
        if (!p.isOnline()) { p.getWorld().dropItemNaturally(p.getLocation(), it); return; }
        Map<Integer, ItemStack> over = p.getInventory().addItem(it);
        if (!over.isEmpty()) {
            over.values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            plugin.msg().send(p, "inventory-full");
        }
    }

    public Component describe(Lot l) {
        return Component.translatable(l.material.translationKey()).append(Component.text(" ×" + l.amount, NamedTextColor.WHITE));
    }
}
