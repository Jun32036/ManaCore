package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * 委託板：「求助 → 幫忙 → 感謝」的循環。
 *
 * - 物品委託：「需要 32 個烤馬鈴薯」，接單的人直接從背包交貨，東西送到發布者手上。
 * - 服務委託：「幫我蓋屋頂」「陪我去下界」，接下後由發布者確認完成。
 * 酬謝只能從固定的幾個級距中選（不講價、不比價），發布時先託管，完成才轉給幫忙的人；
 * 另收一點手續費流入生命之樹（mana 的出口之一）。幫忙的人除了酬謝，還會由世界額外回報「助人」mana
 * （同一對玩家每週有次數限制，避免兩個人互相發委託刷 mana）。
 */
public final class CommissionService {

    public static final class Commission {
        public long id;
        public long created;
        public UUID poster;
        public String posterName;
        public String posterIp;
        public String kind; // item / service
        public Material material;
        public int amount;
        public long reward;
        public String note;
        public String status; // open / taken / done / cancelled
        public UUID taker;
        public String takerName;
        public boolean claimed = true;

        public boolean isItem() {
            return "item".equals(kind);
        }
    }

    private final ManaCorePlugin plugin;
    private final Map<Long, Commission> live = new LinkedHashMap<>();
    /** 已送出、資料庫還沒回傳編號的發布（同一 tick 連發多張也要算進上限） */
    private final Map<UUID, Integer> posting = new java.util.HashMap<>();
    /** 正在領取中的委託（避免連點重複領取） */
    private final java.util.Set<Long> claiming = new java.util.HashSet<>();

    public CommissionService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------- 設定 ----------------

    public boolean enabled() {
        return plugin.getConfig().getBoolean("commissions.enabled", true);
    }

    public List<Long> rewardTiers() {
        List<Long> out = new ArrayList<>();
        for (Object o : plugin.getConfig().getList("commissions.reward-tiers", List.of(2, 5, 10, 20, 50)))
            if (o instanceof Number n && n.longValue() > 0) out.add(n.longValue());
        if (out.isEmpty()) out.addAll(List.of(2L, 5L, 10L, 20L, 50L));
        return out;
    }

    public long fee(long reward) {
        return EconMath.fee(reward, plugin.getConfig().getDouble("commissions.fee-rate", 0.1), plugin.getConfig().getLong("commissions.min-fee", 1));
    }

    public int maxOpen() {
        return plugin.getConfig().getInt("commissions.max-open-per-player", 3);
    }

    public int maxItemAmount() {
        return plugin.getConfig().getInt("commissions.max-item-amount", 576);
    }

    // ---------------- 載入 ----------------

    public void load() throws Exception {
        plugin.db().query(c -> {
            List<Commission> list = new ArrayList<>();
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, created, poster, poster_name, poster_ip, kind, material, amount, reward, note, status, taker, taker_name, claimed FROM commissions WHERE status IN ('open','taken') OR (status='done' AND claimed=0)")) {
                while (rs.next()) list.add(read(rs));
            }
            return list;
        }).get().forEach(cm -> live.put(cm.id, cm));
    }

    private static Commission read(ResultSet rs) throws java.sql.SQLException {
        Commission cm = new Commission();
        cm.id = rs.getLong(1);
        cm.created = rs.getLong(2);
        cm.poster = UUID.fromString(rs.getString(3));
        cm.posterName = rs.getString(4);
        cm.posterIp = rs.getString(5);
        cm.kind = rs.getString(6);
        String mat = rs.getString(7);
        cm.material = mat == null ? null : Material.matchMaterial(mat);
        cm.amount = rs.getInt(8);
        cm.reward = rs.getLong(9);
        cm.note = rs.getString(10);
        cm.status = rs.getString(11);
        String t = rs.getString(12);
        cm.taker = t == null ? null : UUID.fromString(t);
        cm.takerName = rs.getString(13);
        cm.claimed = rs.getInt(14) == 1;
        return cm;
    }

    // ---------------- 查詢 ----------------

    /** 委託板上可以接的（open），新的在前 */
    public List<Commission> board() {
        List<Commission> out = new ArrayList<>();
        for (Commission c : live.values()) if ("open".equals(c.status)) out.add(c);
        out.sort(Comparator.comparingLong((Commission c) -> c.created).reversed());
        return out;
    }

    /** 我發布的（未結束或待領取）與我接下的 */
    public List<Commission> mine(UUID me) {
        List<Commission> out = new ArrayList<>();
        for (Commission c : live.values()) if (me.equals(c.poster) || me.equals(c.taker) && "taken".equals(c.status)) out.add(c);
        out.sort(Comparator.comparingLong((Commission c) -> c.created).reversed());
        return out;
    }

    public long openCount(UUID poster) {
        return posting.getOrDefault(poster, 0)
                + live.values().stream().filter(c -> poster.equals(c.poster) && ("open".equals(c.status) || "taken".equals(c.status))).count();
    }

    public int unclaimed(UUID poster) {
        int n = 0;
        for (Commission c : live.values()) if (poster.equals(c.poster) && "done".equals(c.status) && !c.claimed) n++;
        return n;
    }

    // ---------------- 發布 ----------------

    public boolean postItem(Player p, Material m, int amount, long reward) {
        if (m == null || m.isAir() || !m.isItem()) {
            plugin.msg().raw(p, "<gray>請先把想要的物品拿在主手上。</gray>");
            return false;
        }
        if (amount < 1 || amount > maxItemAmount()) {
            plugin.msg().raw(p, "<gray>數量要在 1 到 <n> 之間。</gray>", "n", maxItemAmount());
            return false;
        }
        return post(p, "item", m, amount, reward, "");
    }

    public boolean postService(Player p, long reward, String note) {
        String n = note == null ? "" : note.strip();
        if (n.length() < 4) {
            plugin.msg().raw(p, "<gray>請寫清楚需要什麼幫忙（至少 4 個字）。</gray>");
            return false;
        }
        if (n.length() > 80) n = n.substring(0, 80);
        return post(p, "service", null, 0, reward, n);
    }

    private boolean post(Player p, String kind, Material m, int amount, long reward, String note) {
        if (!enabled()) {
            plugin.msg().raw(p, "<gray>委託板目前沒有開放。</gray>");
            return false;
        }
        PlayerData d = plugin.mana().get(p);
        if (d == null) return false;
        if (!rewardTiers().contains(reward)) {
            plugin.msg().raw(p, "<gray>酬謝只能選：<t>（不講價、不比價）。</gray>", "t", rewardTiers().toString());
            return false;
        }
        if (openCount(p.getUniqueId()) >= maxOpen()) {
            plugin.msg().raw(p, "<gray>你同時最多只能有 <n> 張進行中的委託。</gray>", "n", maxOpen());
            return false;
        }
        long fee = fee(reward);
        if (d.balance < reward + fee) {
            plugin.msg().send(p, "not-enough", "need", reward + fee, "have", d.balance);
            return false;
        }
        String what = "item".equals(kind) ? amount + "x " + m.getKey().getKey() : note;
        if (!plugin.mana().spend(p, reward, Source.COMMISSION_OUT, "託管：" + what)) return false;
        if (fee > 0 && plugin.mana().spend(p, fee, Source.FEE, "委託手續費（流入生命之樹）")) plugin.tree().add(fee);

        Commission cm = new Commission();
        cm.created = System.currentTimeMillis();
        cm.poster = p.getUniqueId();
        cm.posterName = p.getName();
        cm.posterIp = d.ip;
        cm.kind = kind;
        cm.material = m;
        cm.amount = amount;
        cm.reward = reward;
        cm.note = note;
        cm.status = "open";
        posting.merge(p.getUniqueId(), 1, Integer::sum);
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO commissions(created, poster, poster_name, poster_ip, kind, material, amount, reward, note, status, claimed) VALUES(?,?,?,?,?,?,?,?,?,'open',1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, cm.created);
                ps.setString(2, cm.poster.toString());
                ps.setString(3, cm.posterName);
                ps.setString(4, cm.posterIp);
                ps.setString(5, kind);
                ps.setString(6, m == null ? null : m.getKey().toString());
                ps.setInt(7, amount);
                ps.setLong(8, reward);
                ps.setString(9, note);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        }).whenComplete((id, err) -> plugin.sync(() -> {
            posting.computeIfPresent(cm.poster, (k, v) -> v <= 1 ? null : v - 1);
            if (err != null || id == null || id < 0) {
                // 寫入失敗：退回託管的酬謝（手續費已流入生命之樹，不重複退，避免憑空多出 mana）
                plugin.mana().adjust(cm.poster, reward, Source.COMMISSION_REFUND, "委託發布失敗退回");
                plugin.getLogger().warning("委託寫入資料庫失敗，已退回酬謝 " + reward + " mana 給 " + cm.posterName);
                return;
            }
            cm.id = id;
            live.put(id, cm);
            Component desc = "item".equals(kind)
                    ? Component.translatable(m.translationKey()).append(Component.text(" ×" + amount))
                    : Component.text(note);
            plugin.msg().raw(p, "<green>委託已發布（酬謝 <r> mana 已託管，手續費 <f> 流入生命之樹）。</green>", "r", reward, "f", fee);
            Bukkit.getServer().broadcast(plugin.msg().render("prefix").append(Component.text("📋 " + p.getName() + " 在委託板徵求：").append(desc)
                    .append(Component.text("（酬謝 " + reward + " mana，/mana board）")).color(net.kyori.adventure.text.format.NamedTextColor.AQUA)));
        }));
        return true;
    }

    // ---------------- 接單 / 完成 ----------------

    private boolean sameNetwork(PlayerData helper, Commission cm) {
        if (!plugin.getConfig().getBoolean("block-same-ip", true)) return false;
        return cm.posterIp != null && !cm.posterIp.isEmpty() && cm.posterIp.equals(helper.ip);
    }

    /** 物品委託：直接交貨 */
    public boolean fulfill(Player p, Commission cm) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !"open".equals(cm.status) || !cm.isItem()) return false;
        if (p.getUniqueId().equals(cm.poster)) {
            plugin.msg().raw(p, "<gray>這是你自己發布的委託。</gray>");
            return false;
        }
        if (sameNetwork(d, cm)) {
            plugin.msg().send(p, "same-ip");
            return false;
        }
        int have = OrderService.countPlain(p, cm.material);
        if (have < cm.amount) {
            plugin.msg().raw(p, "<gray>需要 <n> 個 <i>，你身上有 <h> 個。</gray>", "n", cm.amount, "i", Component.translatable(cm.material.translationKey()), "h", have);
            return false;
        }
        List<ItemStack> stacks = OrderService.removePlain(p, cm.material, cm.amount);
        cm.status = "done";
        cm.taker = p.getUniqueId();
        cm.takerName = p.getName();

        // 東西送到發布者手上；不在線就先存著，上線後到「我的委託」領取
        Player poster = Bukkit.getPlayer(cm.poster);
        byte[] blob = null;
        if (poster != null) {
            give(poster, stacks);
            plugin.msg().raw(poster, "<green><n> 完成了你的委託，<i> ×<a> 已經放進你的背包。記得 /thanks 謝謝對方！</green>",
                    "n", p.getName(), "i", Component.translatable(cm.material.translationKey()), "a", cm.amount);
        } else {
            cm.claimed = false;
            blob = ItemStack.serializeItemsAsBytes(stacks);
        }
        finish(cm, blob);
        pay(p, cm);
        return true;
    }

    /** 服務委託：接下 */
    public boolean take(Player p, Commission cm) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !"open".equals(cm.status) || cm.isItem()) return false;
        if (p.getUniqueId().equals(cm.poster)) {
            plugin.msg().raw(p, "<gray>這是你自己發布的委託。</gray>");
            return false;
        }
        if (sameNetwork(d, cm)) {
            plugin.msg().send(p, "same-ip");
            return false;
        }
        cm.status = "taken";
        cm.taker = p.getUniqueId();
        cm.takerName = p.getName();
        update(cm, null, false);
        plugin.msg().raw(p, "<green>你接下了 <n> 的委託：「<t>」。完成後請對方在 /mana board →「我的委託」確認。</green>", "n", cm.posterName, "t", cm.note);
        Player poster = Bukkit.getPlayer(cm.poster);
        if (poster != null) plugin.msg().raw(poster, "<green><n> 接下了你的委託「<t>」！完成後到 /mana board →「我的委託」確認。</green>", "n", p.getName(), "t", cm.note);
        return true;
    }

    /** 服務委託：發布者確認完成 */
    public boolean confirm(Player poster, Commission cm) {
        if (!poster.getUniqueId().equals(cm.poster) || !"taken".equals(cm.status) || cm.taker == null) return false;
        cm.status = "done";
        finish(cm, null);
        Player taker = Bukkit.getPlayer(cm.taker);
        payOffline(cm, taker);
        plugin.msg().raw(poster, "<green>已確認完成，酬謝 <r> mana 交給了 <n>。</green>", "r", cm.reward, "n", cm.takerName);
        return true;
    }

    /** 接單者放棄（回到委託板） */
    public boolean giveUp(Player taker, Commission cm) {
        if (!"taken".equals(cm.status) || !taker.getUniqueId().equals(cm.taker)) return false;
        cm.status = "open";
        String old = cm.takerName;
        cm.taker = null;
        cm.takerName = null;
        update(cm, null, false);
        plugin.msg().raw(taker, "<gray>你放棄了這張委託，它回到了委託板上。</gray>");
        Player poster = Bukkit.getPlayer(cm.poster);
        if (poster != null) plugin.msg().raw(poster, "<gray><n> 放棄了你的委託「<t>」，它回到了委託板上。</gray>", "n", old, "t", cm.note);
        return true;
    }

    /** 發布者取消：退回酬謝（手續費不退） */
    public boolean cancel(Player poster, Commission cm) {
        if (!poster.getUniqueId().equals(cm.poster) || !("open".equals(cm.status) || "taken".equals(cm.status))) return false;
        UUID taker = cm.taker;
        cancelInternal(cm, "取消委託退回");
        plugin.msg().raw(poster, "<green>委託已取消，酬謝 <r> mana 已退回（手續費不退）。</green>", "r", cm.reward);
        Player tp = taker == null ? null : Bukkit.getPlayer(taker);
        if (tp != null) plugin.msg().raw(tp, "<gray><n> 取消了委託「<t>」。</gray>", "n", cm.posterName, "t", cm.note);
        return true;
    }

    private void cancelInternal(Commission cm, String why) {
        cm.status = "cancelled";
        live.remove(cm.id);
        plugin.mana().adjust(cm.poster, cm.reward, Source.COMMISSION_REFUND, why + " #" + cm.id);
        update(cm, null, true);
    }

    /** 發布者領取離線時送達的物品：先在主執行緒把物品交出去，之後才清掉資料庫裡的物品 */
    public void claim(Player poster, Commission cm) {
        if (!poster.getUniqueId().equals(cm.poster) || !"done".equals(cm.status) || cm.claimed || claiming.contains(cm.id)) return;
        long id = cm.id;
        claiming.add(id);
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM commissions WHERE id=? AND claimed=0")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getBytes(1) : null;
                }
            }
        }).whenComplete((blob, err) -> plugin.sync(() -> {
            claiming.remove(id);
            if (err != null || blob == null || !poster.isOnline() || cm.claimed) return; // 什麼都沒交出去，資料庫也沒動
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack it : ItemStack.deserializeItemsFromBytes(blob)) if (it != null && !it.isEmpty()) items.add(it);
            cm.claimed = true;
            live.remove(id);
            give(poster, items);
            plugin.msg().raw(poster, "<green>已領取委託物品。</green>");
            plugin.db().run(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE commissions SET claimed=1, items=NULL WHERE id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                return null;
            });
        }));
    }

    /** 超過 expire-days 還沒完成的委託自動取消並退回酬謝（啟動與跨日時執行） */
    public void expireOld() {
        long days = plugin.getConfig().getLong("commissions.expire-days", 7);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        for (Commission cm : new ArrayList<>(live.values())) {
            if (cm.created < cutoff && ("open".equals(cm.status) || "taken".equals(cm.status))) cancelInternal(cm, "委託逾期退回");
        }
    }

    // ---------------- 內部 ----------------

    private void pay(Player helper, Commission cm) {
        plugin.mana().adjust(helper.getUniqueId(), cm.reward, Source.COMMISSION_IN, "完成 " + cm.posterName + " 的委託 #" + cm.id);
        plugin.msg().raw(helper, "<green>委託完成！得到 <r> mana 酬謝。</green>", "r", cm.reward);
        helpBonus(cm, helper);
    }

    private void payOffline(Commission cm, Player takerOnline) {
        plugin.mana().adjust(cm.taker, cm.reward, Source.COMMISSION_IN, "完成 " + cm.posterName + " 的委託 #" + cm.id);
        if (takerOnline != null) plugin.msg().raw(takerOnline, "<green><n> 確認你完成了委託，得到 <r> mana 酬謝。</green>", "n", cm.posterName, "r", cm.reward);
        helpBonus(cm, takerOnline);
    }

    /** 世界額外回報幫忙的人（不是從發布者身上拿） */
    private void helpBonus(Commission cm, Player helperOnline) {
        if (!plugin.getConfig().getBoolean("sources.help.enabled", true)) return;
        long amount = plugin.getConfig().getLong("sources.help.amount", 3);
        long minReward = plugin.getConfig().getLong("sources.help.min-reward", 5);
        int perWeek = plugin.getConfig().getInt("sources.help.max-per-pair-week", 3);
        if (amount <= 0 || cm.reward < minReward) return;
        String poster = cm.poster.toString(), taker = cm.taker.toString();
        long since = System.currentTimeMillis() - 7 * 86_400_000L;
        plugin.db().query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM commissions WHERE poster=? AND taker=? AND status='done' AND finished>=?")) {
                ps.setString(1, poster);
                ps.setString(2, taker);
                ps.setLong(3, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).thenAccept(n -> plugin.sync(() -> {
            if (perWeek > 0 && n > perWeek) {
                if (helperOnline != null && helperOnline.isOnline())
                    plugin.msg().raw(helperOnline, "<gray>（這週已經幫 <n> 完成 <k> 次委託，這次只有酬謝，沒有額外的助人 mana。多幫幫不同的人吧！）</gray>", "n", cm.posterName, "k", perWeek);
                return;
            }
            plugin.mana().earnAnywhere(cm.taker, Source.HELP, amount, Component.text("幫 " + cm.posterName + " 完成委託"), "委託 #" + cm.id);
        }));
    }

    private void finish(Commission cm, byte[] blob) {
        if (cm.claimed) live.remove(cm.id);
        update(cm, blob, true);
    }

    private void update(Commission cm, byte[] blob, boolean finished) {
        long id = cm.id;
        String st = cm.status, taker = cm.taker == null ? null : cm.taker.toString(), takerName = cm.takerName;
        int claimed = cm.claimed ? 1 : 0;
        long fin = finished ? System.currentTimeMillis() : 0;
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE commissions SET status=?, taker=?, taker_name=?, claimed=?, finished=?, items=COALESCE(?, items) WHERE id=?")) {
                ps.setString(1, st);
                ps.setString(2, taker);
                ps.setString(3, takerName);
                ps.setInt(4, claimed);
                if (fin > 0) ps.setLong(5, fin);
                else ps.setNull(5, java.sql.Types.INTEGER);
                ps.setBytes(6, blob);
                ps.setLong(7, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            Map<Integer, ItemStack> over = p.getInventory().addItem(it);
            if (!over.isEmpty()) {
                over.values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
                plugin.msg().send(p, "inventory-full");
            }
        }
    }
}
