package io.github.jun32036.manacore.theme;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.Msg;
import io.github.jun32036.manacore.gui.Menu;

import java.util.List;

/**
 * 介面外觀：已載入資源包的玩家看到自訂樣式（背景面板、圖示磁磚），其他人看到原版物品。
 *
 * 背景：資源包的字型 manacore:gui 裡每張背景圖是一個字元，標題先用負向空白把游標推到介面左上角、
 * 畫背景、再把游標推回原位畫標題文字。字元編號與 tools/packgen.py 一致：
 * 0xE200 + (列數 − 3) × 5 + 分區編號（self=0 social=1 life=2 alchemy=3 info=4）；背景寬 176，
 * 負向空白 0xE100 + i 代表 −2^i。
 */
public final class Theme {

    private static final Key FONT = Key.key("manacore", "gui");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");

    private final ManaCorePlugin plugin;

    public Theme(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean styled(Player p) {
        return p != null && plugin.pack() != null && plugin.pack().styled(p);
    }

    private static int accentIndex(Material accent) {
        if (accent == Menu.SELF) return 0;
        if (accent == Menu.SOCIAL) return 1;
        if (accent == Menu.LIFE) return 2;
        if (accent == Menu.ALCHEMY) return 3;
        return 4;
    }

    /** 往左 n 個像素的字元串 */
    static String neg(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) if ((n & (1 << i)) != 0) sb.append((char) (0xE100 + i));
        return sb.toString();
    }

    /** 箱子介面標題：有資源包時先畫背景再畫文字 */
    public Component title(Player p, int rows, Material accent, String titleMiniMessage) {
        Component text = Msg.mm(titleMiniMessage);
        if (!styled(p) || rows < 3 || rows > 6) return text;
        char bg = (char) (0xE200 + (rows - 3) * 5 + accentIndex(accent));
        String seq = neg(8) + bg + neg(177 - 8);
        return Component.text(seq).font(FONT).append(text.font(DEFAULT_FONT));
    }

    /** 功能按鈕：有資源包用圖示磁磚，否則用指定的原版物品 */
    public ItemStack button(Player p, String key, Material fallback, int amount, String name, List<String> lore) {
        if (!styled(p)) return Menu.icon(fallback, amount, name, lore);
        ItemStack it = Menu.icon(Material.PAPER, amount, name, lore);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key("manacore", key));
        return it;
    }

    public ItemStack button(Player p, String key, Material fallback, String name, String... lore) {
        return button(p, key, fallback, 1, name, List.of(lore));
    }

    /** 擋住空格：有資源包用透明圖示（背景本身是深色），否則深色玻璃 */
    public ItemStack filler(Player p) {
        if (!styled(p)) return Menu.darkFiller();
        ItemStack it = Menu.icon(Material.PAPER, 1, " ", List.of());
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key("manacore", "blank"));
        return it;
    }

    /** mana 寶石：有資源包用資源包的寶石圖，否則用頭顱材質 */
    public ItemStack mana(Player p, int amount, String name, List<String> lore) {
        if (!styled(p)) return plugin.icons().mana(amount, name, lore);
        return button(p, "mana", Material.DIAMOND, amount, name, lore);
    }
}
