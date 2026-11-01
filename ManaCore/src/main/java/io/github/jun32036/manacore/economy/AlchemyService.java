package io.github.jun32036.manacore.economy;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.PlayerData;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 煉金爐（等價交換）：把物品投入換成「煉金值」，再換出等值的其他物品。
 * - 煉金值和 mana 完全分開，不能互換，所以蓋農場投入再換出也不會產生 mana。
 * - 只能換出「曾經投入過」的物品（學會配方），所以仍然要親自去採集、合成，煉金爐只是幫你整理多餘的東西。
 * - 投入時有 loss-percent 的損耗（物資的出口），換出不再損耗。
 * - 帶附魔、自訂名稱或說明的物品不收，價值表只認識素的物品。
 */
public final class AlchemyService {

    private final ManaCorePlugin plugin;

    public AlchemyService(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("alchemy.enabled", true) && plugin.values().enabled();
    }

    public int lossPercent() {
        return Math.max(0, Math.min(90, plugin.getConfig().getInt("alchemy.loss-percent", 10)));
    }

    public boolean learnRequired() {
        return plugin.getConfig().getBoolean("alchemy.learn-required", true);
    }

    /** 素的物品才有價值：沒有附魔、自訂名稱、說明 */
    public static boolean isPlain(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (!it.hasItemMeta()) return true;
        ItemMeta m = it.getItemMeta();
        return !(m.hasDisplayName() || m.hasEnchants() || m.hasLore() || m.hasCustomModelData());
    }

    public long valueOf(ItemStack it) {
        return isPlain(it) ? plugin.values().value(it.getType()) : 0;
    }

    /** 投入後得到的煉金值 */
    public long yieldFor(Material m, int amount) {
        long v = plugin.values().value(m) * amount;
        return v * (100 - lossPercent()) / 100;
    }

    public List<Material> known(PlayerData d) {
        List<Material> out = new ArrayList<>();
        for (Material m : d.alchemyKnown) if (plugin.values().known(m)) out.add(m);
        out.sort(Comparator.comparingLong((Material m) -> plugin.values().value(m)).reversed().thenComparing(Material::name));
        return out;
    }

    /** 全部不需學會時：所有有價值的物品 */
    public List<Material> withdrawable(PlayerData d) {
        if (learnRequired()) return known(d);
        List<Material> out = new ArrayList<>(plugin.values().all().keySet());
        out.sort(Comparator.comparingLong((Material m) -> plugin.values().value(m)).reversed().thenComparing(Material::name));
        return out;
    }

    // ---------------- 投入 ----------------

    /** 投入背包中某一格；回傳得到的煉金值（0 = 失敗，已告知原因） */
    public long depositSlot(Player p, int slot) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !enabled()) return 0;
        ItemStack it = p.getInventory().getItem(slot);
        if (it == null || it.getType().isAir()) {
            plugin.msg().raw(p, "<gray>請先把要投入的物品拿在主手上。</gray>");
            return 0;
        }
        if (!isPlain(it)) {
            plugin.msg().raw(p, "<gray>帶有附魔、自訂名稱或說明的物品不能投入煉金爐。</gray>");
            return 0;
        }
        long v = plugin.values().value(it.getType());
        if (v <= 0) {
            plugin.msg().raw(p, "<gray>煉金爐不認識 <i>（價值表裡沒有它）。</gray>", "i", Component.translatable(it.getType().translationKey()));
            return 0;
        }
        int n = it.getAmount();
        long got = yieldFor(it.getType(), n);
        p.getInventory().setItem(slot, null);
        return credit(p, d, it.getType(), n, got);
    }

    /** 投入背包裡所有同種類的素物品 */
    public long depositAll(Player p, Material m) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !enabled() || plugin.values().value(m) <= 0) return 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        int n = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it != null && it.getType() == m && isPlain(it)) {
                n += it.getAmount();
                contents[i] = null;
            }
        }
        if (n == 0) return 0;
        p.getInventory().setStorageContents(contents);
        return credit(p, d, m, n, yieldFor(m, n));
    }

    private long credit(Player p, PlayerData d, Material m, int n, long got) {
        d.alchemy += got;
        boolean learned = d.alchemyKnown.add(m);
        String id = d.uuid.toString(), mat = m.getKey().toString();
        long av = d.alchemy;
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO alchemy(uuid, av) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET av=excluded.av")) {
                ps.setString(1, id);
                ps.setLong(2, av);
                ps.executeUpdate();
            }
            if (learned) try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO alchemy_known(uuid, material) VALUES(?,?)")) {
                ps.setString(1, id);
                ps.setString(2, mat);
                ps.executeUpdate();
            }
            return null;
        });
        p.playSound(p, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.3f);
        plugin.msg().raw(p, "<light_purple>投入 <i> ×<n>，得到 <white><v></white> 煉金值" + (lossPercent() > 0 ? "（損耗 " + lossPercent() + "%）" : "") + (learned ? "，學會了這種物品。" : "。") + "</light_purple>",
                "i", Component.translatable(m.translationKey()), "n", n, "v", got);
        return got;
    }

    // ---------------- 換出 ----------------

    /** 換出 want 個（會依煉金值與堆疊上限縮減）；回傳實際換出數量 */
    public int withdraw(Player p, Material m, int want) {
        PlayerData d = plugin.mana().get(p);
        if (d == null || !enabled() || want <= 0) return 0;
        long v = plugin.values().value(m);
        if (v <= 0) return 0;
        if (learnRequired() && !d.alchemyKnown.contains(m)) {
            plugin.msg().raw(p, "<gray>你還沒學會 <i>，先投入一個它才行。</gray>", "i", Component.translatable(m.translationKey()));
            return 0;
        }
        int n = (int) Math.min(want, Math.min(m.getMaxStackSize(), d.alchemy / v));
        if (n <= 0) {
            plugin.msg().raw(p, "<gray>煉金值不夠：換 1 個 <i> 需要 <v>，你有 <h>。</gray>", "i", Component.translatable(m.translationKey()), "v", v, "h", d.alchemy);
            return 0;
        }
        d.alchemy -= v * n;
        String id = d.uuid.toString();
        long av = d.alchemy;
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE alchemy SET av=? WHERE uuid=?")) {
                ps.setLong(1, av);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            return null;
        });
        Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(m, n));
        if (!over.isEmpty()) {
            over.values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            plugin.msg().send(p, "inventory-full");
        }
        p.playSound(p, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.5f, 1.4f);
        plugin.msg().raw(p, "<light_purple>用 <white><c></white> 煉金值換出 <i> ×<n>。</light_purple>", "c", v * n, "i", Component.translatable(m.translationKey()), "n", n);
        return n;
    }
}
