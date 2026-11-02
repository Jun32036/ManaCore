package io.github.jun32036.manacore.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jun32036.manacore.Features;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * ManaCore 選單：置中的灰色面板，上面一排分頁，下面是可以捲動的清單。
 * 所有按鈕都只是送一個 Action 給伺服器，伺服器處理完回傳新的資料再重畫。
 */
public final class ManaScreen extends Screen {

    // ---- 顏色（ARGB）----
    private static final int PANEL = 0xFFC6C6C6, PANEL_HI = 0xFFFFFFFF, PANEL_LO = 0xFF555555;
    private static final int CONTENT = 0xFFB4B4B4, ROW_A = 0xFFBDBDBD, ROW_B = 0xFFC4C4C4;
    private static final int TEXT = 0xFF3F3F3F, MUTED = 0xFF6A6A6A, ACCENT = 0xFF1C5F8C, GOOD = 0xFF2E7D32, WARN = 0xFF9A5B00;
    private static final int BTN = 0xFF6F6F6F, BTN_HOVER = 0xFF4F78A8, BTN_OFF = 0xFFA0A0A0, BTN_TEXT = 0xFFFFFFFF, BTN_TEXT_OFF = 0xFFDADADA;
    private static final int BAR_BG = 0xFF7A7A7A, BAR_XP = 0xFF3E8E41, BAR_DAY = 0xFF2F6FA8;

    private static final String[] TABS = {"總覽", "手藝", "委託", "祭壇", "煉金", "工程", "圖鑑"};
    private static int tab = 0;
    private static int altarCat = 0;

    private JsonObject data;
    private int px, py, pw, ph;          // 面板
    private int cx, cy, cw, ch;          // 內容區
    private double scroll;
    private int contentHeight;
    private final List<Row> rows = new ArrayList<>();
    private final List<Btn> buttons = new ArrayList<>();   // 本幀畫出來的按鈕（螢幕座標）

    public ManaScreen(JsonObject data) {
        super(Component.literal("ManaCore"));
        this.data = data;
    }

    void update(JsonObject d) {
        this.data = d;
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 單人遊戲暫停時內建伺服器不處理封包，按鈕會沒反應
    }

    @Override
    protected void init() {
        pw = Math.min(400, width - 16);
        ph = Math.min(260, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 6;
        cy = py + 40;
        cw = pw - 12;
        ch = ph - 46;
        rebuild();
    }

    // ======================================================================
    // 列與按鈕
    // ======================================================================

    private interface Painter {
        void paint(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my);
    }

    /** chips=true：按鈕靠左排（分類切換用），停用的那顆代表目前選中 */
    private record Row(int height, Painter painter, List<BtnDef> btns, boolean chips) { }

    private record BtnDef(String label, boolean enabled, Runnable action) { }

    private record Btn(int x, int y, int w, int h, boolean enabled, Runnable action) { }

    private void rebuild() {
        rows.clear();
        switch (tab) {
            case 0 -> buildOverview();
            case 1 -> buildSkills();
            case 2 -> buildOrders();
            case 3 -> buildAltar();
            case 4 -> buildAlchemy();
            case 5 -> buildProjects();
            default -> buildDex();
        }
        contentHeight = rows.stream().mapToInt(Row::height).sum();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - ch)));
    }

    private void row(int h, Painter p, BtnDef... btns) {
        rows.add(new Row(h, p, List.of(btns), false));
    }

    private void heading(String text) {
        row(14, (g, x, y, w, mx, my) -> g.text(font, text, x + 2, y + 4, ACCENT, false));
    }

    private void line(String text, int color) {
        for (FormattedCharSequence s : font.split(Component.literal(text), cw - 8)) {
            row(11, (g, x, y, w, mx, my) -> g.text(font, s, x + 4, y + 1, color, false));
        }
    }

    private static BtnDef btn(String label, boolean enabled, String action, String arg, int count) {
        return new BtnDef(label, enabled, () -> ManaCoreClient.send(action, arg, count));
    }

    // ======================================================================
    // 各分頁
    // ======================================================================

    private void buildOverview() {
        row(26, (g, x, y, w, mx, my) -> {
            g.item(new ItemStack(Items.DIAMOND), x + 4, y + 4);
            g.text(font, "mana " + l(data, "balance"), x + 26, y + 4, ACCENT, false);
            g.text(font, "第 " + l(data, "day") + " 天（遊戲內）", x + 26, y + 14, MUTED, false);
        });
        heading("今天的收入");
        for (JsonElement e : arr(data, "today")) {
            JsonObject t = e.getAsJsonObject();
            long got = l(t, "got"), cap = l(t, "cap");
            row(12, (g, x, y, w, mx, my) -> {
                g.text(font, s(t, "name"), x + 4, y + 2, TEXT, false);
                if (cap > 0) {
                    bar(g, x + 70, y + 3, w - 130, 6, (double) got / cap, BAR_DAY);
                    g.text(font, got + " / " + cap, x + w - 54, y + 2, got >= cap ? WARN : TEXT, false);
                } else {
                    g.text(font, got + "（不設上限）", x + 70, y + 2, MUTED, false);
                }
            });
        }
        heading("最近的紀錄");
        JsonArray log = arr(data, "log");
        if (log.isEmpty()) line("還沒有紀錄。去挖礦、種田、釣魚、做菜看看吧！", MUTED);
        for (int i = log.size() - 1; i >= Math.max(0, log.size() - 12); i--) line(log.get(i).getAsString(), TEXT);
        heading("怎麼賺 mana");
        line("・生活手藝：採集、種植、畜牧、釣魚、烹飪、建造都會累積點數。同一件事一直做會越來越沒效率，換著做最划算。", MUTED);
        line("・第一次：圖鑑新登錄、新的生態域與結構、原版成就，都有一次性的 mana。", MUTED);
        line("・委託：城鎮訂單每天換；附近村民偶爾會發光，蹲下右鍵他就能交貨。", MUTED);
        line("・mana 可以在祭壇換物資，或投入工程完成自己的建設目標。", MUTED);
    }

    private void buildSkills() {
        for (JsonElement e : arr(data, "skills")) {
            JsonObject k = e.getAsJsonObject();
            row(46, (g, x, y, w, mx, my) -> {
                g.item(stack(s(k, "icon")), x + 4, y + 4);
                g.text(font, s(k, "name") + "  Lv" + l(k, "level") + "「" + s(k, "title") + "」", x + 26, y + 3, TEXT, false);
                double xp = d(k, "xp"), prev = d(k, "prev"), next = d(k, "next");
                boolean max = next <= prev;
                bar(g, x + 26, y + 14, w - 120, 5, max ? 1 : (xp - prev) / Math.max(1, next - prev), BAR_XP);
                g.text(font, max ? "已滿級" : (long) xp + " / " + (long) next, x + w - 88, y + 12, MUTED, false);
                g.text(font, "今天 " + l(k, "today") + "/" + l(k, "cap") + " mana  新鮮度 " + pct(d(k, "fresh"))
                        + "  多樣性 ×" + String.format("%.2f", d(k, "variety")) + "  被動 " + pct(d(k, "passive")), x + 26, y + 23, d(k, "fresh") < 0.5 ? WARN : MUTED, false);
                g.text(font, s(k, "how"), x + 26, y + 33, MUTED, false);
            });
        }
        line("新鮮度：同一個手藝連續做會下降，休息或做別的事會慢慢恢復。多樣性：最近做的東西種類越多，倍率越高。", MUTED);
    }

    private void buildOrders() {
        long left = l(data, "orderLeft");
        heading("城鎮訂單（每天更新，今天還能拿 " + (left < 0 ? "不限" : left) + " mana）");
        for (JsonElement e : arr(data, "orders")) {
            JsonObject o = e.getAsJsonObject();
            boolean done = b(o, "done");
            boolean lvOk = l(o, "myLevel") >= l(o, "level");
            boolean enough = l(o, "have") >= l(o, "amount");
            row(24, (g, x, y, w, mx, my) -> {
                g.item(stack(s(o, "item")), x + 4, y + 4);
                g.text(font, itemName(s(o, "item")) + " ×" + l(o, "amount") + "  → " + l(o, "reward") + " mana", x + 26, y + 3, done ? MUTED : TEXT, false);
                String sub = done ? "今天已完成" : (!lvOk ? "需要「" + s(o, "skill") + "」Lv" + l(o, "level") : "身上有 " + l(o, "have"));
                g.text(font, sub, x + 26, y + 13, done ? GOOD : (!lvOk || !enough) ? WARN : MUTED, false);
            }, btn(done ? "完成" : "交貨", !done && lvOk && enough, "order", s(o, "id"), 1));
        }
        heading("村民委託");
        JsonArray reqs = arr(data, "requests");
        if (reqs.isEmpty()) line("目前沒有。待在村莊附近，村民偶爾會發光請你幫忙。", MUTED);
        for (JsonElement e : reqs) {
            JsonObject r = e.getAsJsonObject();
            row(24, (g, x, y, w, mx, my) -> {
                g.item(stack(s(r, "item")), x + 4, y + 4);
                g.text(font, itemName(s(r, "item")) + " ×" + l(r, "amount") + "  → " + l(r, "reward") + " mana", x + 26, y + 3, TEXT, false);
                g.text(font, "身上有 " + l(r, "have") + "・剩 " + l(r, "daysLeft") + " 天・蹲下右鍵那位村民交貨", x + 26, y + 13, MUTED, false);
            });
        }
    }

    private void buildAltar() {
        JsonArray cats = arr(data, "altar");
        if (cats.isEmpty()) return;
        altarCat = Math.max(0, Math.min(altarCat, cats.size() - 1));
        // 分類按鈕：一列放得下幾個就放幾個
        List<BtnDef> catBtns = new ArrayList<>();
        for (int i = 0; i < cats.size(); i++) {
            int idx = i;
            catBtns.add(new BtnDef(s(cats.get(i).getAsJsonObject(), "name"), i != altarCat, () -> {
                altarCat = idx;
                scroll = 0;
                rebuild();
            }));
        }
        int perRow = Math.max(1, (cw - 8) / 62);
        for (int i = 0; i < catBtns.size(); i += perRow) {
            List<BtnDef> part = catBtns.subList(i, Math.min(catBtns.size(), i + perRow));
            rows.add(new Row(16, (g, x, y, w, mx, my) -> { }, part, true));
        }
        JsonObject c = cats.get(altarCat).getAsJsonObject();
        long bal = l(data, "balance");
        heading(s(c, "name") + "（你有 " + bal + " mana）");
        JsonArray items = arr(c, "items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject it = items.get(i).getAsJsonObject();
            long price = l(it, "price"), base = l(it, "base");
            String arg = s(c, "id") + ":" + i;
            row(20, (g, x, y, w, mx, my) -> {
                g.item(stack(s(it, "item")), x + 4, y + 2);
                g.text(font, itemName(s(it, "item")) + " ×" + l(it, "amount"), x + 26, y + 6, TEXT, false);
                String p = price + " mana" + (price < base ? "（原 " + base + "）" : "");
                g.text(font, p, x + w - 150, y + 6, price < base ? GOOD : MUTED, false);
            }, btn("換 1 組", bal >= price, "buy", arg, 1), btn("×5", bal >= price * 5, "buy", arg, 5));
        }
    }

    private void buildAlchemy() {
        JsonObject a = data.getAsJsonObject("alchemy");
        if (a == null) return;
        row(26, (g, x, y, w, mx, my) -> {
            g.item(new ItemStack(Items.BREWING_STAND), x + 4, y + 4);
            g.text(font, "煉金值 " + l(a, "av"), x + 26, y + 4, ACCENT, false);
            g.text(font, "等價交換：物品投入變成煉金值（損耗 " + l(a, "loss") + "%），再換成學會的物品。不產生 mana。", x + 26, y + 14, MUTED, false);
        });
        String hand = s(a, "hand");
        long hv = l(a, "handValue");
        row(24, (g, x, y, w, mx, my) -> {
            if (hand.isEmpty()) {
                g.text(font, "主手沒有拿東西。把要投入的物品拿在手上再按。", x + 4, y + 8, MUTED, false);
                return;
            }
            g.item(stack(hand), x + 4, y + 4);
            g.text(font, "手上：" + itemName(hand) + " ×" + l(a, "handCount"), x + 26, y + 3, TEXT, false);
            g.text(font, hv > 0 ? "每個 " + hv + "，這一疊可得 " + l(a, "handYield") : "煉金爐不收這個物品", x + 26, y + 13, hv > 0 ? MUTED : WARN, false);
        }, btn("投入手上", !hand.isEmpty() && hv > 0, "deposit", "", 1), btn("全部同種", !hand.isEmpty() && hv > 0, "deposit_all", "", 1));
        heading("學會的物品（點按鈕換出）");
        JsonArray known = arr(a, "known");
        if (known.isEmpty()) line("還沒學會任何物品。投入一次就會學會。", MUTED);
        long av = l(a, "av");
        for (JsonElement e : known) {
            JsonObject k = e.getAsJsonObject();
            long v = l(k, "value");
            row(20, (g, x, y, w, mx, my) -> {
                g.item(stack(s(k, "item")), x + 4, y + 2);
                g.text(font, itemName(s(k, "item")), x + 26, y + 6, TEXT, false);
                g.text(font, "每個 " + v, x + w - 150, y + 6, MUTED, false);
            }, btn("1 個", av >= v, "withdraw", s(k, "item"), 1), btn("16", av >= v * 16, "withdraw", s(k, "item"), 16), btn("64", av >= v * 64, "withdraw", s(k, "item"), 64));
        }
    }

    private void buildProjects() {
        long bal = l(data, "balance");
        line("工程是自己的建設目標：湊齊物資、投入 mana，完工就能拿到獎勵。", MUTED);
        for (JsonElement e : arr(data, "projects")) {
            JsonObject p = e.getAsJsonObject();
            boolean done = b(p, "done");
            long have = l(p, "mana"), goal = l(p, "manaGoal");
            row(26, (g, x, y, w, mx, my) -> {
                g.item(stack(s(p, "icon")), x + 4, y + 4);
                g.text(font, s(p, "name") + (done ? "（已完工）" : ""), x + 26, y + 3, done ? GOOD : ACCENT, false);
                g.text(font, s(p, "description"), x + 26, y + 14, MUTED, false);
            });
            if (done) continue;
            long need = Math.max(0, goal - have);
            int give = (int) Math.min(Integer.MAX_VALUE, Math.min(bal, need));
            row(16, (g, x, y, w, mx, my) -> {
                g.text(font, "mana", x + 26, y + 4, TEXT, false);
                bar(g, x + 70, y + 5, w - 190, 6, (double) have / Math.max(1, goal), BAR_DAY);
                g.text(font, have + "/" + goal, x + w - 116, y + 4, MUTED, false);
            }, btn("投入", give > 0, "invest", s(p, "id"), give));
            for (JsonElement ne : arr(p, "needs")) {
                JsonObject n = ne.getAsJsonObject();
                long h = l(n, "have"), gl = l(n, "goal"), mine = l(n, "mine");
                row(18, (g, x, y, w, mx, my) -> {
                    g.item(stack(s(n, "item")), x + 26, y + 1);
                    g.text(font, itemName(s(n, "item")), x + 46, y + 5, TEXT, false);
                    g.text(font, h + "/" + gl + "（身上 " + mine + "）", x + w - 150, y + 5, h >= gl ? GOOD : MUTED, false);
                }, btn("交出", h < gl && mine > 0, "donate", s(p, "id") + ":" + s(n, "item"), 1));
            }
        }
    }

    private static final String[][] DEX_CATS = {
            {"gather", "礦物與木材"}, {"crop", "作物"}, {"fish", "魚獲與寶物"}, {"dish", "料理"},
            {"animal", "繁殖過的動物"}, {"tame", "馴服的夥伴"}, {"craft", "合成過的物品"}};

    private void buildDex() {
        JsonObject dex = data.getAsJsonObject("dex");
        row(14, (g, x, y, w, mx, my) -> g.text(font, "探索過的生態域與結構：" + l(data, "explored"), x + 2, y + 3, ACCENT, false));
        if (dex == null) return;
        int perLine = Math.max(1, (cw - 8) / 18);
        for (String[] c : DEX_CATS) {
            JsonArray ids = arr(dex, c[0]);
            heading(c[1] + "（" + ids.size() + "）");
            if (ids.isEmpty()) {
                line("還沒有。", MUTED);
                continue;
            }
            for (int i = 0; i < ids.size(); i += perLine) {
                List<String> part = new ArrayList<>();
                for (int j = i; j < Math.min(ids.size(), i + perLine); j++) part.add(ids.get(j).getAsString());
                boolean entity = c[0].equals("animal") || c[0].equals("tame");
                row(18, (g, x, y, w, mx, my) -> {
                    for (int j = 0; j < part.size(); j++) {
                        int ix = x + 4 + j * 18;
                        ItemStack st = entity ? spawnEgg(part.get(j)) : stack(part.get(j));
                        g.item(st, ix, y + 1);
                        if (mx >= ix && mx < ix + 16 && my >= y + 1 && my < y + 17 && my >= cy && my < cy + ch) {
                            g.setTooltipForNextFrame(font, entity ? entityName(part.get(j)) : st.getHoverName(), mx, my);
                        }
                    }
                });
            }
        }
    }

    // ======================================================================
    // 繪製
    // ======================================================================

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        // 面板（原版風格的立體灰框）
        g.fill(px, py, px + pw, py + ph, PANEL);
        g.fill(px, py, px + pw - 1, py + 1, PANEL_HI);
        g.fill(px, py, px + 1, py + ph - 1, PANEL_HI);
        g.fill(px + 1, py + ph - 1, px + pw, py + ph, PANEL_LO);
        g.fill(px + pw - 1, py + 1, px + pw, py + ph, PANEL_LO);

        g.text(font, "ManaCore", px + 8, py + 6, TEXT, false);
        String bal = "mana " + l(data, "balance");
        g.text(font, bal, px + pw - 8 - font.width(bal), py + 6, ACCENT, false);

        buttons.clear();
        // 分頁
        int tx = px + 6, tw = (pw - 12) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int x0 = tx + i * tw;
            boolean sel = i == tab, hover = !sel && in(mx, my, x0, py + 18, tw - 2, 16);
            g.fill(x0, py + 18, x0 + tw - 2, py + 34, sel ? CONTENT : hover ? 0xFFD6D6D6 : 0xFFA9A9A9);
            g.centeredText(font, TABS[i], x0 + (tw - 2) / 2, py + 22, sel ? ACCENT : TEXT);
            int idx = i;
            buttons.add(new Btn(x0, py + 18, tw - 2, 16, !sel, () -> {
                tab = idx;
                scroll = 0;
                rebuild();
            }));
        }

        // 內容
        g.fill(cx, cy - 6, cx + cw, cy + ch, CONTENT);
        g.enableScissor(cx, cy, cx + cw, cy + ch);
        int y = cy - (int) scroll;
        int n = 0;
        for (Row r : rows) {
            if (y + r.height() > cy && y < cy + ch) {
                if (r.height() >= 18) g.fill(cx, y, cx + cw, y + r.height(), (n % 2 == 0) ? ROW_A : ROW_B);
                r.painter().paint(g, cx, y, cw - 6, mx, my);
                drawButtons(g, r, y, mx, my);
            }
            if (r.height() >= 18) n++;
            y += r.height();
        }
        g.disableScissor();

        // 捲軸
        if (contentHeight > ch) {
            int barH = Math.max(12, ch * ch / contentHeight);
            int barY = cy + (int) ((ch - barH) * (scroll / (contentHeight - ch)));
            g.fill(cx + cw - 4, cy, cx + cw, cy + ch, 0xFF9A9A9A);
            g.fill(cx + cw - 4, barY, cx + cw, barY + barH, 0xFF5E5E5E);
        }
    }

    private void drawButtons(GuiGraphicsExtractor g, Row r, int y, int mx, int my) {
        if (r.btns().isEmpty()) return;
        int bh = 14;
        int by = y + (r.height() - bh) / 2;
        if (r.chips()) {
            int bx = cx + 4;
            for (BtnDef b : r.btns()) {
                int bw = 58;
                paintButton(g, b, bx, by, bw, bh, mx, my, !b.enabled());
                bx += bw + 4;
            }
            return;
        }
        int bx = cx + cw - 8;
        for (int i = r.btns().size() - 1; i >= 0; i--) {
            BtnDef b = r.btns().get(i);
            int bw = Math.max(26, font.width(b.label()) + 10);
            bx -= bw;
            paintButton(g, b, bx, by, bw, bh, mx, my, false);
            bx -= 3;
        }
    }

    /** selected：分類按鈕被選中時用強調色，而不是灰掉 */
    private void paintButton(GuiGraphicsExtractor g, BtnDef b, int x, int y, int w, int h, int mx, int my, boolean selected) {
        boolean visible = my >= cy && my < cy + ch;
        boolean hover = b.enabled() && visible && in(mx, my, x, y, w, h);
        int bg = selected ? ACCENT : !b.enabled() ? BTN_OFF : hover ? BTN_HOVER : BTN;
        g.fill(x, y, x + w, y + h, bg);
        g.fill(x, y + h - 1, x + w, y + h, 0x40000000);
        g.centeredText(font, b.label(), x + w / 2, y + 3, b.enabled() || selected ? BTN_TEXT : BTN_TEXT_OFF);
        int top = Math.max(y, cy), bottom = Math.min(y + h, cy + ch);
        if (bottom > top) buttons.add(new Btn(x, top, w, bottom - top, b.enabled(), b.action()));
    }

    private void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int color) {
        if (w <= 0) return;
        double f = Math.max(0, Math.min(1, frac));
        g.fill(x, y, x + w, y + h, BAR_BG);
        g.fill(x, y, x + (int) Math.round(w * f), y + h, color);
    }

    // ======================================================================
    // 輸入
    // ======================================================================

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.button() == 0) {
            for (Btn b : buttons) {
                if (b.enabled() && in(e.x(), e.y(), b.x(), b.y(), b.w(), b.h())) {
                    b.action().run();
                    playClick();
                    return true;
                }
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (contentHeight > ch) {
            scroll = Math.max(0, Math.min(contentHeight - ch, scroll - v * 18));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (ManaCoreClient.MENU_KEY.matches(e)) {
            onClose();
            return true;
        }
        return super.keyPressed(e);
    }

    private void playClick() {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1f);
        }
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    private static ItemStack stack(String id) {
        Item it = Features.item(id);
        return it == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(it);
    }

    private static ItemStack spawnEgg(String entity) {
        Item it = Features.item(entity + "_spawn_egg");
        return it == Items.AIR ? new ItemStack(Items.LEAD) : new ItemStack(it);
    }

    private static Component entityName(String entity) {
        Identifier id = Identifier.tryParse("minecraft:" + entity);
        if (id == null) return Component.literal(entity);
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t -> t.getDescription()).orElse(Component.literal(entity));
    }

    private static String itemName(String id) {
        Item it = Features.item(id);
        return it == Items.AIR ? id : Features.name(it).getString();
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = o == null ? null : o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String s(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long l(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static double d(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static boolean b(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
}
