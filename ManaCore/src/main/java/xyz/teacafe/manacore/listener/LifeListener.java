package xyz.teacafe.manacore.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import xyz.teacafe.manacore.ManaCorePlugin;
import xyz.teacafe.manacore.economy.CraftService;
import xyz.teacafe.manacore.economy.DexService;
import xyz.teacafe.manacore.economy.EconMath;
import xyz.teacafe.manacore.economy.Skill;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 生活手藝與圖鑑的事件來源：種植、採集、畜牧、釣魚、烹飪、建造。
 *
 * 防刷：
 * - 礦石、原木、南瓜西瓜、甘蔗等「自然產物」如果是玩家放下的，打掉不算（記在區塊的持久資料裡，重啟也記得）。
 * - 建造：剛放下的方塊 10 分鐘內被同一人拆掉，點數收回；單一種方塊狂放，多樣性只有一半。
 */
public final class LifeListener implements Listener {

    private static final Set<Material> CROPS = EnumSet.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS,
            Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);
    /** 不靠成熟度、直接打掉收成的作物（要防「自己放了再打」） */
    private static final Set<Material> BLOCK_CROPS = EnumSet.of(
            Material.MELON, Material.PUMPKIN, Material.SUGAR_CANE, Material.BAMBOO, Material.CACTUS);
    private static final Set<Material> FISH = EnumSet.of(
            Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);
    private static final Set<Material> TREASURE = EnumSet.of(
            Material.BOW, Material.ENCHANTED_BOOK, Material.FISHING_ROD, Material.NAME_TAG, Material.NAUTILUS_SHELL, Material.SADDLE);
    /** 合成出來算「料理」的非食物（蛋糕是方塊） */
    private static final Set<Material> DISH_EXTRA = EnumSet.of(Material.CAKE);
    private static final long BUILD_UNDO_MS = 10 * 60_000L;

    private final ManaCorePlugin plugin;
    private final NamespacedKey placedKey;
    /** 每位玩家最近放的方塊：位置 → 當時得到的有效點數（拆掉時收回） */
    private final Map<UUID, LinkedHashMap<String, double[]>> recentPlaced = new HashMap<>();

    public LifeListener(ManaCorePlugin plugin) {
        this.plugin = plugin;
        this.placedKey = new NamespacedKey(plugin, "placed");
    }

    private CraftService craft() {
        return plugin.craft();
    }

    private DexService dex() {
        return plugin.dex();
    }

    private static String id(Material m) {
        return m.getKey().getKey();
    }

    private static Component name(Material m) {
        return Component.translatable(m.translationKey());
    }

    // ======================================================================
    // 破壞方塊：種植、採集
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Material type = b.getType();
        undoBuild(p, b);

        // 成熟作物
        if (CROPS.contains(type)) {
            if (!(b.getBlockData() instanceof Ageable a) || a.getAge() < a.getMaximumAge()) return;
            Material produce = b.getBlockData().getPlacementMaterial();
            craft().award(p, Skill.FARM, craft().points("crop", 1), id(produce), "收成" + id(produce));
            dex().discover(p, DexService.Category.CROP, id(produce), name(produce));
            return;
        }

        boolean tracked = isTracked(type);
        boolean placedByPlayer = tracked && consumePlaced(b);
        if (placedByPlayer) return; // 玩家自己放的，不是自然產物

        if (BLOCK_CROPS.contains(type)) {
            double pts = switch (type) {
                case MELON, PUMPKIN -> craft().points("melon-pumpkin", 1);
                case BAMBOO -> craft().points("bamboo", 0.25);
                default -> craft().points("cane-cactus", 0.5);
            };
            craft().award(p, Skill.FARM, pts, id(type), "收成" + id(type));
            dex().discover(p, DexService.Category.CROP, id(type), name(type));
            return;
        }
        if (isOre(type)) {
            String ore = EconMath.normalizeOre(id(type));
            craft().award(p, Skill.GATHER, craft().points("ores." + ore, craft().points("ore-default", 1)), ore, "挖到" + ore);
            Material display = Material.matchMaterial(ore);
            dex().discover(p, DexService.Category.GATHER, ore, name(display == null ? type : display));
            return;
        }
        if (Tag.LOGS.isTagged(type)) {
            craft().award(p, Skill.GATHER, craft().points("log", 0.5), id(type), "砍樹");
            dex().discover(p, DexService.Category.GATHER, id(type), name(type));
        }
    }

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS || m == Material.AMETHYST_CLUSTER;
    }

    private static boolean isTracked(Material m) {
        return isOre(m) || Tag.LOGS.isTagged(m) || BLOCK_CROPS.contains(m);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        Material m = e.getHarvestedBlock().getType();
        String key = e.getItemsHarvested().isEmpty() ? id(m) : id(e.getItemsHarvested().get(0).getType());
        craft().award(e.getPlayer(), Skill.FARM, craft().points("berry", 1), key, "採收" + key);
        Material produce = Material.matchMaterial(key);
        dex().discover(e.getPlayer(), DexService.Category.CROP, key, name(produce == null ? m : produce));
    }

    // ======================================================================
    // 放置方塊：建造，並標記「玩家放的自然方塊」
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Material type = b.getType();
        if (isTracked(type)) markPlaced(b);
        if (!type.isSolid() || CROPS.contains(type)) return; // 火把、作物、花之類不算建造

        Player p = e.getPlayer();
        double eff = craft().award(p, Skill.BUILD, craft().points("build-block", 0.25), id(type), "建造");
        if (eff <= 0) return;
        LinkedHashMap<String, double[]> recent = recentPlaced.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        recent.put(blockKey(b), new double[]{eff, System.currentTimeMillis()});
        while (recent.size() > 512) recent.remove(recent.keySet().iterator().next());
    }

    private void undoBuild(Player p, Block b) {
        LinkedHashMap<String, double[]> recent = recentPlaced.get(p.getUniqueId());
        if (recent == null) return;
        double[] v = recent.remove(blockKey(b));
        if (v != null && System.currentTimeMillis() - (long) v[1] <= BUILD_UNDO_MS) craft().revoke(p, Skill.BUILD, v[0]);
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ---- 區塊持久資料：記錄玩家放置的自然方塊 ----

    private void markPlaced(Block b) {
        Chunk c = b.getChunk();
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        long[] arr = pdc.getOrDefault(placedKey, PersistentDataType.LONG_ARRAY, new long[0]);
        long v = EconMath.packBlock(b.getX(), b.getY(), b.getZ());
        for (long x : arr) if (x == v) return;
        if (arr.length >= 4096) return; // 單一區塊記太多就不再記（極端情況）
        long[] next = java.util.Arrays.copyOf(arr, arr.length + 1);
        next[arr.length] = v;
        pdc.set(placedKey, PersistentDataType.LONG_ARRAY, next);
    }

    /** 如果這格是玩家放的，移除紀錄並回傳 true */
    private boolean consumePlaced(Block b) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] arr = pdc.get(placedKey, PersistentDataType.LONG_ARRAY);
        if (arr == null || arr.length == 0) return false;
        long v = EconMath.packBlock(b.getX(), b.getY(), b.getZ());
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != v) continue;
            long[] next = new long[arr.length - 1];
            System.arraycopy(arr, 0, next, 0, i);
            System.arraycopy(arr, i + 1, next, i, arr.length - i - 1);
            if (next.length == 0) pdc.remove(placedKey);
            else pdc.set(placedKey, PersistentDataType.LONG_ARRAY, next);
            return true;
        }
        return false;
    }

    // ======================================================================
    // 釣魚
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(e.getCaught() instanceof Item item)) return;
        Player p = e.getPlayer();
        plugin.mana().touch(p);
        Material m = item.getItemStack().getType();
        double pts = FISH.contains(m) ? craft().points("fish", 3)
                : TREASURE.contains(m) ? craft().points("treasure", 5)
                : craft().points("junk", 1);
        craft().award(p, Skill.FISHING, pts, id(m), "釣到" + id(m));
        dex().discover(p, DexService.Category.FISH, id(m), name(m));
    }

    // ======================================================================
    // 畜牧
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player p)) return;
        String type = e.getEntity().getType().getKey().getKey();
        craft().award(p, Skill.HUSBANDRY, craft().points("breed", 3), type, "繁殖" + type);
        dex().discover(p, DexService.Category.ANIMAL, type, entityName(e.getEntity()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        String type = e.getEntity().getType().getKey().getKey();
        craft().award(e.getPlayer(), Skill.HUSBANDRY, craft().points("shear", 1), "shear:" + type, "剪毛");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        if (!(e.getOwner() instanceof Player p)) return;
        String type = e.getEntity().getType().getKey().getKey();
        craft().award(p, Skill.HUSBANDRY, craft().points("tame", 5), "tame:" + type, "馴服" + type);
        dex().discover(p, DexService.Category.TAME, type, entityName(e.getEntity()));
    }

    private static Component entityName(Entity en) {
        return Component.translatable(en.getType().translationKey());
    }

    // ======================================================================
    // 烹飪：從熔爐 / 煙燻爐拿出熟食、合成料理；其他合成登錄圖鑑
    // ======================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnace(FurnaceExtractEvent e) {
        Material m = e.getItemType();
        if (!m.isEdible() || e.getItemAmount() <= 0) return;
        Player p = e.getPlayer();
        plugin.mana().touch(p);
        craft().award(p, Skill.COOKING, craft().points("smelt-food", 0.5) * e.getItemAmount(), id(m), "烹煮" + id(m));
        dex().discover(p, DexService.Category.DISH, id(m), name(m));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result = e.getRecipe().getResult();
        Material m = result.getType();
        if (m.isAir()) return;
        plugin.mana().touch(p);
        if (m.isEdible() || DISH_EXTRA.contains(m)) {
            int times = e.isShiftClick() ? shiftCraftTimes(e) : 1;
            double per = craft().points("dishes." + id(m), craft().points("dish-default", 1));
            craft().award(p, Skill.COOKING, per * times, id(m), "料理" + id(m));
            dex().discover(p, DexService.Category.DISH, id(m), name(m));
        } else {
            dex().discover(p, DexService.Category.CRAFT, id(m), name(m));
        }
    }

    /** Shift 連續合成時，大約會做幾次（以合成格中最少的那一格估計） */
    private static int shiftCraftTimes(CraftItemEvent e) {
        int min = 64;
        boolean any = false;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it == null || it.getType().isAir()) continue;
            any = true;
            min = Math.min(min, it.getAmount());
        }
        return any ? Math.max(1, min) : 1;
    }

    // ======================================================================
    // 原版的平行貨幣：可選擇關閉村民交易
    // ======================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillager(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof AbstractVillager)) return;
        if (plugin.getConfig().getBoolean("vanilla.villager-trading", true)) return;
        if (e.getPlayer().hasPermission("manacore.admin")) return;
        e.setCancelled(true);
        plugin.msg().raw(e.getPlayer(), "<gray>這個世界不用綠寶石交易。需要物資可以到生命祭壇、委託板，或問問其他玩家！</gray>");
    }
}
