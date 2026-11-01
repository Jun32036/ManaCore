package io.github.jun32036.manacore.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.LifeTree;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.MentorService;
import io.github.jun32036.manacore.Msg;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;
import io.github.jun32036.manacore.economy.CraftService;
import io.github.jun32036.manacore.economy.EconMath;
import io.github.jun32036.manacore.economy.Passives;
import io.github.jun32036.manacore.economy.Skill;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 對話框介面（1.21.6+ 的伺服器對話框）：導覽、說明、表單用真正的按鈕、滑桿與文字輸入；
 * 需要看物品圖示挑選的頁面（祭壇、市集、煉金爐、訂單、委託板、圖鑑、作品）仍用箱子格子，從這裡進入。
 *
 * 層級：主選單（五個分類＋說明）→ 分類頁 → 功能。每一頁都有「返回」回上一層。
 * 舊版客戶端看不到對話框，可用 /mana chest 開箱子版主選單。
 */
public final class Dialogs {

    public static final String SELF = "self", SOCIAL = "social", TRADE = "trade", LIFE = "life", RECORDS = "records";

    private static final int W = 170;   // 按鈕寬度
    private static final int WIDE = 300;

    private final ManaCorePlugin plugin;

    public Dialogs(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("menu.dialogs", true);
    }

    // ---------------- 小工具 ----------------

    private DialogAction act(BiConsumer<Player, DialogResponseView> fn) {
        return DialogAction.customClick((resp, aud) -> {
            if (aud instanceof Player p) plugin.sync(() -> { if (p.isOnline()) fn.accept(p, resp); });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build());
    }

    private ActionButton btn(String label, String tooltip, int width, BiConsumer<Player, DialogResponseView> fn) {
        return ActionButton.builder(Msg.mm(label)).tooltip(tooltip == null ? null : Msg.mm(tooltip)).width(width).action(act(fn)).build();
    }

    private ActionButton back(String label, Runnable to) {
        return btn(label, null, W, (p, r) -> to.run());
    }

    private static DialogBody text(String mm) {
        return DialogBody.plainMessage(Msg.mm(mm), WIDE);
    }

    private static DialogBody item(ItemStack it, String mm) {
        return DialogBody.item(it).description(DialogBody.plainMessage(Msg.mm(mm), WIDE - 40)).showDecorations(false).showTooltip(true).build();
    }

    private void show(Player p, String title, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons, int columns, ActionButton exit) {
        if (buttons.isEmpty()) { notice(p, title, body, exit); return; } // 沒有可選項目時用單一按鈕的通知框（multiAction 不允許沒有按鈕）
        Dialog d = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Msg.mm(title)).canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body).inputs(inputs == null ? List.of() : inputs).build())
                .type(DialogType.multiAction(buttons).columns(columns).exitAction(exit).build()));
        present(p, d);
    }

    private void notice(Player p, String title, List<DialogBody> body, ActionButton ok) {
        Dialog d = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Msg.mm(title)).canCloseWithEscape(true).afterAction(DialogBase.DialogAfterAction.CLOSE).body(body).build())
                .type(DialogType.notice(ok)));
        present(p, d);
    }

    /** 下一個 tick 再顯示：從箱子介面的「返回」點進來時，先關掉箱子（在點擊事件中直接關會出問題） */
    private void present(Player p, Dialog d) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (p.getOpenInventory().getTopInventory().getHolder(false) != null) p.closeInventory();
            p.showDialog(d);
        });
    }

    private PlayerData data(Player p) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
        return d;
    }

    private static String bar(long now, long max, int width) {
        if (max <= 0) return "";
        int filled = (int) Math.min(width, now * width / max);
        return "<green>" + "▮".repeat(filled) + "</green><dark_gray>" + "▮".repeat(width - filled) + "</dark_gray>";
    }

    private DialogBody manaHeader(PlayerData d) {
        long today = 0;
        for (Source s : Source.values()) if (s.earning) today += d.earned(s);
        return item(plugin.icons().mana(1, "<aqua>mana", List.of()),
                "<aqua>你的 mana：<white><bold>" + d.balance + "</bold></white>　<gray>今天已得 <white>" + today + "</white></gray>"
                        + (plugin.mana().isAfk(d) ? "\n<red>你目前 AFK，暫時不會產生 mana</red>" : ""));
    }

    // ======================================================================
    // 主選單
    // ======================================================================

    public void openHub(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        int social = plugin.commissions().unclaimed(p.getUniqueId());
        MentorService.Pair pair = plugin.mentors().of(p.getUniqueId());
        if (pair != null && "pending".equals(pair.status)) social++;
        int trade = plugin.market().returnedCount(p.getUniqueId());
        int deliverable = 0;
        for (var o : plugin.orders().today())
            if (!o.complete() && plugin.craft().level(d, o.def.skill()) >= o.def.level() && plugin.orders().mine(d, o) < o.def.perPlayer()
                    && io.github.jun32036.manacore.economy.OrderService.countPlain(p, o.def.item()) >= o.def.amount()) deliverable++;

        List<DialogBody> body = new ArrayList<>();
        body.add(manaHeader(d));
        body.add(text("<gray>想做什麼？選一個分類。有 <yellow>★</yellow> 的表示有事情等你處理。</gray>"));
        List<ActionButton> b = new ArrayList<>();
        b.add(btn("<green>🌾 靠自己賺" + (deliverable > 0 ? " <yellow>★" + deliverable : ""), "<gray>生活手藝、圖鑑、城鎮訂單</gray>" + (deliverable > 0 ? "\n<yellow>有 " + deliverable + " 張訂單你現在就能交" : ""), W, (pl, r) -> openSelf(pl)));
        b.add(btn("<light_purple>🤝 和大家一起" + (social > 0 ? " <yellow>★" + social : ""), "<gray>感謝、贈與、委託板、作品、導師</gray>" + (social > 0 ? "\n<yellow>有 " + social + " 件事等你處理" : ""), W, (pl, r) -> openSocial(pl)));
        b.add(btn("<aqua>💱 交易與兌換" + (trade > 0 ? " <yellow>★" + trade : ""), "<gray>生命祭壇、玩家市集、煉金爐</gray>" + (trade > 0 ? "\n<yellow>市集有 " + trade + " 批物品退回待領取" : ""), W, (pl, r) -> openTrade(pl)));
        b.add(btn("<dark_green>🌳 生生不息", "<gray>生命之樹、社區工程</gray>", W, (pl, r) -> openLife(pl)));
        b.add(btn("<white>📒 我的紀錄", "<gray>今天的 mana、收支紀錄</gray>", W, (pl, r) -> openRecords(pl)));
        b.add(btn("<yellow>❓ 怎麼得到 mana？", "<gray>新手請先看這個</gray>", W, (pl, r) -> openHelp(pl)));
        show(p, "<dark_green>◆ 生生不息 · mana", body, null, b, 2, btn("<red>關閉", null, W, (pl, r) -> {}));
    }

    public void openCategory(Player p, String cat) {
        switch (cat) {
            case SELF -> openSelf(p);
            case SOCIAL -> openSocial(p);
            case TRADE -> openTrade(p);
            case LIFE -> openLife(p);
            case RECORDS -> openRecords(p);
            default -> openHub(p);
        }
    }

    // ======================================================================
    // 分類頁
    // ======================================================================

    public void openSelf(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        int open = 0;
        for (var o : plugin.orders().today()) if (!o.complete()) open++;
        List<DialogBody> body = List.of(manaHeader(d),
                text("<gray>靠自己的雙手賺 mana：做同一件事越久收益越少，換著玩最划算。</gray>"));
        List<ActionButton> b = List.of(
                btn("<green>生活手藝", "<gray>採集、種植、畜牧、釣魚、烹飪、建造的等級、被動加成與新鮮度", W, (pl, r) -> openSkills(pl)),
                btn("<aqua>圖鑑", "<gray>第一次釣到、種出、做出、繁殖的東西都有 mana（箱子介面）", W, (pl, r) -> plugin.econMenu().openDex(pl)),
                btn("<gold>城鎮訂單 <gray>" + open + " 張", "<gray>城鎮今天需要的東西，交貨換 mana（箱子介面）", W, (pl, r) -> plugin.econMenu().openOrders(pl)),
                btn("<yellow>成就與探索", "<gray>達成原版成就、第一次到新地方也有 mana", W, (pl, r) -> openAchievements(pl)));
        show(p, "<green>◆ mana › 靠自己賺", body, null, b, 2, back("<white>← 返回主選單", () -> openHub(p)));
    }

    public void openSocial(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        int unclaimed = plugin.commissions().unclaimed(p.getUniqueId());
        int boardN = plugin.commissions().board().size();
        int nearby = plugin.places().worksNear(p.getLocation(), plugin.actions().worksRadius(), p.getUniqueId()).size();
        MentorService.Pair pair = plugin.mentors().of(p.getUniqueId());
        boolean invite = pair != null && "pending".equals(pair.status);
        List<DialogBody> body = new ArrayList<>();
        body.add(manaHeader(d));
        body.add(text("<gray>和大家一起玩，收穫更多：感謝不花自己的 mana，由世界替你回報對方。</gray>"));
        List<ActionButton> b = new ArrayList<>();
        b.add(btn("<red>感謝", "<gray>感謝幫過你的人，對方得到 mana", W, (pl, r) -> openThanks(pl)));
        b.add(btn("<aqua>贈與 mana", "<gray>把自己的 mana 送給別人", W, (pl, r) -> openGift(pl)));
        b.add(btn("<aqua>委託板 <gray>" + boardN + " 張" + (unclaimed > 0 ? " <yellow>★" + unclaimed : ""), "<gray>求助與幫忙（箱子介面）" + (unclaimed > 0 ? "\n<yellow>有 " + unclaimed + " 張完成的委託物品待領取" : ""), W, (pl, r) -> plugin.econMenu().openBoard(pl, 0)));
        b.add(btn("<aqua>發布服務委託", "<gray>請人幫忙做一件事，例如蓋屋頂、陪你去下界", W, (pl, r) -> openServiceForm(pl)));
        b.add(btn("<gold>我的作品", "<gray>登記作品，別人來參觀留言時你會得到 mana（箱子介面）", W, (pl, r) -> plugin.menu().openWorks(pl)));
        b.add(btn("<aqua>參觀附近作品" + (nearby > 0 ? " <yellow>★" + nearby : ""), "<gray>給附近的作品留言，作者會得到 mana（箱子介面）", W, (pl, r) -> plugin.menu().openVisit(pl)));
        b.add(btn("<white>收到的留言", "<gray>看看誰來參觀過你的作品（箱子介面）", W, (pl, r) -> plugin.menu().openComments(pl, 0)));
        b.add(btn("<gold>導師" + (invite ? " <yellow>★" : ""), invite ? "<yellow>有人想當你的導師！" : "<gray>帶新手完成引導，你們都會得到 mana", W, (pl, r) -> openMentor(pl)));
        show(p, "<light_purple>◆ mana › 和大家一起", body, null, b, 2, back("<white>← 返回主選單", () -> openHub(p)));
    }

    public void openTrade(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        int lots = plugin.market().open().size(), returned = plugin.market().returnedCount(p.getUniqueId());
        boolean remote = p.hasPermission("manacore.altar.remote");
        List<DialogBody> body = List.of(manaHeader(d),
                text("<gray>祭壇用 mana 換物資（公定價）；市集向其他玩家買賣；煉金爐把多餘的物品換成等值的其他物品。</gray>"));
        List<ActionButton> b = List.of(
                btn("<aqua>生命祭壇", remote ? "<gray>用 mana 照公定價換物資，一組一組換（箱子介面）" : "<gray>要走到祭壇方塊旁右鍵開啟", W,
                        (pl, r) -> { if (remote) plugin.altar().open(pl); else notice(pl, "<aqua>生命祭壇", List.of(text("<gray>生命祭壇要親自走到祭壇方塊旁，<white>右鍵</white>開啟。</gray>")), back("<white>← 返回", () -> openTrade(pl))); }),
                btn("<aqua>玩家市集 <gray>" + lots + " 批" + (returned > 0 ? " <yellow>★" + returned : ""), "<gray>公定價、不講價（箱子介面）" + (returned > 0 ? "\n<yellow>有 " + returned + " 批退回的物品待領取" : ""), W, (pl, r) -> plugin.marketMenu().open(pl, 0)),
                btn("<light_purple>煉金爐 · 等價交換", "<gray>煉金值：<white>" + d.alchemy + "</white>（箱子介面）", W, (pl, r) -> plugin.alchemyMenu().open(pl, 0)),
                btn("<white>價格怎麼算？", "<gray>價值表與公定價的說明", W, (pl, r) -> openPriceHelp(pl)));
        show(p, "<aqua>◆ mana › 交易與兌換", body, null, b, 2, back("<white>← 返回主選單", () -> openHub(p)));
    }

    public void openLife(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        LifeTree t = plugin.tree();
        LifeTree.Stage cur = t.currentStage(), next = t.nextStage();
        long threshold = plugin.getConfig().getLong("decay.threshold", 500);
        List<DialogBody> body = List.of(manaHeader(d),
                item(Menu.icon(Material.FLOWERING_AZALEA, "<green>生命之樹"), "<green>生命之樹</green> <gray>階段：<white>" + (cur == null ? "種子" : cur.name()) + "</white>\n"
                        + (next != null ? bar(t.total(), next.threshold(), 16) + " <gray>" + t.total() + " / " + next.threshold() + "（下一階段：" + next.name() + "）" : "<gold>所有祝福都已解鎖")),
                text("<gray>mana 存超過 <white>" + threshold + "</white> 的部分每天歸土 " + Math.round(plugin.getConfig().getDouble("decay.rate", 0.05) * 100) + "% 回生命之樹；捐獻、手續費也會讓它成長。</gray>"));
        List<ActionButton> b = List.of(
                btn("<green>捐獻給生命之樹", "<gray>選數量捐出", W, (pl, r) -> openDonate(pl)),
                btn("<gold>社區工程", "<gray>全服一起蓋的公共建設：捐物資、投入 mana（箱子介面）", W, (pl, r) -> plugin.econMenu().openProjects(pl)));
        show(p, "<dark_green>◆ mana › 生生不息", body, null, b, 2, back("<white>← 返回主選單", () -> openHub(p)));
    }

    public void openRecords(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        StringBuilder sb = new StringBuilder("<white>今天的 mana</white>\n");
        boolean any = false;
        for (Source s : Source.values()) {
            if (!s.earning) continue;
            long cap = plugin.mana().cap(s), got = d.earned(s);
            if (cap <= 0 && got == 0) continue;
            any = true;
            sb.append("<gray>").append(s.display).append("　<white>").append(got).append(cap > 0 ? "</white><dark_gray>/" + cap + "</dark_gray> " + bar(got, cap, 10) : "</white>").append("\n");
        }
        if (!any) sb.append("<gray>今天還沒有收入，去做點什麼吧！</gray>\n");
        sb.append("<dark_gray>每天午夜重置</dark_gray>");
        List<DialogBody> body = List.of(manaHeader(d), text(sb.toString()));
        List<ActionButton> b = List.of(
                btn("<white>收支紀錄", "<gray>每一筆 mana 的來源與去處（箱子介面）", W, (pl, r) -> plugin.menu().openLog(pl, 0)),
                btn("<green>手藝等級", "<gray>六種生活手藝的等級與被動", W, (pl, r) -> openSkills(pl)));
        show(p, "<white>◆ mana › 我的紀錄", body, null, b, 2, back("<white>← 返回主選單", () -> openHub(p)));
    }

    // ======================================================================
    // 說明頁
    // ======================================================================

    public void openHelp(Player p) {
        List<DialogBody> body = List.of(
                text("<white>靠自己的雙手</white>\n<gray>・生活手藝：採集、種植、畜牧、釣魚、烹飪、建造\n・圖鑑：第一次做到某件事\n・城鎮訂單：交出城鎮今天需要的東西\n・探索：第一次到新的生態域、結構、地標\n・成就：達成原版成就（挑戰類最多）</gray>"),
                text("<white>和大家一起</white>\n<gray>・聊天被接話、被感謝、帶新手、作品被參觀\n・委託板：幫別人完成委託\n・合作挑戰：一起打首領、守住突襲（人越多越多）\n・社區工程：捐物資、一起完工</gray>"),
                text("<dark_gray>做同一件事越久收益越少，換著玩最划算。mana 存超過 " + plugin.getConfig().getLong("decay.threshold", 500) + " 會慢慢歸土，拿來用吧。\n小技巧：蹲下按 F 隨時打開這個選單。</dark_gray>"));
        notice(p, "<yellow>怎麼得到 mana？", body, back("<white>← 返回主選單", () -> openHub(p)));
    }

    public void openAchievements(Player p) {
        var cfg = plugin.getConfig();
        List<DialogBody> body = List.of(
                text("<white>成就</white>\n<gray>達成原版成就就有 mana：一般 <white>" + cfg.getLong("sources.advancement.task") + "</white>、目標 <white>" + cfg.getLong("sources.advancement.goal") + "</white>、挑戰 <white>" + cfg.getLong("sources.advancement.challenge") + "</white>；重要成就更多（例如擊敗終界龍 " + cfg.getLong("sources.advancement.overrides.minecraft:end/kill_dragon") + "）。成就只能達成一次。</gray>"),
                text("<white>探索</white>\n<gray>第一次踏入新的生態域 <white>" + cfg.getLong("sources.explore.biome") + "</white>、結構 <white>" + cfg.getLong("sources.explore.structure") + "</white>、管理員設定的地標 <white>" + cfg.getLong("sources.explore.landmark") + "</white>。</gray>"));
        notice(p, "<yellow>成就與探索", body, back("<white>← 返回", () -> openSelf(p)));
    }

    public void openPriceHelp(Player p) {
        List<DialogBody> body = List.of(
                text("<gray>全服有一張<white>價值表</white>：基礎物資的價值由服主設定，其他物品從合成、燒製、切石配方自動推算，取最便宜的做法。</gray>"),
                text("<gray><white>市集</white>的公定價 = 價值 × 數量 ÷ 100，不能自訂，所以不講價、不比價。\n<white>煉金爐</white>用同一張表：投入物品換煉金值（損耗 " + plugin.alchemy().lossPercent() + "%），換出曾投入過的物品。煉金值不能換成 mana。\n<white>祭壇</white>的價格另外設定，建造等級高的人買建材有折扣。</gray>"));
        notice(p, "<white>價格怎麼算？", body, back("<white>← 返回", () -> openTrade(p)));
    }

    // ======================================================================
    // 生活手藝（資訊頁）
    // ======================================================================

    public void openSkills(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        CraftService cs = plugin.craft();
        Passives pv = plugin.passives();
        List<DialogBody> body = new ArrayList<>();
        int companions = plugin.coop().companions(p);
        body.add(text("<gray>點數 = 基礎 × 多樣性 × 結伴 × 新鮮度，每 " + (long) cs.pointsPerMana() + " 點換 1 mana；單一手藝每天最多 " + cs.perSkillCap() + "，合計 " + plugin.mana().cap(Source.CRAFT) + "。"
                + (companions > 0 ? "<green>身邊有 " + companions + " 位夥伴，加成中。</green>" : "") + "</gray>"));
        for (Skill s : Skill.values()) {
            int lv = cs.level(d, s);
            double xp = d.xp(s), next = EconMath.nextLevelXp(lv, cs.levels());
            double prev = cs.levels().get(Math.min(lv - 1, cs.levels().size() - 1)).doubleValue();
            double fresh = cs.freshness(p.getUniqueId(), s);
            String desc = "<gold>" + s.display + "</gold> <white>Lv" + lv + "「" + cs.title(lv) + "」</white>　<gray>今天 " + d.skillEarned(s) + "/" + cs.perSkillCap() + "</gray>\n"
                    + (next > 0 ? bar((long) (xp - prev), (long) (next - prev), 12) + " <gray>" + (long) xp + "/" + (long) next + "</gray>" : "<gold>已達最高等級</gold>")
                    + "\n<gray>新鮮度 " + (fresh >= 0.7 ? "<green>" : fresh >= 0.45 ? "<yellow>" : "<red>") + Math.round(fresh * 100) + "%</gray>"
                    + (pv.enabled() ? "<gray>　被動：" + Passives.describe(s) + " <white>" + Math.round(pv.bonusAt(s, lv) * 100) + "%</white></gray>" : "");
            body.add(item(Menu.icon(s.icon, Math.max(1, lv), "<gold>" + s.display, List.of()), desc));
        }
        notice(p, "<green>◆ mana › 靠自己賺 › 生活手藝", body, back("<white>← 返回", () -> openSelf(p)));
    }

    // ======================================================================
    // 感謝 / 贈與：選人 → 表單
    // ======================================================================

    private List<ActionButton> playerButtons(Player p, java.util.function.Predicate<Player> filter, BiConsumer<Player, Player> onPick) {
        List<ActionButton> b = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(p) || !filter.test(t)) continue;
            b.add(btn("<white>" + t.getName(), null, W, (pl, r) -> { if (t.isOnline()) onPick.accept(pl, t); else plugin.msg().send(pl, "no-player", "name", t.getName()); }));
        }
        return b;
    }

    public void openThanks(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        int max = plugin.actions().thanksPerDay();
        int left = max <= 0 ? 99 : Math.max(0, max - d.thankedToday.size());
        List<ActionButton> b = playerButtons(p, t -> !d.thankedToday.contains(t.getUniqueId()), this::openThanksForm);
        List<DialogBody> body = List.of(text("<gray>感謝幫過你的人，對方得到 <white>" + plugin.getConfig().getLong("sources.thanks.amount", 5) + "</white> mana，不會花你自己的。今天還能感謝 <white>" + (max <= 0 ? "不限" : left + " 人") + "</white>。</gray>"
                + (b.isEmpty() ? "\n<dark_gray>目前沒有可以感謝的玩家在線（或今天都謝過了）。</dark_gray>" : "")));
        show(p, "<red>◆ mana › 和大家一起 › 感謝誰？", body, null, b, 3, back("<white>← 返回", () -> openSocial(p)));
    }

    private void openThanksForm(Player p, Player t) {
        List<DialogInput> inputs = List.of(DialogInput.text("reason", Msg.mm("<gray>想對 " + t.getName() + " 說什麼？（可留空）")).maxLength(60).width(WIDE).build());
        List<ActionButton> b = List.of(btn("<green>送出感謝", null, W, (pl, r) -> {
            String reason = r.getText("reason");
            if (plugin.actions().thank(pl, t, reason == null ? "" : reason)) Menu.success(pl); else Menu.fail(pl);
            openSocial(pl);
        }));
        show(p, "<red>感謝 " + t.getName(), List.of(text("<gray>對方會收到你的感謝和 mana。</gray>")), inputs, b, 1, back("<white>← 返回", () -> openThanks(p)));
    }

    public void openGift(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        List<ActionButton> b = playerButtons(p, t -> true, this::openGiftForm);
        List<DialogBody> body = List.of(text("<gray>把自己的 mana 送給別人。你有 <white>" + d.balance + "</white> mana。</gray>" + (b.isEmpty() ? "\n<dark_gray>目前沒有其他玩家在線。</dark_gray>" : "")));
        show(p, "<aqua>◆ mana › 和大家一起 › 送給誰？", body, null, b, 3, back("<white>← 返回", () -> openSocial(p)));
    }

    private void openGiftForm(Player p, Player t) {
        PlayerData d = data(p);
        if (d == null) return;
        long max = Math.max(1, Math.min(d.balance, 500));
        if (d.balance <= 0) { plugin.msg().raw(p, "<gray>你現在沒有 mana 可以送。</gray>"); openSocial(p); return; }
        List<DialogInput> inputs = List.of(
                DialogInput.numberRange("amount", Msg.mm("<white>數量"), 1, max).step(1f).initial((float) Math.min(10, max)).width(WIDE).build(),
                DialogInput.text("note", Msg.mm("<gray>附上一句話（可留空）")).maxLength(40).width(WIDE).build());
        List<ActionButton> b = List.of(btn("<aqua>送出", null, W, (pl, r) -> {
            Float a = r.getFloat("amount");
            String note = r.getText("note");
            if (a != null && plugin.actions().gift(pl, t, Math.round(a), note == null ? "" : note)) Menu.success(pl); else Menu.fail(pl);
            openSocial(pl);
        }));
        show(p, "<aqua>送 mana 給 " + t.getName(), List.of(text("<gray>拉動滑桿選數量（最多一次 " + max + "）。</gray>")), inputs, b, 1, back("<white>← 返回", () -> openGift(p)));
    }

    // ======================================================================
    // 捐獻
    // ======================================================================

    public void openDonate(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        if (d.balance <= 0) { plugin.msg().raw(p, "<gray>你現在沒有 mana 可以捐。</gray>"); openLife(p); return; }
        long max = d.balance;
        List<DialogInput> inputs = List.of(DialogInput.numberRange("amount", Msg.mm("<white>捐獻數量"), 1, max).step(1f).initial((float) Math.min(50, max)).width(WIDE).build());
        List<ActionButton> b = List.of(
                btn("<green>捐出", null, W, (pl, r) -> { Float a = r.getFloat("amount"); if (a != null && plugin.actions().donate(pl, Math.round(a))) Menu.success(pl); openLife(pl); }),
                btn("<dark_green>全部捐出（" + max + "）", "<gray>把所有 mana 獻給生命之樹", W, (pl, r) -> { if (plugin.actions().donate(pl, max)) Menu.success(pl); openLife(pl); }));
        show(p, "<green>捐獻給生命之樹", List.of(text("<gray>養分會讓全服一起解鎖祝福。</gray>")), inputs, b, 2, back("<white>← 返回", () -> openLife(p)));
    }

    // ======================================================================
    // 服務委託表單
    // ======================================================================

    public void openServiceForm(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        var cs = plugin.commissions();
        List<SingleOptionDialogInput.OptionEntry> tiers = new ArrayList<>();
        boolean first = true;
        for (long t : cs.rewardTiers()) {
            tiers.add(SingleOptionDialogInput.OptionEntry.create(String.valueOf(t), Msg.mm("<aqua>" + t + " mana</aqua> <gray>（手續費 " + cs.fee(t) + "）"), first));
            first = false;
        }
        List<DialogInput> inputs = List.of(
                DialogInput.text("note", Msg.mm("<white>需要什麼幫忙？")).maxLength(80).width(WIDE).build(),
                DialogInput.singleOption("reward", Msg.mm("<white>酬謝"), tiers).width(WIDE).build());
        List<ActionButton> b = List.of(btn("<aqua>發布", "<gray>酬謝先託管，完成才交給幫忙的人", W, (pl, r) -> {
            String note = r.getText("note");
            String reward = r.getText("reward");
            long rw = 0;
            try { rw = Long.parseLong(reward == null ? "0" : reward); } catch (NumberFormatException ignored) { }
            if (cs.postService(pl, rw, note == null ? "" : note)) Menu.success(pl); else Menu.fail(pl);
            openSocial(pl);
        }));
        show(p, "<aqua>發布服務委託", List.of(text("<gray>例如「幫我蓋屋頂」「陪我去下界」。酬謝只能從固定級距選（不講價）。你有 <white>" + d.balance + "</white> mana。</gray>")),
                inputs, b, 1, back("<white>← 返回", () -> openSocial(p)));
    }

    // ======================================================================
    // 導師
    // ======================================================================

    public void openMentor(Player p) {
        MentorService ms = plugin.mentors();
        MentorService.Pair mine = ms.of(p.getUniqueId());
        List<DialogBody> body = new ArrayList<>();
        List<ActionButton> b = new ArrayList<>();
        if (mine != null && "pending".equals(mine.status)) {
            String mentor = String.valueOf(Bukkit.getOfflinePlayer(mine.mentor).getName());
            body.add(text("<white>" + mentor + "</white> <gray>想當你的導師。完成「第一次兌換、作品第一次被留言、遊玩滿 " + plugin.getConfig().getInt("sources.mentor.required-playtime-minutes", 60) + " 分鐘」後，你們都會得到 mana。</gray>"));
            b.add(btn("<green>接受邀請", null, W, (pl, r) -> {
                String err = ms.accept(pl);
                if (err != null) plugin.msg().raw(pl, "<gray><e></gray>", "e", err);
                else {
                    plugin.msg().raw(pl, "<green>你接受了導師的邀請！</green>");
                    Player mp = Bukkit.getPlayer(mine.mentor);
                    if (mp != null) plugin.msg().raw(mp, "<green><n> 接受了你的導師邀請。</green>", "n", pl.getName());
                }
                openMentor(pl);
            }));
        } else if (mine != null && "active".equals(mine.status)) {
            body.add(text("<gray>導師：<white>" + Bukkit.getOfflinePlayer(mine.mentor).getName() + "</white>\n" + ms.progress(mine, p).replace("　", "\n") + "</gray>"));
        } else {
            body.add(text("<gray>帶新手完成引導，你們都會得到 mana。邀請在線的新手：</gray>" + (mine != null ? "\n<dark_gray>你自己已完成新手引導。</dark_gray>" : "")));
            for (MentorService.Pair x : ms.menteesOf(p.getUniqueId())) {
                String st = switch (x.status) { case "pending" -> "等待接受"; case "active" -> "引導中"; default -> "已完成"; };
                body.add(text("<white>" + Bukkit.getOfflinePlayer(x.newbie).getName() + "</white> <gray>" + st + "</gray>"));
            }
            b.addAll(playerButtons(p, ms::canBeInvited, (me, t) -> {
                String err = ms.request(me, t);
                if (err != null) plugin.msg().raw(me, "<gray><e></gray>", "e", err);
                else {
                    plugin.msg().raw(me, "<green>已邀請 <n>，等對方接受。</green>", "n", t.getName());
                    plugin.msg().raw(t, "<green><n> 想當你的導師！蹲下按 F 打開選單 → 和大家一起 → 導師 接受。</green>", "n", me.getName());
                }
                openMentor(me);
            }));
            if (b.isEmpty()) body.add(text("<dark_gray>目前沒有可以邀請的新手在線。</dark_gray>"));
        }
        show(p, "<gold>◆ mana › 和大家一起 › 導師", body, null, b, 3, back("<white>← 返回", () -> openSocial(p)));
    }
}
