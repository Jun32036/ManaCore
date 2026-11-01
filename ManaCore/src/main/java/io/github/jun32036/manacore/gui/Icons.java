package io.github.jun32036.manacore.gui;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import io.github.jun32036.manacore.ManaCorePlugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 介面上代表「mana」的圖示：一顆藍色的寶石（自訂材質的玩家頭顱）。
 * 材質存放在 Mojang 的材質伺服器（經 MineSkin 上傳），不需要資源包。
 * 設定檔 icons.mana-texture：留空用內建材質；填 none 改用鑽石；也可以貼上其他頭顱的材質值。
 */
public final class Icons {

    /** 內建的藍色寶石頭顱材質（textures.minecraft.net/texture/386b3516…） */
    public static final String DEFAULT_MANA_TEXTURE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc5MzU0MDU2NTg3OSwKICAicHJvZmlsZUlkIiA6ICI2YmU4ODdiZmRmZTk0OWQ0OGViZTcyYzA3OWM3ZGEyMyIsCiAgInByb2ZpbGVOYW1lIiA6ICJGcmFuY3l2b2wiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzg2YjM1MTYyMDY1NzZhNzM5ZGRjYjU3N2E3MzQ2OWIwMTIzYTdlYzExNzNlNjdlOWQ2MGQxZTM2MmNmZjBkIgogICAgfQogIH0KfQ==";

    private final ManaCorePlugin plugin;
    private PlayerProfile manaProfile;

    public Icons(ManaCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String tex = plugin.getConfig().getString("icons.mana-texture", "");
        if (tex == null || tex.isBlank()) tex = DEFAULT_MANA_TEXTURE;
        if (tex.equalsIgnoreCase("none")) {
            manaProfile = null;
            return;
        }
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes("manacore:mana-icon".getBytes(StandardCharsets.UTF_8)), "mana");
        profile.setProperty(new ProfileProperty("textures", tex.strip()));
        manaProfile = profile;
    }

    /** 一顆 mana 寶石；amount 會顯示在圖示右下角（1～64） */
    public ItemStack mana(int amount, String name, List<String> lore) {
        if (manaProfile == null) return Menu.icon(Material.DIAMOND, amount, name, lore);
        ItemStack it = Menu.icon(Material.PLAYER_HEAD, amount, name, lore);
        PlayerProfile profile = manaProfile;
        it.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(profile));
        return it;
    }

    public ItemStack mana(int amount, String name, String... lore) {
        return mana(amount, name, List.of(lore));
    }
}
