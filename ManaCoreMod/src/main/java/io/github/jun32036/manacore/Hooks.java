package io.github.jun32036.manacore;

import io.github.jun32036.manacore.data.WorldState;
import io.github.jun32036.manacore.logic.EconMath;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 遊戲事件 → 生活手藝與圖鑑。由 ManaCore 的事件註冊與 mixin 呼叫。 */
public final class Hooks {

    private static final Set<String> CROPS = Set.of("wheat", "carrots", "potatoes", "beetroots", "nether_wart", "cocoa", "torchflower_crop", "pitcher_crop");
    private static final Set<String> BLOCK_CROPS = Set.of("melon", "pumpkin", "sugar_cane", "bamboo", "cactus");
    /** 這兩種的收成物是花，不登錄圖鑑（不出現花朵圖示） */
    private static final Set<String> NO_DEX = Set.of("torchflower_crop", "pitcher_crop");
    private static final Set<Item> FISH = Set.of(Items.COD, Items.SALMON, Items.TROPICAL_FISH, Items.PUFFERFISH);
    private static final Set<Item> TREASURE = Set.of(Items.BOW, Items.ENCHANTED_BOOK, Items.FISHING_ROD, Items.NAME_TAG, Items.NAUTILUS_SHELL, Items.SADDLE);
    private static final long BUILD_UNDO_MS = 10 * 60_000L;

    private final ManaCore mod;
    /** 每位玩家最近放的方塊：位置 → [有效點數, 時間] */
    private final Map<UUID, LinkedHashMap<Long, double[]>> recentBuild = new HashMap<>();

    public Hooks(ManaCore mod) {
        this.mod = mod;
    }

    private Mana mana() {
        return mod.mana();
    }

    private static String id(Block b) {
        return BuiltInRegistries.BLOCK.getKey(b).getPath();
    }

    private static Component name(Item it) {
        return Features.name(it);
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.equals("ancient_debris") || id.equals("amethyst_cluster");
    }

    private static boolean tracked(BlockState st) {
        String id = id(st.getBlock());
        return isOre(id) || st.is(BlockTags.LOGS) || BLOCK_CROPS.contains(id);
    }

    private static String key(ServerLevel lvl, BlockPos pos) {
        return lvl.dimension().identifier() + "|" + pos.asLong();
    }

    /** 作物是否成熟：找名為 age 的整數屬性，值等於最大值 */
    private static boolean mature(BlockState st) {
        for (Property<?> p : st.getProperties()) {
            if (p instanceof IntegerProperty ip && p.getName().equals("age")) {
                int max = ip.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                return st.getValue(ip) >= max;
            }
        }
        return false;
    }

    // ---------------- 挖掘 ----------------

    public void onBreak(ServerPlayer p, ServerLevel lvl, BlockPos pos, BlockState st) {
        String id = id(st.getBlock());
        // 剛放的方塊馬上拆掉：收回建造點數
        LinkedHashMap<Long, double[]> recent = recentBuild.get(p.getUUID());
        if (recent != null) {
            double[] v = recent.remove(pos.asLong());
            if (v != null && System.currentTimeMillis() - (long) v[1] <= BUILD_UNDO_MS) mana().revoke(p, Mana.Skill.BUILD, v[0]);
        }
        if (CROPS.contains(id)) {
            if (!mature(st)) return;
            Item produce = st.getBlock().asItem();
            if (id.equals("carrots")) produce = Items.CARROT;
            else if (id.equals("potatoes")) produce = Items.POTATO;
            else if (id.equals("beetroots")) produce = Items.BEETROOT;
            else if (id.equals("cocoa")) produce = Items.COCOA_BEANS;
            else if (id.equals("wheat")) produce = Items.WHEAT;
            extraDrops(p, lvl, pos, st, Mana.Skill.FARM);
            mana().craft(p, Mana.Skill.FARM, mana().points("crop", 1), Features.id(produce));
            if (!NO_DEX.contains(id)) mana().discover(p, "crop", Features.id(produce), name(produce));
            return;
        }
        if (tracked(st) && mod.world().placed.remove(key(lvl, pos))) return; // 玩家自己放的
        if (BLOCK_CROPS.contains(id)) {
            double pts = switch (id) {
                case "melon", "pumpkin" -> mana().points("melon-pumpkin", 1);
                case "bamboo" -> mana().points("bamboo", 0.25);
                default -> mana().points("cane-cactus", 0.5);
            };
            mana().craft(p, Mana.Skill.FARM, pts, id);
            mana().discover(p, "crop", id, name(st.getBlock().asItem()));
            return;
        }
        if (isOre(id)) {
            String ore = EconMath.normalizeOre(id);
            extraDrops(p, lvl, pos, st, Mana.Skill.GATHER);
            mana().craft(p, Mana.Skill.GATHER, mod.config().craft.ores.getOrDefault(ore, mana().points("ore-default", 1)), ore);
            Item shown = Features.item(ore);
            mana().discover(p, "gather", ore, name(shown == Items.AIR ? st.getBlock().asItem() : shown));
            return;
        }
        if (st.is(BlockTags.LOGS)) {
            mana().craft(p, Mana.Skill.GATHER, mana().points("log", 0.5), id);
            mana().discover(p, "gather", id, name(st.getBlock().asItem()));
        }
    }

    /** 被動：有機率把掉落物再掉一份 */
    private void extraDrops(ServerPlayer p, ServerLevel lvl, BlockPos pos, BlockState st, Mana.Skill k) {
        if (!mana().roll(p, k)) return;
        for (ItemStack drop : Block.getDrops(st, lvl, pos, null, p, p.getMainHandItem())) Block.popResource(lvl, pos, drop.copy());
        p.sendSystemMessage(Component.literal("✦ " + k.display + "被動：多拿一份！").withStyle(ChatFormatting.GREEN), true);
    }

    // ---------------- 放置 ----------------

    public void onPlace(ServerPlayer p, ServerLevel lvl, BlockPos pos, BlockState st) {
        if (tracked(st)) {
            WorldState w = mod.world();
            w.placed.add(key(lvl, pos));
            while (w.placed.size() > 50_000) w.placed.remove(w.placed.iterator().next());
        }
        if (!st.isSolidRender() || CROPS.contains(id(st.getBlock()))) return; // 火把、花、作物不算建造
        double eff = mana().craft(p, Mana.Skill.BUILD, mana().points("build-block", 0.25), id(st.getBlock()));
        if (eff <= 0) return;
        LinkedHashMap<Long, double[]> recent = recentBuild.computeIfAbsent(p.getUUID(), k -> new LinkedHashMap<>());
        recent.put(pos.asLong(), new double[]{eff, System.currentTimeMillis()});
        while (recent.size() > 512) recent.remove(recent.keySet().iterator().next());
    }

    // ---------------- 釣魚、畜牧 ----------------

    public void onFish(ServerPlayer p, Iterable<ItemStack> caught) {
        for (ItemStack st : caught) {
            Item it = st.getItem();
            double pts = FISH.contains(it) ? mana().points("fish", 3) : TREASURE.contains(it) ? mana().points("treasure", 5) : mana().points("junk", 1);
            mana().craft(p, Mana.Skill.FISHING, pts, Features.id(it));
            mana().discover(p, "fish", Features.id(it), name(it));
            if (mana().roll(p, Mana.Skill.FISHING)) {
                Features.give(p, it, st.getCount());
                p.sendSystemMessage(Component.literal("✦ 釣魚被動：再多一份！").withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    public void onBreed(ServerPlayer p, Animal parent, AgeableMob child) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(parent.getType()).getPath();
        mana().craft(p, Mana.Skill.HUSBANDRY, mana().points("breed", 3), type);
        mana().discover(p, "animal", type, parent.getType().getDescription());
        if (child != null && mana().roll(p, Mana.Skill.HUSBANDRY) && p.level() instanceof ServerLevel lvl) {
            Entity twin = child.getType().create(lvl, EntitySpawnReason.BREEDING);
            if (twin instanceof AgeableMob am) {
                am.setAge(-24000);
                am.snapTo(child.getX(), child.getY(), child.getZ(), child.getYRot(), 0);
                lvl.addFreshEntity(am);
                p.sendSystemMessage(Component.literal("✦ 畜牧被動：雙胞胎！").withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    public void onTame(ServerPlayer p, Animal animal) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(animal.getType()).getPath();
        mana().craft(p, Mana.Skill.HUSBANDRY, mana().points("tame", 5), "tame:" + type);
        mana().discover(p, "tame", type, animal.getType().getDescription());
    }

    // ---------------- 合成與烹煮（ItemStack.onCraftedBy：合成、熔爐取出都會呼叫，數量是實際做出的） ----------------

    public void onCrafted(ServerPlayer p, ItemStack st, int amount) {
        if (st.isEmpty() || amount <= 0) return;
        Item it = st.getItem();
        String id = Features.id(it);
        boolean food = st.has(net.minecraft.core.component.DataComponents.FOOD) || it == Items.CAKE;
        if (food) {
            double per = mod.config().craft.dishes.getOrDefault(id, mana().points("dish-default", 0.5));
            mana().craft(p, Mana.Skill.COOKING, per * amount, id);
            mana().discover(p, "dish", id, name(it));
            int extra = 0;
            for (int i = 0; i < Math.min(amount, 16); i++) if (mana().roll(p, Mana.Skill.COOKING)) extra++;
            if (extra > 0) {
                Features.give(p, it, extra);
                p.sendSystemMessage(Component.literal("✦ 烹飪被動：多做了 " + extra + " 個！").withStyle(ChatFormatting.GREEN), true);
            }
        } else {
            mana().discover(p, "craft", id, name(it));
        }
    }

    // ---------------- 探索：每 5 秒看一次玩家所在的生態域與結構 ----------------

    public void tickExplore(ServerPlayer p) {
        if (p.isSpectator()) return;
        ServerLevel lvl = p.level();
        BlockPos pos = p.blockPosition();
        lvl.getBiome(pos).unwrapKey().ifPresent(k -> {
            String key = "biome:" + k.identifier();
            mana().explore(p, key, mod.config().explore.biome,
                    Component.literal("發現生態域 ").append(Component.translatable(k.identifier().toLanguageKey("biome"))));
        });
        var reg = lvl.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (Structure s : lvl.structureManager().getAllStructuresAt(pos).keySet()) {
            if (!lvl.structureManager().getStructureAt(pos, s).isValid()) continue;
            var k = reg.getKey(s);
            if (k == null) continue;
            long amount = mod.config().explore.structureOverrides.getOrDefault(k.toString(), mod.config().explore.structure);
            mana().explore(p, "structure:" + k, amount, Component.literal("發現結構 " + k.getPath().replace('_', ' ')));
        }
    }
}
