package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.Actions;
import io.github.jun32036.manacore.LifeTree;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.MentorService;
import io.github.jun32036.manacore.Places;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;
import io.github.jun32036.manacore.economy.DexService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static io.github.jun32036.manacore.gui.Menu.click;
import static io.github.jun32036.manacore.gui.Menu.head;
import static io.github.jun32036.manacore.gui.Menu.icon;

/** /mana 的圖形介面。所有動作都交給 Actions，規則與指令版一致。 */
public final class ManaMenu {

    private static final long[] GIFT_AMOUNTS = {1, 5, 10, 50, 100};
    private static final long[] DONATE_AMOUNTS = {10, 50, 100, 500};
    private static final List<String> DEFAULT_PRESETS = List.of(
            "好漂亮！", "好有創意", "感覺好溫馨", "細節做得好用心", "下次還想再來", "我也想蓋一個");

    private final ManaCorePlugin plugin;

    public ManaMenu(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    private Actions act() {
        return plugin.actions();
    }

    private static String esc(String s) {
        return MiniMessage.miniMessage().escapeTags(s == null ? "" : s);
    }

    static String bar(long now, long max, int width) {
        if (max <= 0) return "";
        int filled = (int) Math.min(width, now * width / max);
        return "<green>" + "▮".repeat(filled) + "</green><dark_gray>" + "▮".repeat(width - filled) + "</dark_gray>";
    }

    private PlayerData data(Player p) {
        PlayerData d = plugin.mana().get(p);
        if (d == null) plugin.msg().raw(p, "<gray>資料還在載入，請稍後再試。</gray>");
        return d;
    }

    // ======================================================================
    // 主選單：三列分區
    // ======================================================================

    public void openMain(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        Menu m = new Menu(5, "<dark_green>◆ 生生不息 · mana");
        EconomyMenu em = plugin.econMenu();
        m.header(plugin, p, null);

        // 標題列左右：今天的 mana、說明
        List<String> today = new ArrayList<>();
        for (Source s : Source.values()) {
            if (!s.earning) continue;
            long cap = plugin.mana().cap(s), got = d.earned(s);
            if (cap <= 0 && got == 0) continue;
            today.add("<white>" + s.display + " <gray>" + got + (cap > 0 ? "/" + cap + " " + bar(got, cap, 10) : ""));
        }
        if (today.isEmpty()) today.add("<gray>今天還沒有收入，去做點什麼吧！");
        today.add("");
        today.add("<dark_gray>每天午夜重置");
        int companions = plugin.coop().companions(p);
        if (companions > 0) today.add("<green>身邊有 " + companions + " 位夥伴：生活手藝加成中");
        if (plugin.mana().isAfk(d)) today.add("<red>你目前 AFK，暫時不會產生 mana");
        m.set(2, icon(Material.CLOCK, 1, "<gold>今天的 mana", today));
        m.set(6, icon(Material.KNOWLEDGE_BOOK, 1, "<green>怎麼得到 mana？", List.of(
                "<white>靠自己的雙手",
                "<gray>・生活手藝：採集、種植、畜牧、釣魚、烹飪、建造",
                "<gray>・圖鑑：第一次做到某件事",
                "<gray>・城鎮訂單：交出城鎮今天需要的東西",
                "<gray>・探索：第一次到新的生態域、結構、地標",
                "<gray>・成就：達成原版成就（挑戰類最多）",
                "<white>和大家一起",
                "<gray>・聊天被接話、被感謝、帶新手、作品被參觀",
                "<gray>・委託板：幫別人完成委託",
                "<gray>・合作挑戰：一起打首領、守住突襲（人越多越多）",
                "<gray>・社區工程：捐物資、一起完工",
                "",
                "<dark_gray>做同一件事越久收益越少，換著玩最划算",
                "<dark_gray>mana 存超過 " + plugin.getConfig().getLong("decay.threshold", 500) + " 會慢慢歸土，拿來用吧")));

        // ---- 第一列：靠自己的雙手 ----
        m.label(9, Menu.SELF, "<green>靠自己的雙手");
        m.label(17, Menu.SELF, "<green>靠自己的雙手");
        m.set(10, icon(Material.IRON_HOE, 1, "<green>生活手藝",
                List.of("<gray>採集、種植、畜牧、釣魚、烹飪、建造", "<gray>等級、被動加成、新鮮度", "", "<yellow>點一下查看")),
                e -> { click(p); em.openSkills(p); });
        int dexTotal = 0;
        for (DexService.Category c : DexService.Category.values()) dexTotal += plugin.dex().count(d, c);
        m.set(11, icon(Material.BOOK, 1, "<aqua>圖鑑",
                List.of("<gray>第一次釣到、種出、做出、繁殖的東西都有 mana", "<gray>已登錄 <white>" + dexTotal + "</white> 種", "", "<yellow>點一下查看")),
                e -> { click(p); em.openDex(p); });
        int open = 0;
        for (var o : plugin.orders().today()) if (!o.complete()) open++;
        m.set(12, icon(Material.BELL, 1, "<gold>城鎮訂單",
                List.of("<gray>城鎮今天需要的東西，交貨換 mana", "<gray>還有 <white>" + open + "</white> 張訂單等人完成", "", "<yellow>點一下查看")),
                e -> { click(p); em.openOrders(p); });
        if (plugin.alchemy().enabled())
            m.set(13, icon(Material.BREWING_STAND, 1, "<light_purple>煉金爐 · 等價交換",
                    List.of("<gray>多餘的物品換成煉金值，再換出等值的物品", "<gray>煉金值：<white>" + d.alchemy, "", "<yellow>點一下開啟")),
                    e -> { click(p); plugin.alchemyMenu().open(p, 0); });
        boolean remote = p.hasPermission("manacore.altar.remote");
        m.set(14, icon(Material.LODESTONE, 1, "<aqua>生命祭壇",
                List.of("<gray>用 mana 照公定價換物資，一組一組換", "",
                        remote ? "<yellow>點一下：開啟兌換介面" : "<gray>請到祭壇方塊旁<white>右鍵</white>開啟")),
                e -> {
                    click(p);
                    if (remote) plugin.altar().open(p);
                    else { p.closeInventory(); plugin.msg().raw(p, "<gray>生命祭壇要親自走到祭壇方塊旁，右鍵開啟。</gray>"); }
                });
        if (plugin.market().enabled()) {
            int lots = plugin.market().open().size(), returned = plugin.market().returnedCount(p.getUniqueId());
            ItemStack mk = icon(Material.EMERALD_BLOCK, 1, "<aqua>玩家市集",
                    List.of("<gray>向其他玩家買東西、把多餘的賣掉", "<gray>公定價、不講價", "<gray>目前有 <white>" + lots + "</white> 批商品",
                            returned > 0 ? "<yellow>有 " + returned + " 批退回的物品待領取！" : "", "<yellow>點一下查看"));
            if (returned > 0) Menu.glint(mk);
            m.set(15, mk, e -> { click(p); plugin.marketMenu().open(p, 0); });
        }

        // ---- 第二列：和大家一起 ----
        m.label(18, Menu.SOCIAL, "<light_purple>和大家一起");
        m.label(26, Menu.SOCIAL, "<light_purple>和大家一起");
        int maxThanks = act().thanksPerDay();
        int left = maxThanks <= 0 ? 99 : Math.max(0, maxThanks - d.thankedToday.size());
        m.set(19, icon(Material.POPPY, 1, "<red>感謝",
                List.of("<gray>感謝幫過你的人，對方得到 mana", "<gray>不會花你自己的 mana",
                        "<gray>今天還能感謝 <white>" + (maxThanks <= 0 ? "不限" : left + " 人"), "", "<yellow>點一下：選擇玩家")),
                e -> { click(p); openThanks(p); });
        m.set(20, plugin.icons().mana(1, "<aqua>贈與 mana", List.of("<gray>把自己的 mana 送給別人", "", "<yellow>點一下：選擇玩家")),
                e -> { click(p); openGift(p); });
        int boardN = plugin.commissions().board().size(), unclaimed = plugin.commissions().unclaimed(p.getUniqueId());
        ItemStack board = icon(Material.OAK_HANGING_SIGN, 1, "<aqua>委託板",
                List.of("<gray>需要幫忙？想幫別人？", "<gray>目前有 <white>" + boardN + "</white> 張委託",
                        unclaimed > 0 ? "<yellow>有 " + unclaimed + " 張委託物品待領取！" : "", "<yellow>點一下查看"));
        if (unclaimed > 0) Menu.glint(board);
        m.set(21, board, e -> { click(p); em.openBoard(p, 0); });
        List<Places.Work> mine = plugin.places().worksOf(p.getUniqueId());
        m.set(22, icon(Material.PAINTING, 1, "<gold>我的作品",
                List.of("<gray>登記作品，別人來參觀留言時你會得到 mana", "<gray>已登記 <white>" + mine.size() + "/" + act().maxWorks(), "", "<yellow>點一下：管理作品")),
                e -> { click(p); openWorks(p); });
        int nearby = plugin.places().worksNear(p.getLocation(), act().worksRadius(), p.getUniqueId()).size();
        ItemStack visit = icon(Material.SPYGLASS, 1, "<aqua>參觀附近作品",
                List.of("<gray>給附近的作品留言，作者會得到 mana", "<gray>附近 " + (int) act().worksRadius() + " 格內有 <white>" + nearby + "</white> 件作品", "", "<yellow>點一下：選擇作品"));
        if (nearby > 0) Menu.glint(visit);
        m.set(23, visit, e -> { click(p); openVisit(p); });
        m.set(24, icon(Material.BOOK, 1, "<white>收到的留言", List.of("<gray>讀取中…")), e -> { click(p); openComments(p, 0); });
        act().unseenComments(p.getUniqueId()).thenAccept(n -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            ItemStack ic = icon(n > 0 ? Material.ENCHANTED_BOOK : Material.BOOK, Math.max(1, n), "<white>收到的留言",
                    List.of(n > 0 ? "<yellow>有 " + n + " 則新留言！" : "<gray>沒有新留言", "", "<yellow>點一下查看"));
            m.set(24, ic, e -> { click(p); openComments(p, 0); });
        }));
        mentorButton(m, p, 25);

        // ---- 第三列：生生不息 ----
        m.label(27, Menu.LIFE, "<aqua>生生不息");
        m.label(35, Menu.LIFE, "<aqua>生生不息");
        LifeTree t = plugin.tree();
        LifeTree.Stage cur = t.currentStage(), next = t.nextStage();
        List<String> treeLore = new ArrayList<>();
        treeLore.add("<gray>階段：<white>" + (cur == null ? "種子" : cur.name()));
        if (next != null) {
            treeLore.add(bar(t.total(), next.threshold(), 16));
            treeLore.add("<gray>" + t.total() + " / " + next.threshold() + "（下一階段：" + next.name() + "）");
        } else treeLore.add("<gold>所有祝福都已解鎖");
        treeLore.add("");
        treeLore.add("<yellow>點一下：捐獻 mana");
        m.set(28, icon(Material.FLOWERING_AZALEA, 1, "<green>生命之樹", treeLore), e -> { click(p); openTree(p); });
        m.set(29, icon(Material.SCAFFOLDING, 1, "<gold>社區工程",
                List.of("<gray>全服一起蓋的公共建設", "<gray>捐物資、投入 mana，完工一起慶祝", "", "<yellow>點一下查看")),
                e -> { click(p); em.openProjects(p); });
        m.set(30, icon(Material.WRITABLE_BOOK, 1, "<white>mana 紀錄", List.of("<gray>所有收支", "", "<yellow>點一下查看")),
                e -> { click(p); openLog(p, 0); });

        m.set(40, icon(Material.BARRIER, "<red>關閉"), e -> p.closeInventory());
        m.border();
        m.open(p);
    }

    private void mentorButton(Menu m, Player p, int slot) {
        MentorService ms = plugin.mentors();
        MentorService.Pair mine = ms.of(p.getUniqueId());
        if (mine != null && "pending".equals(mine.status)) {
            String mentor = String.valueOf(Bukkit.getOfflinePlayer(mine.mentor).getName());
            m.set(slot, Menu.glint(icon(Material.NAME_TAG, 1, "<gold>導師邀請",
                    List.of("<white>" + mentor + "<gray> 想當你的導師", "<gray>完成引導後你們都會得到 mana", "", "<yellow>點一下：接受"))),
                    e -> {
                        click(p);
                        String err = ms.accept(p);
                        if (err != null) plugin.msg().raw(p, "<gray><e></gray>", "e", err);
                        else {
                            plugin.msg().raw(p, "<green>你接受了導師的邀請！</green>");
                            Player mp = Bukkit.getPlayer(mine.mentor);
                            if (mp != null) plugin.msg().raw(mp, "<green><n> 接受了你的導師邀請。</green>", "n", p.getName());
                        }
                        openMain(p);
                    });
            return;
        }
        if (mine != null && "active".equals(mine.status)) {
            List<String> lore = new ArrayList<>();
            lore.add("<gray>導師：<white>" + Bukkit.getOfflinePlayer(mine.mentor).getName());
            for (String s : ms.progress(mine, p).split("　")) lore.add("<gray>" + s);
            m.set(slot, icon(Material.NAME_TAG, 1, "<gold>新手引導進度", lore));
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>帶新手完成引導，你們都會得到 mana");
        List<MentorService.Pair> mentees = ms.menteesOf(p.getUniqueId());
        for (MentorService.Pair x : mentees) {
            String n = String.valueOf(Bukkit.getOfflinePlayer(x.newbie).getName());
            String st = switch (x.status) { case "pending" -> "等待接受"; case "active" -> "引導中"; default -> "已完成"; };
            lore.add("<white>" + n + " <gray>" + st);
        }
        if (mine != null) lore.add("<dark_gray>你自己已完成新手引導");
        lore.add("");
        lore.add("<yellow>點一下：邀請在線新手");
        m.set(slot, icon(Material.NAME_TAG, 1, "<gold>導師", lore), e -> { click(p); openMentorPick(p); });
    }

    // ======================================================================
    // 選玩家（感謝、贈與、導師共用）
    // ======================================================================

    private void openPlayerPicker(Player p, String title, Predicate<Player> filter, java.util.function.Function<Player, List<String>> loreFn,
                                  BiConsumer<Player, Player> onPick, String empty) {
        Menu m = new Menu(6, title);
        m.header(plugin, p, () -> openMain(p));
        List<Pager.Entry> entries = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(p) || !filter.test(t)) continue;
            entries.add(new Pager.Entry(head(t, "<white>" + t.getName(), loreFn.apply(t)), e -> { click(p); onPick.accept(p, t); }));
        }
        Pager.fill(m, entries, 0, pg -> {}, empty, null);
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    public void openThanks(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        openPlayerPicker(p, "<dark_red>感謝誰？",
                t -> true,
                t -> d.thankedToday.contains(t.getUniqueId())
                        ? List.of("<gray>今天已經感謝過了")
                        : List.of("<yellow>點一下：感謝 " + t.getName(), "<gray>對方得到 " + plugin.getConfig().getLong("sources.thanks.amount", 5) + " mana"),
                (me, t) -> { if (act().thank(me, t, "")) Menu.success(me); openThanks(me); },
                "目前沒有其他玩家在線");
    }

    public void openGift(Player p) {
        openPlayerPicker(p, "<dark_purple>送 mana 給誰？", t -> true,
                t -> List.of("<yellow>點一下：選擇數量"),
                (me, t) -> openGiftAmount(me, t),
                "目前沒有其他玩家在線");
    }

    private void openGiftAmount(Player p, Player t) {
        PlayerData d = data(p);
        if (d == null) return;
        Menu m = new Menu(3, "<dark_purple>送給 " + t.getName());
        m.header(plugin, p, () -> openGift(p));
        m.set(2, head(t, "<white>" + t.getName(), List.of("<gray>選擇要送的數量")));
        int slot = 11;
        for (long a : GIFT_AMOUNTS) {
            boolean ok = d.balance >= a;
            String name = (ok ? "<aqua>" : "<gray>") + "送 " + a + " mana";
            List<String> lore = List.of(ok ? "<yellow>點一下送出" : "<red>mana 不夠");
            m.set(slot++, ok ? plugin.icons().mana((int) Math.min(a, 64), name, lore) : icon(Material.GRAY_DYE, (int) Math.min(a, 64), name, lore),
                    e -> {
                        if (!t.isOnline()) { plugin.msg().send(p, "no-player", "name", t.getName()); openGift(p); return; }
                        if (act().gift(p, t, a, "")) Menu.success(p); else Menu.fail(p);
                        openGiftAmount(p, t);
                    });
        }
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    private void openMentorPick(Player p) {
        MentorService ms = plugin.mentors();
        openPlayerPicker(p, "<gold>邀請哪位新手？",
                ms::canBeInvited,
                t -> List.of("<gray>遊玩 " + ms.playMinutes(t) + " 分鐘", "<yellow>點一下：邀請當導師"),
                (me, t) -> {
                    String err = ms.request(me, t);
                    if (err != null) plugin.msg().raw(me, "<gray><e></gray>", "e", err);
                    else {
                        plugin.msg().raw(me, "<green>已邀請 <n>，等對方接受。</green>", "n", t.getName());
                        plugin.msg().raw(t, "<green><n> 想當你的導師！輸入 <white>/mana</white> 打開選單接受。</green>", "n", me.getName());
                    }
                    me.closeInventory();
                },
                "目前沒有可以邀請的新手");
    }

    // ======================================================================
    // 生命之樹
    // ======================================================================

    public void openTree(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        LifeTree t = plugin.tree();
        Menu m = new Menu(3, "<dark_green>生命之樹");
        m.header(plugin, p, () -> openMain(p));
        List<String> lore = new ArrayList<>();
        LifeTree.Stage cur = t.currentStage();
        lore.add("<gray>階段：<white>" + (cur == null ? "種子" : cur.name()) + "<gray>　累積：<white>" + t.total());
        for (int i = 0; i < t.stages().size(); i++) {
            LifeTree.Stage s = t.stages().get(i);
            lore.add((i < t.reached() ? "<green>✔ " : "<dark_gray>○ ") + s.name() + " <gray>(" + s.threshold() + ")");
        }
        lore.add("");
        lore.add("<dark_gray>養分來自歸土、捐獻與各種手續費");
        m.set(2, icon(Material.FLOWERING_AZALEA, 1, "<green>生命之樹", lore));

        int slot = 10;
        for (long a : DONATE_AMOUNTS) {
            boolean ok = d.balance >= a;
            String name = (ok ? "<green>" : "<gray>") + "捐 " + a + " mana";
            List<String> l = List.of(ok ? "<yellow>點一下捐獻" : "<red>mana 不夠");
            m.set(slot++, ok ? plugin.icons().mana((int) Math.min(a, 64), name, l) : icon(Material.GRAY_DYE, (int) Math.min(a, 64), name, l),
                    e -> { if (act().donate(p, a)) Menu.success(p); openTree(p); });
        }
        m.set(16, icon(Material.MOSS_BLOCK, 1, "<green>全部捐出（" + d.balance + "）", List.of("<yellow>Shift + 點擊 才會送出", "<gray>避免手滑")),
                e -> {
                    if (!e.isShiftClick()) { plugin.msg().raw(p, "<gray>全部捐出需要按住 Shift 再點。</gray>"); return; }
                    if (act().donate(p, d.balance)) Menu.success(p);
                    openTree(p);
                });
        m.frame(Menu.LIFE);
        m.open(p);
    }

    // ======================================================================
    // 作品
    // ======================================================================

    public void openWorks(Player p) {
        List<Places.Work> mine = new ArrayList<>(plugin.places().worksOf(p.getUniqueId()));
        Menu m = new Menu(3, "<gold>我的作品");
        m.header(plugin, p, () -> openMain(p));
        int slot = 10;
        for (Places.Work w : mine) {
            if (slot > 13) break;
            m.set(slot++, icon(Material.ITEM_FRAME, 1, "<gold>" + esc(w.name()),
                    List.of("<gray>" + w.world() + "  " + (int) w.x() + ", " + (int) w.y() + ", " + (int) w.z(),
                            "", "<red>Shift + 右鍵：取消登記")),
                    e -> {
                        if (e.getClick() != ClickType.SHIFT_RIGHT) return;
                        click(p);
                        plugin.places().removeWork(p.getUniqueId(), w);
                        plugin.msg().raw(p, "<green>已取消登記「<n>」。</green>", "n", w.name());
                        openWorks(p);
                    });
        }
        if (mine.isEmpty()) m.set(11, icon(Material.STRUCTURE_VOID, 1, "<gray>還沒有登記作品", List.of("<dark_gray>站在作品裡，點右邊的按鈕")));
        boolean full = mine.size() >= act().maxWorks();
        m.set(16, icon(full ? Material.GRAY_DYE : Material.LIME_DYE, 1,
                full ? "<gray>作品已滿（" + mine.size() + "/" + act().maxWorks() + "）" : "<green>把我站的地方登記為作品",
                full ? List.of("<gray>先取消一件才能再登記")
                        : List.of("<gray>名稱會是「" + p.getName() + " 的作品 " + (mine.size() + 1) + "」",
                        "<gray>想自訂名稱：<white>/mana work set 名稱", "", "<yellow>點一下登記")),
                e -> {
                    if (full) return;
                    click(p);
                    act().addWork(p, null, w -> { if (p.isOnline()) openWorks(p); });
                });
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    public void openVisit(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        List<Places.Work> near = plugin.places().worksNear(p.getLocation(), act().worksRadius(), p.getUniqueId());
        Menu m = new Menu(6, "<dark_aqua>附近的作品");
        m.header(plugin, p, () -> openMain(p));
        List<Pager.Entry> entries = new ArrayList<>();
        for (Places.Work w : near) {
            String owner = String.valueOf(Bukkit.getOfflinePlayer(w.owner()).getName());
            boolean done = d.commentedToday.contains(w.owner());
            ItemStack ic = head(Bukkit.getOfflinePlayer(w.owner()), "<gold>" + esc(w.name()),
                    List.of("<gray>作者：<white>" + owner,
                            "<gray>距離 " + (int) Math.sqrt(Math.pow(p.getLocation().getX() - w.x(), 2) + Math.pow(p.getLocation().getZ() - w.z(), 2)) + " 格",
                            "", done ? "<gray>今天已經給這位作者留過言" : "<yellow>點一下：留言"));
            if (!done) Menu.glint(ic);
            entries.add(new Pager.Entry(ic, e -> { if (!done) { click(p); openPresets(p, w); } }));
        }
        Pager.fill(m, entries, 0, pg -> {}, "附近 " + (int) act().worksRadius() + " 格內沒有別人的作品", "走到別人的作品旁再打開看看");
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    private void openPresets(Player p, Places.Work w) {
        Menu m = new Menu(3, "<dark_aqua>留言給「" + esc(w.name()) + "」");
        m.header(plugin, p, () -> openVisit(p));
        List<String> presets = plugin.getConfig().getStringList("sources.works.preset-comments");
        if (presets.isEmpty()) presets = DEFAULT_PRESETS;
        int slot = 10;
        for (String text : presets) {
            if (slot > 15) break;
            m.set(slot++, icon(Material.PAPER, 1, "<white>「" + esc(text) + "」", List.of("<yellow>點一下送出")),
                    e -> { click(p); p.closeInventory(); act().comment(p, w, text, null); });
        }
        m.set(16, icon(Material.OAK_SIGN, 1, "<gray>想自己寫留言？",
                List.of("<gray>輸入：<white>/mana comment " + Bukkit.getOfflinePlayer(w.owner()).getName() + " 留言內容")));
        m.frame(Menu.SOCIAL);
        m.open(p);
    }

    // ======================================================================
    // 留言與紀錄（非同步讀取、翻頁）
    // ======================================================================

    public void openComments(Player p, int page) {
        Menu m = new Menu(6, "<white>收到的留言");
        m.header(plugin, p, () -> openMain(p));
        m.set(31, icon(Material.HOPPER, "<gray>讀取中…"));
        m.frame(Menu.SOCIAL);
        m.open(p);
        act().comments(p.getUniqueId(), 200, true).thenAccept(list -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            m.set(31, null, null);
            List<Pager.Entry> entries = new ArrayList<>();
            for (Actions.Entry c : list)
                entries.add(new Pager.Entry(icon(Material.PAPER, 1, "<white>" + esc(c.who()), wrap(c.text(), "<gray>", 18, c.time())), null));
            Pager.fill(m, entries, page, pg -> openComments(p, pg), "還沒有人留言", "登記作品，讓大家來參觀吧");
        }));
    }

    public void openLog(Player p, int page) {
        Menu m = new Menu(6, "<white>mana 紀錄");
        m.header(plugin, p, () -> openMain(p));
        m.set(31, icon(Material.HOPPER, "<gray>讀取中…"));
        m.frame(Menu.LIFE);
        m.open(p);
        act().ledger(p.getUniqueId(), 280).thenAccept(list -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            m.set(31, null, null);
            List<Pager.Entry> entries = new ArrayList<>();
            for (Actions.Entry c : list) {
                Source s = Source.byKey(c.source());
                boolean plus = c.delta() >= 0;
                entries.add(new Pager.Entry(icon(plus ? Material.LIME_DYE : Material.RED_DYE, 1,
                        (plus ? "<green>+" : "<red>") + c.delta() + " <white>" + (s == null ? c.source() : s.display),
                        wrap(c.text(), "<gray>", 18, c.time())), null));
            }
            Pager.fill(m, entries, page, pg -> openLog(p, pg), "還沒有任何紀錄", null);
        }));
    }

    /** 把長文字切成多行 lore，最後加上時間 */
    static List<String> wrap(String text, String color, int width, String time) {
        List<String> out = new ArrayList<>();
        String s = text == null ? "" : text;
        for (int i = 0; i < s.length(); i += width) out.add(color + esc(s.substring(i, Math.min(s.length(), i + width))));
        out.add("<dark_gray>" + time);
        return out;
    }
}
