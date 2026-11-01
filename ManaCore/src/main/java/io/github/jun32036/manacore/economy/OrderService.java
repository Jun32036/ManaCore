package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.Database;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 城鎮訂單：每天依日期從訂單池抽出幾張「城鎮需要的東西」（料理、魚、作物、羊毛、礦物、建材），
 * 每張訂單全服共同完成 bundles 份，每人每天最多交 per-player 份。
 *
 * 這是獨自遊玩的人最主要的收入：生產的東西「有人需要」才有價值，而且需求每天輪替，
 * 蓋一座巨型自動農場只刷一種東西沒有用。整張訂單被大家交滿時，所有出過力的人再拿一筆完成獎勵，
 * 不需要開口講話也能和其他人「一起」完成一件事。
 */
public final class OrderService {

    public record Def(String id, Material item, int amount, long reward, Skill skill, int bundles, int perPlayer, int level) {}

    public static final class Order {
        public final int idx;
        public final Def def;
        public int delivered;

        Order(int idx, Def def, int delivered) {
            this.idx = idx;
            this.def = def;
            this.delivered = delivered;
        }

        public boolean complete() {
            return delivered >= def.bundles();
        }
    }

    private final ManaCorePlugin plugin;
    private final Map<String, Def> pool = new HashMap<>();
    private final List<Order> today = new ArrayList<>();
    private String day = "";
    /** 今天每張訂單的貢獻者（完成時發獎勵）：序號 → (玩家 → 份數) */
    private final Map<Integer, Map<UUID, Integer>> contributors = new HashMap<>();

    public OrderService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("sources.order.enabled", true);
    }

    public List<Order> today() {
        return today;
    }

    /** 依訂單序號找（序號 ≠ 清單位置：設定檔移除某張訂單後，序號仍沿用當天抽選時的順序） */
    public Order find(int idx) {
        for (Order o : today) if (o.idx == idx) return o;
        return null;
    }

    public long completionBonus() {
        return plugin.getConfig().getLong("sources.order.completion-bonus", 3);
    }

    // ---------------- 載入 ----------------

    public void reloadPool() {
        pool.clear();
        ConfigurationSection sec = plugin.section("sources.order.pool");
        if (sec == null) return;
        int defBundles = plugin.getConfig().getInt("sources.order.default-bundles", 10);
        int defPer = plugin.getConfig().getInt("sources.order.default-per-player", 2);
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Material m = Material.matchMaterial(s.getString("item", ""));
            Skill sk = Skill.byKey(s.getString("skill", ""));
            if (m == null || !m.isItem() || sk == null) {
                plugin.getLogger().warning("城鎮訂單 " + id + " 設定有誤（item 或 skill），已略過");
                continue;
            }
            pool.put(id, new Def(id, m, Math.max(1, s.getInt("amount", 16)), Math.max(1, s.getLong("reward", 3)), sk,
                    Math.max(1, s.getInt("bundles", defBundles)), Math.max(1, s.getInt("per-player", defPer)), Math.max(1, s.getInt("level", 1))));
        }
    }

    /** 啟動、跨日、重抽時呼叫：決定今天的訂單並讀取進度 */
    public void loadToday(boolean reroll) {
        reloadPool();
        String d = plugin.mana().today();
        int count = plugin.getConfig().getInt("sources.order.daily-count", 6);
        int maxPerSkill = plugin.getConfig().getInt("sources.order.max-per-skill", 2);
        List<EconMath.Candidate> cands = new ArrayList<>();
        for (Def def : pool.values()) cands.add(new EconMath.Candidate(def.id(), def.skill().key));
        String salt = reroll ? "#" + System.currentTimeMillis() : "";
        List<String> picked = EconMath.pickOrders(d + salt, cands, count, maxPerSkill);

        plugin.db().query(c -> {
            String kvKey = "orders:" + d;
            String stored = reroll ? null : Database.getKv(c, kvKey);
            List<String> ids = stored == null || stored.isEmpty() ? picked : List.of(stored.split(","));
            if (stored == null || reroll) {
                Database.setKv(c, kvKey, String.join(",", ids));
                if (reroll) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM order_progress WHERE day=?")) {
                        ps.setString(1, d);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM order_contrib WHERE day=?")) {
                        ps.setString(1, d);
                        ps.executeUpdate();
                    }
                }
            }
            Map<Integer, Integer> progress = new HashMap<>();
            Map<Integer, Map<UUID, Integer>> contrib = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT idx, delivered FROM order_progress WHERE day=?")) {
                ps.setString(1, d);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) progress.put(rs.getInt(1), rs.getInt(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT idx, uuid, bundles FROM order_contrib WHERE day=?")) {
                ps.setString(1, d);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) contrib.computeIfAbsent(rs.getInt(1), k -> new HashMap<>()).put(UUID.fromString(rs.getString(2)), rs.getInt(3));
                }
            }
            return new Object[]{ids, progress, contrib};
        }).thenAccept(res -> plugin.sync(() -> {
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) res[0];
            @SuppressWarnings("unchecked") Map<Integer, Integer> progress = (Map<Integer, Integer>) res[1];
            @SuppressWarnings("unchecked") Map<Integer, Map<UUID, Integer>> contrib = (Map<Integer, Map<UUID, Integer>>) res[2];
            // 同一天重新載入（/manaadmin reload）：以記憶體裡的進度為準，
            // 避免讀資料庫到回到主執行緒之間的交貨被蓋掉
            boolean sameDay = !reroll && d.equals(day) && !today.isEmpty();
            if (sameDay) {
                progress.clear();
                for (Order o : today) progress.put(o.idx, o.delivered);
            } else {
                contributors.clear();
                contributors.putAll(contrib);
            }
            today.clear();
            int idx = 0;
            for (String id : ids) {
                Def def = pool.get(id);
                if (def != null) today.add(new Order(idx, def, progress.getOrDefault(idx, 0)));
                idx++;
            }
            day = d;
            if (reroll) for (PlayerData pd : plugin.mana().all()) pd.ordersToday.clear();
        }));
    }

    // ---------------- 交貨 ----------------

    /** 背包中可交貨的數量（改過名的物品不收，避免誤交） */
    public static int countPlain(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isPlain(it, m)) n += it.getAmount();
        }
        return n;
    }

    static boolean isPlain(ItemStack it, Material m) {
        return it != null && it.getType() == m && !(it.hasItemMeta() && it.getItemMeta().hasDisplayName());
    }

    /** 從背包移除 amount 個（呼叫前請先確認數量足夠），回傳拿走的物品（保留附魔等資料） */
    public static List<ItemStack> removePlain(Player p, Material m, int amount) {
        List<ItemStack> taken = new ArrayList<>();
        ItemStack[] contents = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (!isPlain(it, m)) continue;
            int take = Math.min(left, it.getAmount());
            left -= take;
            taken.add(it.asQuantity(take));
            if (take >= it.getAmount()) contents[i] = null;
            else it.setAmount(it.getAmount() - take);
        }
        p.getInventory().setStorageContents(contents);
        return taken;
    }

    public int mine(PlayerData d, Order o) {
        return d.ordersToday.getOrDefault(o.idx, 0);
    }

    /** @return 交貨成功與否（失敗時已告知原因） */
    public boolean deliver(Player p, int idx) {
        if (!enabled()) return false;
        PlayerData d = plugin.mana().get(p);
        Order o = find(idx);
        if (d == null || o == null || !plugin.mana().today().equals(day)) return false;
        Def def = o.def;
        if (o.complete()) {
            plugin.msg().raw(p, "<gray>這張訂單已經被大家完成了，謝謝！</gray>");
            return false;
        }
        int lv = plugin.craft().level(d, def.skill());
        if (lv < def.level()) {
            plugin.msg().raw(p, "<gray>這張訂單需要「<s>」Lv<n>（你目前 Lv<lv>）。</gray>", "s", def.skill().display, "n", def.level(), "lv", lv);
            return false;
        }
        int mine = mine(d, o);
        if (mine >= def.perPlayer()) {
            plugin.msg().raw(p, "<gray>你今天已經交了 <n> 份，把機會留給其他人吧！</gray>", "n", mine);
            return false;
        }
        long rem = plugin.mana().remaining(d, Source.ORDER);
        if (rem == 0) {
            plugin.msg().raw(p, "<gray>今天城鎮訂單的 mana 已達上限，明天再來交貨吧（東西先留著）。</gray>");
            return false;
        }
        int have = countPlain(p, def.item());
        if (have < def.amount()) {
            plugin.msg().raw(p, "<gray>需要 <n> 個 <i>，你身上有 <h> 個。</gray>", "n", def.amount(), "i", Component.translatable(def.item().translationKey()), "h", have);
            return false;
        }
        removePlain(p, def.item(), def.amount());
        o.delivered++;
        d.ordersToday.merge(idx, 1, Integer::sum);
        contributors.computeIfAbsent(idx, k -> new HashMap<>()).merge(p.getUniqueId(), 1, Integer::sum);
        String dd = day, uid = p.getUniqueId().toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO order_progress(day, idx, delivered) VALUES(?,?,1) ON CONFLICT(day, idx) DO UPDATE SET delivered = delivered + 1")) {
                ps.setString(1, dd);
                ps.setInt(2, idx);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO order_contrib(day, idx, uuid, bundles) VALUES(?,?,?,1) ON CONFLICT(day, idx, uuid) DO UPDATE SET bundles = bundles + 1")) {
                ps.setString(1, dd);
                ps.setInt(2, idx);
                ps.setString(3, uid);
                ps.executeUpdate();
            }
            return null;
        });
        plugin.mana().earn(p, Source.ORDER, def.reward(),
                Component.text("城鎮訂單：").append(Component.translatable(def.item().translationKey())).append(Component.text(" ×" + def.amount())),
                def.id(), false);
        plugin.craft().addXp(p, def.skill(), def.reward() * plugin.getConfig().getDouble("sources.order.xp-per-reward", 2));
        p.playSound(p, Sound.ENTITY_VILLAGER_YES, 0.6f, 1.1f);
        if (o.complete()) completeOrder(o);
        return true;
    }

    private void completeOrder(Order o) {
        Map<UUID, Integer> who = contributors.getOrDefault(o.idx, Map.of());
        Component item = Component.translatable(o.def.item().translationKey());
        Bukkit.getServer().broadcast(plugin.msg().render("prefix").append(Component.text("🏘 城鎮訂單「").append(item)
                .append(Component.text("」被 " + who.size() + " 位玩家一起完成了！")).color(net.kyori.adventure.text.format.NamedTextColor.GOLD)));
        long bonus = completionBonus();
        if (bonus <= 0) return;
        for (UUID id : who.keySet()) {
            // 完成獎勵屬於活動性質，不佔城鎮訂單的每日上限
            plugin.mana().earnAnywhere(id, Source.EVENT, bonus, Component.text("大家一起完成了城鎮訂單"), "訂單完成：" + o.def.id());
        }
    }
}
