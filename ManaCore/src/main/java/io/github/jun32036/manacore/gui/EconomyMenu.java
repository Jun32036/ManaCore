package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;
import io.github.jun32036.manacore.economy.CommissionService;
import io.github.jun32036.manacore.economy.CraftService;
import io.github.jun32036.manacore.economy.DexService;
import io.github.jun32036.manacore.economy.EconMath;
import io.github.jun32036.manacore.economy.OrderService;
import io.github.jun32036.manacore.economy.Passives;
import io.github.jun32036.manacore.economy.ProjectService;
import io.github.jun32036.manacore.economy.Skill;

import java.util.ArrayList;
import java.util.List;

import static io.github.jun32036.manacore.gui.Menu.click;
import static io.github.jun32036.manacore.gui.Menu.icon;
import static io.github.jun32036.manacore.gui.Menu.tr;

/** 經濟系統的圖形介面：生活手藝、圖鑑、城鎮訂單、委託板、社區工程。 */
public final class EconomyMenu {

    private static final int[] POST_AMOUNTS = {1, 8, 16, 32, 64, 128, 256};
    private static final long[] INVEST_AMOUNTS = {10, 50, 100, 500};

    private final ManaCorePlugin plugin;

    public EconomyMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    private static String bar(double ratio, int width) {
        int filled = (int) Math.max(0, Math.min(width, Math.round(ratio * width)));
        return "<green>" + "▮".repeat(filled) + "</green><dark_gray>" + "▮".repeat(width - filled) + "</dark_gray>";
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private PlayerData data(Player p) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
        return d;
    }

    private Runnable main(Player p) {
        return () -> plugin.openCategory(p, Dialogs.SELF);
    }

    // ======================================================================
    // 生活手藝
    // ======================================================================

    public void openSkills(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        CraftService cs = plugin.craft();
        Passives pv = plugin.passives();
        Menu m = new Menu(4, "<dark_green>生活手藝");
        m.header(plugin, p, main(p));
        long cap = cs.perSkillCap();
        int slot = 10;
        for (Skill s : Skill.values()) {
            if (slot == 13) slot = 14;
            int lv = cs.level(d, s);
            double xp = d.xp(s), next = EconMath.nextLevelXp(lv, cs.levels());
            double prev = cs.levels().get(Math.min(lv - 1, cs.levels().size() - 1)).doubleValue();
            double fresh = cs.freshness(p.getUniqueId(), s);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + s.how);
            lore.add("");
            lore.add("<white>Lv" + lv + "「" + cs.title(lv) + "」");
            if (next > 0) lore.add(bar((xp - prev) / Math.max(1, next - prev), 12) + " <gray>" + (long) xp + "/" + (long) next);
            else lore.add("<gold>已達最高等級");
            if (pv.enabled()) {
                double now = pv.bonusAt(s, lv), nxt = pv.bonusAt(s, lv + 1);
                lore.add("<gray>被動：<white>" + Passives.describe(s) + " " + pct(now) + (next > 0 && nxt > now ? " <dark_gray>（下一級 " + pct(nxt) + "）" : ""));
            }
            lore.add("<gray>今天 mana：<white>" + d.skillEarned(s) + (cap > 0 ? "/" + cap : ""));
            lore.add("<gray>新鮮度：" + (fresh >= 0.7 ? "<green>" : fresh >= 0.45 ? "<yellow>" : "<red>") + pct(fresh)
                    + " <dark_gray>多樣性 " + pct(cs.varietyFactor(p.getUniqueId(), s)));
            m.set(slot++, icon(s.icon, Math.max(1, lv), "<gold>" + s.display, lore));
            if (slot == 17) slot = 19;
        }
        int companions = plugin.coop().companions(p);
        double bonus = plugin.getConfig().getDouble("sources.craft.companion.bonus", 0.25);
        long total = plugin.mana().cap(Source.CRAFT);
        m.set(13, icon(Material.KNOWLEDGE_BOOK, 1, "<green>手藝怎麼算？", List.of(
                "<gray>點數 = 基礎 × <white>多樣性</white> × <white>結伴</white> × <white>新鮮度",
                "<gray>每 " + (long) cs.pointsPerMana() + " 點換 1 mana",
                "",
                "<white>多樣性<gray>：最近做的東西越多樣越高",
                "<white>新鮮度<gray>：一直做同一件事會下降，休息會恢復",
                "<white>結伴<gray>：附近有夥伴一起時 +" + pct(bonus) + "（現在 " + companions + " 位）",
                "<white>被動<gray>：等級越高，多拿一份的機率越高",
                "",
                "<gray>單一手藝每天最多 <white>" + cap + "</white>，合計 <white>" + (total > 0 ? total : "不限"),
                "<gray>超過上限仍會累積經驗")));
        m.frame(Menu.SELF);
        m.open(p);
    }

    // ======================================================================
    // 圖鑑
    // ======================================================================

    public void openDex(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        DexService dex = plugin.dex();
        Menu m = new Menu(4, "<dark_aqua>圖鑑");
        m.header(plugin, p, main(p));
        int slot = 10;
        int total = 0;
        for (DexService.Category c : DexService.Category.values()) {
            if (slot == 17) slot = 19;
            int n = dex.count(d, c);
            total += n;
            m.set(slot++, icon(c.icon, Math.max(1, Math.min(64, n)), "<aqua>" + c.display, List.of(
                    "<gray>已登錄 <white>" + n + "</white> 種",
                    "<gray>每登錄一種新的：<white>+" + dex.amount(c) + " mana",
                    "", "<yellow>點一下：看收集到的")), e -> { click(p); openDexCategory(p, c, 0); });
        }
        long rem = plugin.mana().remaining(d, Source.DEX);
        m.set(2, icon(Material.BOOK, 1, "<white>我的圖鑑：" + total + " 種", List.of(
                "<gray>第一次釣到、種出、做出、繁殖、討伐的東西",
                "<gray>都會登錄進圖鑑並得到 mana（只算一次）",
                "<gray>每個分類收集滿 " + plugin.getConfig().getInt("sources.dex.milestone-every", 10) + " 種再加碼",
                "",
                "<gray>今天圖鑑還能拿：<white>" + (rem < 0 ? "不限" : rem + " mana"),
                "<dark_gray>刷同一樣東西一千次只算一次，嘗試新東西吧")));
        m.frame(Menu.SELF);
        m.open(p);
    }

    private void openDexCategory(Player p, DexService.Category c, int page) {
        PlayerData d = data(p);
        if (d == null) return;
        List<String> ids = plugin.dex().entries(d, c);
        Menu m = new Menu(6, "<dark_aqua>圖鑑 · " + c.display + "（" + ids.size() + "）");
        m.header(plugin, p, () -> openDex(p));
        List<Pager.Entry> entries = new ArrayList<>();
        for (String id : ids) {
            Material mat = Material.matchMaterial(id);
            if (mat == null || !mat.isItem()) mat = Material.matchMaterial(id + "_spawn_egg");
            String name;
            if (mat != null && mat.isItem() && !mat.name().endsWith("_SPAWN_EGG")) name = tr(mat);
            else {
                EntityType et = org.bukkit.Registry.ENTITY_TYPE.get(org.bukkit.NamespacedKey.minecraft(id));
                name = et != null ? "<lang:" + et.translationKey() + ">" : esc(id);
            }
            entries.add(new Pager.Entry(icon(mat != null && mat.isItem() ? mat : c.icon, 1, "<white>" + name, List.of("<dark_gray>" + esc(id))), null));
        }
        Pager.fill(m, entries, page, pg -> openDexCategory(p, c, pg), "還沒有登錄任何東西", "去試試新的東西吧！");
        m.frame(Menu.SELF);
        m.open(p);
    }

    // ======================================================================
    // 城鎮訂單
    // ======================================================================

    public void openOrders(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        OrderService os = plugin.orders();
        Menu m = new Menu(4, "<gold>城鎮訂單 · 今天的需求");
        m.header(plugin, p, main(p));
        int slot = 10;
        for (OrderService.Order o : os.today()) {
            if (slot > 16) break;
            OrderService.Def def = o.def;
            int lv = plugin.craft().level(d, def.skill());
            int mine = os.mine(d, o);
            int have = OrderService.countPlain(p, def.item());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>手藝：<white>" + def.skill().display + (def.level() > 1 ? " Lv" + def.level() + " 以上" : ""));
            lore.add("<gray>每份：<white>" + def.amount() + " 個</white> → <aqua>" + def.reward() + " mana");
            lore.add("<gray>全服進度：" + bar(o.delivered / (double) def.bundles(), 10) + " <white>" + o.delivered + "/" + def.bundles());
            lore.add("<gray>你今天交了：<white>" + mine + "/" + def.perPlayer() + "<gray>　身上有：<white>" + have);
            lore.add("");
            boolean can = !o.complete() && lv >= def.level() && mine < def.perPlayer() && have >= def.amount();
            String action;
            if (o.complete()) action = "<gold>✔ 大家一起完成了！";
            else if (lv < def.level()) action = "<red>需要" + def.skill().display + " Lv" + def.level() + "（你 Lv" + lv + "）";
            else if (mine >= def.perPlayer()) action = "<gray>今天交夠了，把機會留給別人";
            else if (have < def.amount()) action = "<gray>還差 " + (def.amount() - have) + " 個";
            else action = "<yellow>點一下：交一份";
            lore.add(action);
            Material shown = o.complete() ? Material.LIME_STAINED_GLASS_PANE : def.item();
            ItemStack ic = icon(shown, Math.min(def.amount(), shown.getMaxStackSize()), "<white>" + tr(def.item()), lore);
            if (can) Menu.glint(ic);
            m.set(slot++, ic, e -> { if (os.deliver(p, o.idx)) Menu.success(p); else Menu.fail(p); openOrders(p); });
        }
        if (os.today().isEmpty()) m.set(13, icon(Material.STRUCTURE_VOID, 1, "<gray>今天沒有訂單", List.of("<dark_gray>服主還沒有設定訂單池")));
        long rem = plugin.mana().remaining(d, Source.ORDER);
        m.set(2, icon(Material.BELL, 1, "<gold>城鎮訂單", List.of(
                "<gray>城鎮每天需要不同的東西，",
                "<gray>交出你種的、釣的、煮的、挖的、做的，換 mana",
                "<gray>整張訂單被大家交滿時，每位出過力的人",
                "<gray>再得到 <white>" + os.completionBonus() + "</white> mana 完成獎勵",
                "",
                "<gray>今天訂單還能拿：<white>" + (rem < 0 ? "不限" : rem + " mana"),
                "<dark_gray>每天午夜換新的需求")));
        m.frame(Menu.SELF);
        m.open(p);
    }

    // ======================================================================
    // 委託板
    // ======================================================================

    public void openBoard(Player p, int page) {
        PlayerData d = data(p);
        if (d == null) return;
        CommissionService cs = plugin.commissions();
        Menu m = new Menu(6, "<dark_aqua>委託板");
        m.header(plugin, p, () -> plugin.openCategory(p, Dialogs.SOCIAL));
        long now = System.currentTimeMillis();
        List<Pager.Entry> entries = new ArrayList<>();
        for (CommissionService.Commission c : cs.board()) {
            boolean own = c.poster.equals(p.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>發布者：<white>" + esc(c.posterName));
            lore.add("<gray>酬謝：<aqua>" + c.reward + " mana" + (c.reward >= plugin.getConfig().getLong("sources.help.min-reward", 5) ? " <dark_gray>＋助人 mana" : ""));
            lore.add("<dark_gray>" + ago(now - c.created));
            lore.add("");
            if (c.isItem()) {
                int have = OrderService.countPlain(p, c.material);
                boolean can = !own && have >= c.amount;
                lore.add("<gray>你身上有：<white>" + have + "/" + c.amount);
                lore.add(own ? "<gray>這是你的委託" : can ? "<yellow>點一下：交貨" : "<gray>東西不夠");
                ItemStack ic = icon(c.material, Math.min(c.amount, c.material.getMaxStackSize()), (own ? "<gray>" : "<white>") + "徵求 " + tr(c.material) + " ×" + c.amount, lore);
                if (can) Menu.glint(ic);
                entries.add(new Pager.Entry(ic, e -> { if (!own) { if (cs.fulfill(p, c)) Menu.success(p); else Menu.fail(p); openBoard(p, page); } }));
            } else {
                lore.add(0, "<white>「" + esc(c.note) + "」");
                lore.add(own ? "<gray>這是你的委託" : "<yellow>點一下：接下這個委託");
                ItemStack ic = icon(Material.OAK_HANGING_SIGN, 1, (own ? "<gray>" : "<white>") + "請求幫忙", lore);
                entries.add(new Pager.Entry(ic, e -> { if (!own) { if (cs.take(p, c)) Menu.success(p); else Menu.fail(p); openBoard(p, page); } }));
            }
        }
        Pager.fill(m, entries, page, pg -> openBoard(p, pg), "目前沒有委託", "需要幫忙嗎？發布一張吧！");

        List<String> tiers = new ArrayList<>();
        for (long t : cs.rewardTiers()) tiers.add(String.valueOf(t));
        m.set(2, icon(Material.KNOWLEDGE_BOOK, 1, "<green>委託板怎麼用？", List.of(
                "<white>物品委託<gray>：把想要的物品拿在手上，點右邊的「發布」",
                "<white>服務委託<gray>：輸入 <white>/mana request service 酬謝 說明",
                "<gray>  例：/mana request service 10 幫我蓋屋頂",
                "",
                "<gray>酬謝只能選 <white>" + String.join("/", tiers) + "</white>（不講價）",
                "<gray>酬謝先託管，完成才交給對方；取消可退回",
                "<gray>手續費 " + pct(plugin.getConfig().getDouble("commissions.fee-rate", 0.1)) + " 流入生命之樹",
                "<gray>幫忙的人另外得到世界回報的「助人」mana")));
        Material held = p.getInventory().getItemInMainHand().getType();
        ItemStack post = icon(held.isAir() ? Material.WRITABLE_BOOK : held, 1, "<aqua>發布物品委託",
                held.isAir() ? List.of("<gray>先把想要的物品拿在<white>主手</white>，再點這裡")
                        : List.of("<gray>徵求：<white>" + tr(held), "<gray>（物品不會被拿走，只是告訴系統要什麼）", "", "<yellow>點一下：選數量"));
        m.set(6, post, e -> {
            Material h = p.getInventory().getItemInMainHand().getType();
            if (h.isAir()) { Menu.fail(p); plugin.msg().raw(p, "<gray>請先把想要的物品拿在主手上，再點一次。</gray>"); return; }
            click(p);
            openPostAmount(p, h);
        });
        int mineN = cs.mine(p.getUniqueId()).size(), unclaimed = cs.unclaimed(p.getUniqueId());
        ItemStack mine = icon(unclaimed > 0 ? Material.CHEST_MINECART : Material.CHEST, Math.max(1, Math.min(64, mineN)), "<gold>我的委託",
                List.of("<gray>我發布的、我接下的：<white>" + mineN + "</white> 張",
                        unclaimed > 0 ? "<yellow>有 " + unclaimed + " 張完成的委託物品待領取！" : "<gray>沒有待領取的物品", "", "<yellow>點一下查看"));
        if (unclaimed > 0) Menu.glint(mine);
        m.set(5, mine, e -> { click(p); openMyCommissions(p, 0); });
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    private static String ago(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " 分鐘前";
        long h = min / 60;
        if (h < 24) return h + " 小時前";
        return (h / 24) + " 天前";
    }

    private void openPostAmount(Player p, Material mat) {
        Menu m = new Menu(3, "<dark_aqua>徵求多少個？");
        m.header(plugin, p, () -> openBoard(p, 0));
        m.set(2, icon(mat, 1, "<white>" + tr(mat), List.of("<gray>選擇需要的數量")));
        int slot = 10;
        int max = plugin.commissions().maxItemAmount();
        for (int a : POST_AMOUNTS) {
            if (a > max) continue;
            int amount = a;
            m.set(slot++, icon(mat, Math.min(a, mat.getMaxStackSize()), "<white>" + a + " 個", List.of("<yellow>點一下：選擇酬謝")),
                    e -> { click(p); openPostReward(p, mat, amount); });
        }
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    private void openPostReward(Player p, Material mat, int amount) {
        PlayerData d = data(p);
        if (d == null) return;
        CommissionService cs = plugin.commissions();
        Menu m = new Menu(3, "<dark_aqua>酬謝多少 mana？");
        m.header(plugin, p, () -> openPostAmount(p, mat));
        m.set(2, icon(mat, Math.min(amount, mat.getMaxStackSize()), "<white>徵求 " + tr(mat) + " ×" + amount, List.of("<gray>選擇酬謝")));
        int slot = 11;
        for (long r : cs.rewardTiers()) {
            if (slot > 15) break;
            long fee = cs.fee(r);
            boolean ok = d.balance >= r + fee;
            String name = (ok ? "<aqua>" : "<gray>") + "酬謝 " + r + " mana";
            List<String> lore = List.of("<gray>手續費 " + fee + "（流入生命之樹）", "<gray>合計扣 <white>" + (r + fee), "", ok ? "<yellow>點一下：發布" : "<red>mana 不夠");
            m.set(slot++, ok ? plugin.icons().mana((int) Math.min(64, r), name, lore) : icon(Material.GRAY_DYE, (int) Math.min(64, r), name, lore),
                    e -> { if (cs.postItem(p, mat, amount, r)) { Menu.success(p); p.closeInventory(); } else Menu.fail(p); });
        }
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    public void openMyCommissions(Player p, int page) {
        CommissionService cs = plugin.commissions();
        Menu m = new Menu(6, "<gold>我的委託");
        m.header(plugin, p, () -> openBoard(p, 0));
        List<Pager.Entry> entries = new ArrayList<>();
        for (CommissionService.Commission c : cs.mine(p.getUniqueId())) {
            boolean poster = c.poster.equals(p.getUniqueId());
            String title = c.isItem() ? tr(c.material) + " ×" + c.amount : "「" + esc(c.note) + "」";
            List<String> lore = new ArrayList<>();
            lore.add("<gray>酬謝：<aqua>" + c.reward + " mana");
            Material ic = c.isItem() ? c.material : Material.OAK_HANGING_SIGN;
            Runnable action = null;
            switch (c.status) {
                case "open" -> { lore.add("<gray>狀態：<white>等待有人接下"); lore.add(""); lore.add("<red>Shift + 右鍵：取消（退回酬謝）"); }
                case "taken" -> {
                    if (poster) { lore.add("<gray>狀態：<white>" + esc(c.takerName) + " 正在幫忙"); lore.add(""); lore.add("<green>Shift + 左鍵：確認完成（酬謝交給對方）"); lore.add("<red>Shift + 右鍵：取消（退回酬謝）"); }
                    else { lore.add(0, "<gray>發布者：<white>" + esc(c.posterName)); lore.add("<gray>狀態：<white>你接下了，完成後請對方確認"); lore.add(""); lore.add("<red>Shift + 右鍵：放棄"); }
                }
                case "done" -> { ic = Material.CHEST_MINECART; lore.add("<gray>狀態：<white>" + esc(c.takerName) + " 已經完成"); lore.add(""); lore.add("<yellow>點一下：領取物品"); action = () -> cs.claim(p, c); }
                default -> { }
            }
            Runnable claim = action;
            ItemStack item = icon(ic, 1, "<white>" + title, lore);
            if (claim != null) Menu.glint(item);
            entries.add(new Pager.Entry(item, e -> {
                ClickType ct = e.getClick();
                boolean changed = false;
                if (claim != null) { claim.run(); changed = true; }
                else if (ct == ClickType.SHIFT_LEFT && poster && "taken".equals(c.status)) changed = cs.confirm(p, c);
                else if (ct == ClickType.SHIFT_RIGHT && poster) changed = cs.cancel(p, c);
                else if (ct == ClickType.SHIFT_RIGHT && !poster) changed = cs.giveUp(p, c);
                if (changed) { Menu.success(p); Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) openMyCommissions(p, page); }, 2L); }
            }));
        }
        Pager.fill(m, entries, page, pg -> openMyCommissions(p, pg), "沒有進行中的委託", null);
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    // ======================================================================
    // 社區工程
    // ======================================================================

    public void openProjects(Player p) {
        ProjectService ps = plugin.projects();
        Menu m = new Menu(4, "<gold>社區工程");
        m.header(plugin, p, () -> plugin.openCategory(p, Dialogs.LIFE));
        int slot = 10;
        for (ProjectService.Project pr : ps.all()) {
            if (slot > 16) break;
            List<String> lore = new ArrayList<>();
            if (!pr.description.isEmpty()) lore.add("<gray>" + esc(pr.description));
            lore.add(bar(pr.progress(), 14) + " <white>" + pct(pr.progress()));
            lore.add("<gray>出力的人：<white>" + pr.contrib.size());
            lore.add("");
            lore.add(pr.done ? "<gold>✔ 已完工" : "<yellow>點一下：查看需要什麼");
            m.set(slot++, icon(pr.done ? Material.LIME_CONCRETE : pr.icon, 1, "<gold>" + esc(pr.name), lore),
                    e -> { click(p); openProject(p, pr.id); });
        }
        if (slot == 10) m.set(13, icon(Material.STRUCTURE_VOID, 1, "<gray>目前沒有社區工程", List.of("<dark_gray>服主可以在 config.yml 的 projects 新增")));
        m.set(2, icon(Material.SCAFFOLDING, 1, "<gold>社區工程", List.of(
                "<gray>全服一起蓋的公共建設",
                "<gray>捐物資：每 " + plugin.getConfig().getInt("sources.project.items-per-mana", 32) + " 個換 1 mana（有每日上限）",
                "<gray>投入 mana：讓存著的 mana 變成看得見的建設",
                "<gray>完工時每位出過力的人都有完工獎勵")));
        m.frame(Menu.LIFE);
        m.open(p);
    }

    public void openProject(Player p, String id) {
        PlayerData d = data(p);
        ProjectService.Project pr = plugin.projects().get(id);
        if (d == null || pr == null) return;
        Menu m = new Menu(6, "<gold>" + esc(pr.name));
        m.header(plugin, p, () -> openProjects(p));
        List<String> info = new ArrayList<>();
        if (!pr.description.isEmpty()) info.add("<gray>" + esc(pr.description));
        info.add(bar(pr.progress(), 16) + " <white>" + pct(pr.progress()));
        info.add("");
        info.add("<white>出力最多：");
        for (Object[] c : pr.top(5)) info.add("<gray>・" + esc(String.valueOf(c[2])) + " <dark_gray>物資 " + c[0] + "，mana " + c[1]);
        if (pr.contrib.isEmpty()) info.add("<dark_gray>還沒有人出力，成為第一個吧！");
        m.set(2, icon(pr.icon, 1, "<gold>" + esc(pr.name) + (pr.done ? " <green>✔ 已完工" : ""), info));

        List<Pager.Entry> entries = new ArrayList<>();
        for (ProjectService.Need n : pr.needs.values()) {
            int have = pr.have(n.material());
            int mine = OrderService.countPlain(p, n.material());
            boolean full = have >= n.goal();
            List<String> lore = new ArrayList<>();
            lore.add(bar(have / (double) n.goal(), 12) + " <white>" + have + "/" + n.goal());
            lore.add("<gray>每 " + n.perMana() + " 個換 1 mana");
            lore.add("<gray>你身上有：<white>" + mine);
            lore.add("");
            boolean can = !full && !pr.done && mine > 0;
            lore.add(full ? "<gold>✔ 已湊齊" : pr.done ? "<gray>已完工" : mine > 0 ? "<yellow>點一下：捐出身上全部（捐到湊齊為止）" : "<gray>身上沒有");
            ItemStack it = icon(full ? Material.LIME_STAINED_GLASS_PANE : n.material(), 1, "<white>" + tr(n.material()), lore);
            if (can) Menu.glint(it);
            Material need = n.material();
            entries.add(new Pager.Entry(it, e -> {
                ProjectService.Project cur = plugin.projects().get(id);
                if (cur != null && plugin.projects().donateItems(p, cur, need)) { Menu.success(p); openProject(p, id); } else Menu.fail(p);
            }));
        }
        Pager.fill(m, entries, 0, pg -> {}, "這項工程不需要物資", null);
        if (pr.manaGoal > 0) {
            m.set(46, plugin.icons().mana(1, "<aqua>mana：" + pr.mana + "/" + pr.manaGoal, List.of(bar(pr.mana / (double) Math.max(1, pr.manaGoal), 12))));
            int s = 47;
            for (long a : INVEST_AMOUNTS) {
                boolean ok = d.balance >= a && !pr.done && pr.mana < pr.manaGoal;
                String name = (ok ? "<aqua>" : "<gray>") + "投入 " + a + " mana";
                List<String> lore = List.of(ok ? "<yellow>點一下投入" : pr.done ? "<gray>已完工" : "<red>不能投入");
                m.set(s++, ok ? plugin.icons().mana((int) Math.min(64, a), name, lore) : icon(Material.GRAY_DYE, (int) Math.min(64, a), name, lore),
                        e -> {
                            ProjectService.Project cur = plugin.projects().get(id);
                            if (cur != null && plugin.projects().invest(p, cur, a)) { Menu.success(p); openProject(p, id); } else Menu.fail(p);
                        });
            }
        }
        m.frame(Menu.LIFE);
        m.open(p);
    }
}
