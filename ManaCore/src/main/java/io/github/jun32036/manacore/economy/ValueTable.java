package io.github.jun32036.manacore.economy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import io.github.jun32036.manacore.ManaCorePlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 價值表：每種物品值多少「煉金值」（100 煉金值 ≈ 1 mana 的物資價值；煉金值本身不能換成 mana）。
 *
 * 基礎物資（礦物、作物、木頭、怪物掉落…）的價值寫在設定檔 values.base；
 * 其他物品從伺服器的合成、燒製、切石配方自動推算：結果的價值 = 材料價值總和 ÷ 產出數量，
 * 有多種做法時取最便宜的一種。這樣「拆解」一樣東西最多只能拿回最便宜配方的材料，不會憑空多出價值。
 *
 * 煉金爐與市集都用這張表，同一樣東西在哪裡都是同一個價值。
 */
public final class ValueTable {

    /** 一條配方：result 產出 count 個，每個材料格是一組可選材料（取最便宜） */
    public record Rec<K>(K result, int count, List<List<K>> ingredients) {}

    private final ManaCorePlugin plugin;
    private final Map<Material, Long> values = new EnumMap<>(Material.class);
    private int baseCount, derivedCount;

    public ValueTable(ManaCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("values.enabled", true);
    }

    /** 0 = 沒有價值（煉金爐與市集都不收） */
    public long value(Material m) {
        return values.getOrDefault(m, 0L);
    }

    public boolean known(Material m) {
        return value(m) > 0;
    }

    public Map<Material, Long> all() {
        return Collections.unmodifiableMap(values);
    }

    public int baseCount() { return baseCount; }
    public int derivedCount() { return derivedCount; }

    public void reload() {
        values.clear();
        Map<Material, Long> base = new EnumMap<>(Material.class);
        ConfigurationSection sec = plugin.section("values.base");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                long v = sec.getLong(k);
                if (m == null || !m.isItem()) plugin.getLogger().warning("values.base 的 " + k + " 不是有效物品，已略過");
                else if (v > 0) base.put(m, v);
            }
        }
        baseCount = base.size();
        if (!plugin.getConfig().getBoolean("values.derive-from-recipes", true)) {
            values.putAll(base);
            derivedCount = 0;
            return;
        }
        List<Rec<Material>> recs = collectRecipes();
        values.putAll(derive(base, recs, plugin.getConfig().getInt("values.max-passes", 25)));
        derivedCount = values.size() - baseCount;
        // 明確設定為 0 的物品：不給價值（例如不想讓某些東西進煉金爐）
        if (sec != null) for (String k : sec.getKeys(false)) {
            Material m = Material.matchMaterial(k);
            if (m != null && sec.getLong(k) <= 0) values.remove(m);
        }
    }

    /** 從伺服器的配方讀出可用來推算的配方 */
    private List<Rec<Material>> collectRecipes() {
        List<Rec<Material>> out = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r;
            try { r = it.next(); } catch (Exception e) { continue; }
            ItemStack result = r.getResult();
            if (result == null || result.getType().isAir() || result.getAmount() <= 0) continue;
            if (result.hasItemMeta()) continue; // 藥水、附魔書等帶資料的結果不推算
            List<List<Material>> ings = new ArrayList<>();
            if (r instanceof ShapedRecipe s) {
                Map<Character, RecipeChoice> map = s.getChoiceMap();
                for (String row : s.getShape()) for (char c : row.toCharArray()) {
                    RecipeChoice ch = map.get(c);
                    if (ch == null) continue;
                    List<Material> opts = options(ch);
                    if (opts.isEmpty()) { ings = null; break; }
                    ings.add(opts);
                }
                if (ings == null) continue;
            } else if (r instanceof ShapelessRecipe s) {
                for (RecipeChoice ch : s.getChoiceList()) {
                    List<Material> opts = options(ch);
                    if (opts.isEmpty()) { ings = null; break; }
                    ings.add(opts);
                }
                if (ings == null) continue;
            } else if (r instanceof CookingRecipe<?> s) {
                List<Material> opts = options(s.getInputChoice());
                if (opts.isEmpty()) continue;
                ings.add(opts);
            } else if (r instanceof StonecuttingRecipe s) {
                List<Material> opts = options(s.getInputChoice());
                if (opts.isEmpty()) continue;
                ings.add(opts);
            } else continue; // 鍛造、轉化等不推算
            if (ings.isEmpty()) continue;
            out.add(new Rec<>(result.getType(), result.getAmount(), ings));
        }
        return out;
    }

    private static List<Material> options(RecipeChoice ch) {
        List<Material> out = new ArrayList<>();
        if (ch instanceof RecipeChoice.MaterialChoice mc) out.addAll(mc.getChoices());
        else if (ch instanceof RecipeChoice.ExactChoice ec) for (ItemStack s : ec.getChoices()) out.add(s.getType());
        return out;
    }

    /**
     * 純計算（可單元測試）：從基礎價值與配方推算所有物品的價值。
     * 基礎價值優先；其餘物品取所有可行配方中最便宜的一種；反覆直到沒有變化或達到 maxPasses。
     */
    public static <K> Map<K, Long> derive(Map<K, Long> base, List<Rec<K>> recs, int maxPasses) {
        Map<K, Long> v = new HashMap<>(base);
        for (int pass = 0; pass < Math.max(1, maxPasses); pass++) {
            boolean changed = false;
            for (Rec<K> r : recs) {
                if (base.containsKey(r.result())) continue;
                long sum = 0;
                boolean ok = true;
                for (List<K> slot : r.ingredients()) {
                    long best = Long.MAX_VALUE;
                    for (K m : slot) {
                        Long x = v.get(m);
                        if (x != null && x > 0 && x < best) best = x;
                    }
                    if (best == Long.MAX_VALUE) { ok = false; break; }
                    sum += best;
                }
                if (!ok) continue;
                long val = Math.max(1, (long) Math.ceil(sum / (double) r.count()));
                Long cur = v.get(r.result());
                if (cur == null || val < cur) {
                    v.put(r.result(), val);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return v;
    }

    /** 給測試與管理指令：物品 ID → 價值 */
    public Map<String, Long> dump() {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<Material, Long> e : values.entrySet()) out.put(e.getKey().getKey().getKey(), e.getValue());
        return out;
    }
}
