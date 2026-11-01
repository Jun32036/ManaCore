package io.github.jun32036.manacore.listener;

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
import io.github.jun32036.manacore.ManaCorePlugin;
import io.github.jun32036.manacore.economy.CraftService;
import io.github.jun32036.manacore.economy.DexService;
import io.github.jun32036.manacore.economy.EconMath;
import io.github.jun32036.manacore.economy.Skill;

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
 * - 建造：點數要等方塊在原處放滿 10 分鐘、而且還是同一種方塊才入帳（拆掉、被活塞推走、掉落都不算）；
 *   單一種方塊狂放，多樣性只有一半。
 * - 活塞推動「玩家放的自然方塊」時，標記跟著移動，不能靠活塞洗掉標記。
 * - 合成料理：下一個 tick 比對合成格實際少了多少才算（背包滿、沒合成成功的點擊不算）。
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
    private static final long BUILD_SETTLE_MS = 10 * 60_000L;
    private static final int MAX_PENDING = 2048;

    private final ManaCorePlugin plugin;
    private final NamespacedKey placedKey;
    /** 還沒入帳的建造點數：每位玩家 → (位置 → 待入帳) */
    private final Map<UUID, LinkedHashMap<String, PendingBuild>> pending = new HashMap<>();

    private record PendingBuild(String world, int x, int y, int z, Material type, double eff, long at) {}

    public LifeListener(ManaCorePlugin plugin) {
        this.plugin = plugin;
        this.placedKey = new NamespacedKey(plugin, "placed");
        // 每分鐘結算一次放滿 10 分鐘的方塊
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, this::settleBuilds, 1200L, 1200L);
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
        double eff = craft().measure(p, Skill.BUILD, craft().points("build-block", 0.25), id(type));
        if (eff <= 0) return;
        LinkedHashMap<String, PendingBuild> mine = pending.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        String key = b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
        mine.remove(key); // 同一格重放：只算最後一次
        mine.put(key, new PendingBuild(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(), type, eff, System.currentTimeMillis()));
        while (mine.size() > MAX_PENDING) mine.remove(mine.keySet().iterator().next());
    }

    /** 放滿 10 分鐘、方塊仍在原處且種類相同的，才入帳 */
    private void settleBuilds() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, LinkedHashMap<String, PendingBuild>> en : pending.entrySet()) {
            Player p = org.bukkit.Bukkit.getPlayer(en.getKey());
            if (p == null) continue; // 離線的人等上線再結算
            double sum = 0;
            int n = 0;
            var it = en.getValue().values().iterator();
            while (it.hasNext()) {
                PendingBuild pb = it.next();
                if (now - pb.at() < BUILD_SETTLE_MS) break; // 依放置時間排序，後面的更新
                it.remove();
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(pb.world());
                if (w == null || !w.isChunkLoaded(pb.x() >> 4, pb.z() >> 4)) continue;
                if (w.getBlockAt(pb.x(), pb.y(), pb.z()).getType() != pb.type()) continue;
                sum += pb.eff();
                n++;
            }
            if (sum > 0) craft().settle(p, Skill.BUILD, sum, "建造 " + n + " 個方塊");
        }
        pending.values().removeIf(Map::isEmpty);
    }

    // ---- 活塞：玩家放的自然方塊被推動時，標記跟著走 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent e) {
        movePlaced(e.getBlocks(), e.getDirection());
    }

    /**
     * 被推動的方塊中，凡是「自然產物」種類的，都在移動目的地加上標記（不論原本是否玩家放的）：
     * 用活塞搬動礦石或原木本來就不是正常的採集。目的地一定是空氣或另一個被推動的方塊，
     * 只標記符合這個條件的那一側，避免不同版本對收回方向定義不同而標錯。
     */
    private void movePlaced(java.util.List<Block> blocks, org.bukkit.block.BlockFace dir) {
        if (blocks.isEmpty()) return;
        java.util.Set<Block> moving = new java.util.HashSet<>(blocks);
        for (Block b : blocks) {
            if (!isTracked(b.getType())) continue;
            consumePlaced(b);
            for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{dir, dir.getOppositeFace()}) {
                Block to = b.getRelative(f);
                if (to.getType().isAir() || moving.contains(to)) markPlaced(to);
            }
        }
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
        if (e.getAction() == org.bukkit.event.inventory.InventoryAction.NOTHING) return;
        Material m = e.getRecipe().getResult().getType();
        if (m.isAir()) return;
        plugin.mana().touch(p);
        // 合成格的內容（之後比對實際用掉多少）
        org.bukkit.inventory.CraftingInventory inv = e.getInventory();
        ItemStack[] before = cloneMatrix(inv.getMatrix());
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            int times = craftedTimes(before, inv.getMatrix());
            if (times <= 0) return; // 背包滿、沒有真的合成
            if (m.isEdible() || DISH_EXTRA.contains(m)) {
                double per = craft().points("dishes." + id(m), craft().points("dish-default", 1));
                craft().award(p, Skill.COOKING, per * times, id(m), "料理" + id(m));
                dex().discover(p, DexService.Category.DISH, id(m), name(m));
            } else {
                dex().discover(p, DexService.Category.CRAFT, id(m), name(m));
            }
        });
    }

    private static ItemStack[] cloneMatrix(ItemStack[] matrix) {
        ItemStack[] out = new ItemStack[matrix.length];
        for (int i = 0; i < matrix.length; i++) out[i] = matrix[i] == null ? null : matrix[i].clone();
        return out;
    }

    /**
     * 實際合成了幾次：每次合成，每個材料格各少 1。只看合成後種類不變或變空的格子
     * （牛奶桶會變成空桶，那格不能拿來算）。
     */
    static int craftedTimes(ItemStack[] before, ItemStack[] after) {
        int best = 0;
        for (int i = 0; i < before.length && i < after.length; i++) {
            ItemStack b = before[i];
            if (b == null || b.getType().isAir()) continue;
            ItemStack a = after[i];
            int left;
            if (a == null || a.getType().isAir()) left = 0;
            else if (a.getType() == b.getType()) left = a.getAmount();
            else continue;
            best = Math.max(best, b.getAmount() - left);
        }
        return best;
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
