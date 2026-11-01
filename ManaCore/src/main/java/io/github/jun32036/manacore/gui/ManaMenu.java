package io.github.jun32036.manacore.gui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import io.github.jun32036.manacore.Actions;
import io.github.jun32036.manacore.LifeTree;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.MentorService;
import io.github.jun32036.manacore.Places;
import io.github.jun32036.manacore.PlayerData;
import io.github.jun32036.manacore.Source;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

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

    private static void click(Player p) {
        p.playSound(p, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
    }

    private static String bar(long now, long max, int width) {
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
    // 主選單
    // ======================================================================

    public void openMain(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        Menu m = new Menu(6, "<dark_green>✿ 生生不息 · mana");
        EconomyMenu em = plugin.econMenu();

        // 頭像：餘額與歸土
        long threshold = plugin.getConfig().getLong("decay.threshold", 500);
        double rate = plugin.getConfig().getDouble("decay.rate", 0.05);
        List<String> headLore = new ArrayList<>();
        headLore.add("<gray>mana 是拿來用的，不是拿來存的");
        if (plugin.getConfig().getBoolean("decay.enabled", true)) {
            headLore.add("<gray>持有超過 <white>" + threshold + "</white> 的部分，");
            headLore.add("<gray>每天有 <white>" + Math.round(rate * 100) + "%</white> 歸土回生命之樹");
            if (d.balance > threshold)
                headLore.add("<yellow>明天約會歸土 " + (long) Math.ceil((d.balance - threshold) * rate) + " mana");
        }
        int companions = plugin.coop().companions(p);
        if (companions > 0) headLore.add("<green>身邊有 " + companions + " 位夥伴：生活手藝加成中");
        if (plugin.mana().isAfk(d)) headLore.add("<red>你目前 AFK，暫時不會產生 mana");
        m.set(4, head(p, "<light_purple>你的 mana：<white>" + d.balance, headLore));

        // ---- 第一列：靠自己的雙手（採集、種植、畜牧、釣魚、烹飪、建造） ----
        List<String> today = new ArrayList<>();
        for (Source s : Source.values()) {
            if (!s.earning) continue;
            long cap = plugin.mana().cap(s), got = d.earned(s);
            if (cap <= 0 && got == 0) continue;
            today.add("<white>" + s.display + " <gray>" + got + (cap > 0 ? "/" + cap + " " + bar(got, cap, 10) : ""));
        }
        today.add("");
        today.add("<dark_gray>每天午夜重置");
        m.set(10, icon(Material.CLOCK, 1, "<gold>今天的 mana", today), e -> {});

        m.set(11, icon(Material.IRON_HOE, 1, "<green>生活手藝",
                List.of("<gray>採集、種植、畜牧、釣魚、烹飪、建造", "<gray>等級、新鮮度、今天的收入", "", "<yellow>點一下查看")),
                e -> { click(p); em.openSkills(p); });

        int dexTotal = 0;
        for (io.github.jun32036.manacore.economy.DexService.Category c : io.github.jun32036.manacore.economy.DexService.Category.values())
            dexTotal += plugin.dex().count(d, c);
        m.set(12, icon(Material.BOOK, 1, "<aqua>圖鑑",
                List.of("<gray>第一次釣到、種出、做出、繁殖的東西", "<gray>都會得到 mana（只算一次）", "<gray>已登錄 <white>" + dexTotal + "</white> 種", "", "<yellow>點一下查看")),
                e -> { click(p); em.openDex(p); });

        int open = 0;
        for (var o : plugin.orders().today()) if (!o.complete()) open++;
        m.set(13, icon(Material.BELL, 1, "<gold>城鎮訂單",
                List.of("<gray>城鎮今天需要的東西，交貨換 mana", "<gray>今天還有 <white>" + open + "</white> 張訂單等人完成", "", "<yellow>點一下查看")),
                e -> { click(p); em.openOrders(p); });

        int boardN = plugin.commissions().board().size(), unclaimed = plugin.commissions().unclaimed(p.getUniqueId());
        m.set(14, icon(Material.OAK_HANGING_SIGN, 1, "<aqua>委託板",
                List.of("<gray>需要幫忙？想幫別人？", "<gray>目前有 <white>" + boardN + "</white> 張委託",
                        unclaimed > 0 ? "<yellow>有 " + unclaimed + " 張委託物品待領取！" : "", "<yellow>點一下查看")),
                e -> { click(p); em.openBoard(p); });

        m.set(15, icon(Material.SCAFFOLDING, 1, "<gold>社區工程",
                List.of("<gray>全服一起蓋的公共建設", "<gray>捐物資、投入 mana，完工一起慶祝", "", "<yellow>點一下查看")),
                e -> { click(p); em.openProjects(p); });

        boolean remote = p.hasPermission("manacore.altar.remote");
        m.set(16, icon(Material.LODESTONE, 1, "<aqua>生命祭壇",
                List.of("<gray>用 mana 照公定價換物資", "",
                        remote ? "<yellow>點一下：開啟兌換介面" : "<gray>請到祭壇方塊旁<white>右鍵</white>開啟")),
                e -> {
                    click(p);
                    if (remote) plugin.altar().open(p);
                    else { p.closeInventory(); plugin.msg().raw(p, "<gray>生命祭壇要親自走到祭壇方塊旁，右鍵開啟。</gray>"); }
                });

        // ---- 第二列：人與人 ----
        int maxThanks = act().thanksPerDay();
        int left = maxThanks <= 0 ? 99 : Math.max(0, maxThanks - d.thankedToday.size());
        m.set(19, icon(Material.POPPY, 1, "<red>感謝",
                List.of("<gray>感謝幫過你的人，對方得到 mana", "<gray>不會花你自己的 mana",
                        "<gray>今天還能感謝 <white>" + (maxThanks <= 0 ? "不限" : left + " 人"), "", "<yellow>點一下：選擇玩家")),
                e -> { click(p); openThanks(p); });

        m.set(20, icon(Material.AMETHYST_SHARD, 1, "<light_purple>贈與 mana",
                List.of("<gray>把自己的 mana 送給別人", "", "<yellow>點一下：選擇玩家")),
                e -> { click(p); openGift(p); });

        List<Places.Work> mine = plugin.places().worksOf(p.getUniqueId());
        m.set(21, icon(Material.PAINTING, 1, "<gold>我的作品",
                List.of("<gray>登記作品，別人來參觀留言時你會得到 mana",
                        "<gray>已登記 <white>" + mine.size() + "/" + act().maxWorks(), "", "<yellow>點一下：管理作品")),
                e -> { click(p); openWorks(p); });

        int nearby = plugin.places().worksNear(p.getLocation(), act().worksRadius(), p.getUniqueId()).size();
        m.set(22, icon(Material.SPYGLASS, 1, "<aqua>參觀附近作品",
                List.of("<gray>給附近的作品留言，作者會得到 mana",
                        "<gray>附近 " + (int) act().worksRadius() + " 格內有 <white>" + nearby + "</white> 件作品", "", "<yellow>點一下：選擇作品")),
                e -> { click(p); openVisit(p); });

        m.set(23, icon(Material.BOOK, 1, "<white>收到的留言", List.of("<gray>讀取中…")), e -> { click(p); openComments(p); });
        act().unseenComments(p.getUniqueId()).thenAccept(n -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            m.set(23, icon(n > 0 ? Material.ENCHANTED_BOOK : Material.BOOK, Math.max(1, n), "<white>收到的留言",
                    List.of(n > 0 ? "<yellow>有 " + n + " 則新留言！" : "<gray>沒有新留言", "", "<yellow>點一下查看")),
                    e -> { click(p); openComments(p); });
        }));

        mentorButton(m, p, 24);

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
        m.set(25, icon(Material.FLOWERING_AZALEA, 1, "<green>生命之樹", treeLore), e -> { click(p); openTree(p); });

        // ---- 第三列：紀錄與說明 ----
        m.set(30, icon(Material.WRITABLE_BOOK, 1, "<white>mana 紀錄", List.of("<gray>最近 28 筆收支", "", "<yellow>點一下查看")),
                e -> { click(p); openLog(p); });

        m.set(32, icon(Material.KNOWLEDGE_BOOK, 1, "<green>怎麼得到 mana？",
                List.of("<white>靠自己的雙手",
                        "<gray>・生活手藝：採集、種植、畜牧、釣魚、烹飪、建造",
                        "<gray>・圖鑑：第一次做到某件事",
                        "<gray>・城鎮訂單：交出城鎮今天需要的東西",
                        "<gray>・探索：第一次到新的生態域、結構、地標",
                        "<white>和大家一起",
                        "<gray>・聊天被接話、被感謝、帶新手、作品被參觀",
                        "<gray>・委託板：幫別人完成委託",
                        "<gray>・合作挑戰：一起打首領、守住突襲（人越多越多）",
                        "<gray>・社區工程：捐物資、一起完工",
                        "",
                        "<dark_gray>做同一件事越久收益越少，換著玩最划算",
                        "<dark_gray>AFK 時不會產生 mana")));

        m.set(49, icon(Material.BARRIER, "<red>關閉"), e -> p.closeInventory());
        m.border();
        m.open(p);
    }

    private void mentorButton(Menu m, Player p, int slot) {
        MentorService ms = plugin.mentors();
        MentorService.Pair mine = ms.of(p.getUniqueId());
        if (mine != null && "pending".equals(mine.status)) {
            String mentor = String.valueOf(Bukkit.getOfflinePlayer(mine.mentor).getName());
            m.set(slot, icon(Material.NAME_TAG, 1, "<gold>導師邀請",
                    List.of("<white>" + mentor + "<gray> 想當你的導師", "<gray>完成引導後你們都會得到 mana", "", "<yellow>點一下：接受")),
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
        int slot = 0;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.equals(p) || !filter.test(t) || slot >= 45) continue;
            m.set(slot++, head(t, "<white>" + t.getName(), loreFn.apply(t)), e -> { click(p); onPick.accept(p, t); });
        }
        if (slot == 0) m.set(22, icon(Material.STRUCTURE_VOID, "<gray>" + empty));
        m.set(49, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
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
                (me, t) -> { act().thank(me, t, ""); openThanks(me); },
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
        m.set(4, head(t, "<white>" + t.getName(), List.of("<gray>你的 mana：<white>" + d.balance)));
        int slot = 11;
        for (long a : GIFT_AMOUNTS) {
            boolean ok = d.balance >= a;
            m.set(slot++, icon(ok ? Material.AMETHYST_SHARD : Material.GRAY_DYE, (int) Math.min(a, 64),
                    (ok ? "<light_purple>" : "<gray>") + "送 " + a + " mana",
                    List.of(ok ? "<yellow>點一下送出" : "<red>mana 不夠")),
                    e -> {
                        click(p);
                        if (!t.isOnline()) { plugin.msg().send(p, "no-player", "name", t.getName()); openGift(p); return; }
                        act().gift(p, t, a, "");
                        openGiftAmount(p, t);
                    });
        }
        m.set(22, icon(Material.ARROW, "<white>返回"), e -> { click(p); openGift(p); });
        m.border();
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
        List<String> lore = new ArrayList<>();
        LifeTree.Stage cur = t.currentStage();
        lore.add("<gray>階段：<white>" + (cur == null ? "種子" : cur.name()) + "<gray>　累積：<white>" + t.total());
        for (int i = 0; i < t.stages().size(); i++) {
            LifeTree.Stage s = t.stages().get(i);
            lore.add((i < t.reached() ? "<green>✔ " : "<dark_gray>○ ") + s.name() + " <gray>(" + s.threshold() + ")");
        }
        lore.add("");
        lore.add("<dark_gray>養分來自歸土與大家的捐獻");
        m.set(4, icon(Material.FLOWERING_AZALEA, 1, "<green>生命之樹", lore));

        int slot = 11;
        for (long a : DONATE_AMOUNTS) {
            boolean ok = d.balance >= a;
            m.set(slot++, icon(ok ? Material.BONE_MEAL : Material.GRAY_DYE, (int) Math.min(a, 64),
                    (ok ? "<green>" : "<gray>") + "捐 " + a + " mana", List.of(ok ? "<yellow>點一下捐獻" : "<red>mana 不夠")),
                    e -> { click(p); if (act().donate(p, a)) openTree(p); });
        }
        m.set(15, icon(Material.MOSS_BLOCK, 1, "<green>全部捐出（" + d.balance + "）", List.of("<yellow>Shift + 點擊 才會送出", "<gray>避免手滑")),
                e -> {
                    if (!e.isShiftClick()) { plugin.msg().raw(p, "<gray>全部捐出需要按住 Shift 再點。</gray>"); return; }
                    click(p);
                    if (act().donate(p, d.balance)) openTree(p);
                });
        m.set(22, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
        m.set(18, icon(Material.AMETHYST_SHARD, 1, "<light_purple>你的 mana：" + d.balance, List.of()));
        m.border();
        m.open(p);
    }

    // ======================================================================
    // 作品
    // ======================================================================

    public void openWorks(Player p) {
        List<Places.Work> mine = new ArrayList<>(plugin.places().worksOf(p.getUniqueId()));
        Menu m = new Menu(3, "<gold>我的作品");
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
        if (mine.isEmpty()) m.set(11, icon(Material.STRUCTURE_VOID, "<gray>還沒有登記作品"));
        boolean full = mine.size() >= act().maxWorks();
        m.set(15, icon(full ? Material.GRAY_DYE : Material.LIME_DYE, 1,
                full ? "<gray>作品已滿（" + mine.size() + "/" + act().maxWorks() + "）" : "<green>把我站的地方登記為作品",
                full ? List.of("<gray>先取消一件才能再登記")
                        : List.of("<gray>名稱會是「" + p.getName() + " 的作品 " + (mine.size() + 1) + "」",
                        "<gray>想自訂名稱：<white>/mana work set 名稱", "", "<yellow>點一下登記")),
                e -> {
                    if (full) return;
                    click(p);
                    act().addWork(p, null, w -> { if (p.isOnline()) openWorks(p); });
                });
        m.set(22, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
        m.border();
        m.open(p);
    }

    public void openVisit(Player p) {
        PlayerData d = data(p);
        if (d == null) return;
        List<Places.Work> near = plugin.places().worksNear(p.getLocation(), act().worksRadius(), p.getUniqueId());
        Menu m = new Menu(4, "<dark_aqua>附近的作品");
        int slot = 10;
        for (Places.Work w : near) {
            if (slot > 25) break;
            if (slot % 9 == 8) slot += 2;
            String owner = String.valueOf(Bukkit.getOfflinePlayer(w.owner()).getName());
            boolean done = d.commentedToday.contains(w.owner());
            m.set(slot++, head(Bukkit.getOfflinePlayer(w.owner()), "<gold>" + esc(w.name()),
                    List.of("<gray>作者：<white>" + owner,
                            "<gray>距離 " + (int) Math.sqrt(Math.pow(p.getLocation().getX() - w.x(), 2) + Math.pow(p.getLocation().getZ() - w.z(), 2)) + " 格",
                            "", done ? "<gray>今天已經給這位作者留過言" : "<yellow>點一下：留言")),
                    e -> { if (!done) { click(p); openPresets(p, w); } });
        }
        if (near.isEmpty())
            m.set(13, icon(Material.STRUCTURE_VOID, 1, "<gray>附近 " + (int) act().worksRadius() + " 格內沒有別人的作品",
                    List.of("<gray>走到別人的作品旁再打開看看")));
        m.set(31, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
        m.border();
        m.open(p);
    }

    private void openPresets(Player p, Places.Work w) {
        Menu m = new Menu(3, "<dark_aqua>留言給「" + esc(w.name()) + "」");
        List<String> presets = plugin.getConfig().getStringList("sources.works.preset-comments");
        if (presets.isEmpty()) presets = DEFAULT_PRESETS;
        int slot = 10;
        for (String text : presets) {
            if (slot > 16) break;
            m.set(slot++, icon(Material.PAPER, 1, "<white>「" + esc(text) + "」", List.of("<yellow>點一下送出")),
                    e -> { click(p); p.closeInventory(); act().comment(p, w, text, null); });
        }
        m.set(22, icon(Material.OAK_SIGN, 1, "<gray>想自己寫留言？",
                List.of("<gray>輸入：<white>/mana comment " + Bukkit.getOfflinePlayer(w.owner()).getName() + " 留言內容")));
        m.set(18, icon(Material.ARROW, "<white>返回"), e -> { click(p); openVisit(p); });
        m.border();
        m.open(p);
    }

    // ======================================================================
    // 留言與紀錄（非同步讀取）
    // ======================================================================

    public void openComments(Player p) {
        Menu m = new Menu(6, "<white>收到的留言");
        m.set(22, icon(Material.HOPPER, "<gray>讀取中…"));
        m.set(49, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
        m.open(p);
        act().comments(p.getUniqueId(), 45, true).thenAccept(list -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            m.set(22, null);
            if (list.isEmpty()) { m.set(22, icon(Material.STRUCTURE_VOID, "<gray>還沒有人留言")); return; }
            int slot = 0;
            for (Actions.Entry c : list) {
                m.set(slot++, icon(Material.PAPER, 1, "<white>" + esc(c.who()), wrap(c.text(), "<gray>", 18, c.time())));
            }
        }));
    }

    public void openLog(Player p) {
        Menu m = new Menu(5, "<white>mana 紀錄");
        m.set(22, icon(Material.HOPPER, "<gray>讀取中…"));
        m.set(40, icon(Material.ARROW, "<white>返回"), e -> { click(p); openMain(p); });
        m.open(p);
        act().ledger(p.getUniqueId(), 28).thenAccept(list -> plugin.sync(() -> {
            if (!m.isOpenFor(p)) return;
            m.set(22, null);
            if (list.isEmpty()) { m.set(22, icon(Material.STRUCTURE_VOID, "<gray>還沒有任何紀錄")); return; }
            int slot = 0;
            for (Actions.Entry c : list) {
                if (slot >= 36) break;
                Source s = Source.byKey(c.source());
                boolean plus = c.delta() >= 0;
                m.set(slot++, icon(plus ? Material.LIME_DYE : Material.RED_DYE, 1,
                        (plus ? "<green>+" : "<red>") + c.delta() + " <white>" + (s == null ? c.source() : s.display),
                        wrap(c.text(), "<gray>", 18, c.time())));
            }
        }));
    }

    /** 把長文字切成多行 lore，最後加上時間 */
    private static List<String> wrap(String text, String color, int width, String time) {
        List<String> out = new ArrayList<>();
        String s = text == null ? "" : text;
        for (int i = 0; i < s.length(); i += width) out.add(color + esc(s.substring(i, Math.min(s.length(), i + width))));
        out.add("<dark_gray>" + time);
        return out;
    }
}
