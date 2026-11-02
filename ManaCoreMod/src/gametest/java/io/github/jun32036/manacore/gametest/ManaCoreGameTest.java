package io.github.jun32036.manacore.gametest;

import com.mojang.authlib.GameProfile;
import io.github.jun32036.manacore.Features;
import io.github.jun32036.manacore.ManaCore;
import io.github.jun32036.manacore.Mana;
import io.github.jun32036.manacore.Net;
import io.github.jun32036.manacore.data.Config;
import io.github.jun32036.manacore.data.PlayerState;
import io.github.jun32036.manacore.data.WorldState;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.impl.event.interaction.FakePlayerPacketListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 遊戲內測試：用假玩家走一遍「做事 → 得到 mana / 經驗 / 圖鑑」的流程。
 * 執行：./gradlew runGameTest
 */
public class ManaCoreGameTest {

    /** 各個測試同時跑，失敗清單要分開 */
    private final java.util.Map<String, List<String>> fails = new java.util.concurrent.ConcurrentHashMap<>();
    private String current = "";

    private void begin(String name) {
        current = name;
        fails.put(name, new ArrayList<>());
    }

    private void check(boolean ok, String what) {
        ManaCore.LOG.info("[測試] {} {}", ok ? "通過" : "失敗", what);
        if (!ok) fails.get(current).add(what);
    }

    private void finish(GameTestHelper helper) {
        List<String> f = fails.remove(current);
        if (f == null || f.isEmpty()) helper.succeed();
        else helper.fail(String.join("；", f));
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer p = FakePlayer.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        BlockPos at = helper.absolutePos(new BlockPos(0, 1, 0));
        p.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        ManaCore.get().world().players.remove(p.getUUID().toString()); // 測試世界會保留上一輪的資料
        return p;
    }

    private static double xp(PlayerState s, Mana.Skill k) {
        return s.xp.getOrDefault(k.key, 0.0);
    }

    @GameTest(maxTicks = 100)
    public void lifeSkills(GameTestHelper helper) {
        begin("lifeSkills");
        ManaCore mod = ManaCore.get();
        ServerLevel lvl = helper.getLevel();
        FakePlayer p = player(helper, "mc_skills");
        PlayerState s = mod.mana().state(p);

        // 採集：挖鐵礦
        BlockPos ore = new BlockPos(1, 1, 1);
        helper.setBlock(ore, Blocks.IRON_ORE);
        p.gameMode.destroyBlock(helper.absolutePos(ore));
        check(xp(s, Mana.Skill.GATHER) > 0, "挖鐵礦累積採集經驗");
        check(s.found.contains("dex:gather:iron_ore"), "挖鐵礦登錄圖鑑");

        // 種植：收成熟的小麥；沒成熟的不算
        helper.setBlock(new BlockPos(2, 0, 1), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 3));
        p.gameMode.destroyBlock(helper.absolutePos(new BlockPos(2, 1, 1)));
        check(xp(s, Mana.Skill.FARM) == 0, "沒成熟的小麥不算");
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7, 7));
        p.gameMode.destroyBlock(helper.absolutePos(new BlockPos(2, 1, 1)));
        check(xp(s, Mana.Skill.FARM) > 0, "收成熟小麥累積種植經驗");
        check(s.found.contains("dex:crop:wheat"), "小麥登錄圖鑑");

        // 建造：放方塊得點數；馬上拆掉收回
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        ItemStack bricks = new ItemStack(Items.STONE_BRICKS, 4);
        p.setItemInHand(InteractionHand.MAIN_HAND, bricks);
        bricks.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false)));
        check(lvl.getBlockState(base.above()).is(Blocks.STONE_BRICKS), "假玩家放下石磚");
        double built = xp(s, Mana.Skill.BUILD);
        check(built > 0, "放方塊累積建造經驗");
        p.gameMode.destroyBlock(base.above());
        check(xp(s, Mana.Skill.BUILD) < built, "剛放的方塊拆掉會收回建造經驗");

        // 玩家自己放的礦石打掉不算
        ItemStack ores = new ItemStack(Items.COAL_ORE, 1);
        p.setItemInHand(InteractionHand.MAIN_HAND, ores);
        ores.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(base).add(0, 0.5, 0), Direction.UP, base, false)));
        double gather = xp(s, Mana.Skill.GATHER);
        p.gameMode.destroyBlock(base.above());
        check(xp(s, Mana.Skill.GATHER) == gather && !s.found.contains("dex:gather:coal_ore"), "自己放的煤礦打掉不算採集");

        // 烹飪與合成
        new ItemStack(Items.BREAD, 3).onCraftedBy(p, 3);
        check(xp(s, Mana.Skill.COOKING) > 0, "做麵包累積烹飪經驗");
        check(s.found.contains("dex:dish:bread"), "麵包登錄料理圖鑑");
        new ItemStack(Items.CHEST).onCraftedBy(p, 1);
        check(s.found.contains("dex:craft:chest"), "合成箱子登錄合成圖鑑");

        // 畜牧：原版繁殖觸發器
        Animal a = helper.spawn(EntityTypes.COW, new BlockPos(5, 1, 5));
        Animal b = helper.spawn(EntityTypes.COW, new BlockPos(5, 1, 6));
        Animal calf = helper.spawn(EntityTypes.COW, new BlockPos(6, 1, 5));
        calf.setAge(-24000);
        CriteriaTriggers.BRED_ANIMALS.trigger(p, a, b, calf);
        check(xp(s, Mana.Skill.HUSBANDRY) > 0, "繁殖牛累積畜牧經驗");
        check(s.found.contains("dex:animal:cow"), "牛登錄繁殖圖鑑");
        var wolf = helper.spawn(EntityTypes.WOLF, new BlockPos(6, 1, 6));
        wolf.tame(p);
        check(s.found.contains("dex:tame:wolf"), "馴服狼登錄圖鑑");

        // 釣魚
        mod.hooks().onFish(p, List.of(new ItemStack(Items.COD)));
        check(xp(s, Mana.Skill.FISHING) > 0, "釣到鱈魚累積釣魚經驗");
        // 到這裡圖鑑今天已拿滿 15：鱈魚先不登錄，明天再釣仍算第一次
        check(s.today("dex") == mod.config().dayCaps.dex && !s.found.contains("dex:fish:cod"), "圖鑑每日上限：滿了之後不登錄（今天 " + s.today("dex") + "）");

        // 每日上限：手藝一天最多 craft 上限
        check(s.today("craft") <= mod.config().dayCaps.craft, "手藝 mana 不超過每日上限");
        check(s.balance > 0, "做了這些事之後 mana > 0（" + s.balance + "）");

        // 創造模式不算
        FakePlayer c = player(helper, "mc_creative");
        c.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
        new ItemStack(Items.BREAD, 3).onCraftedBy(c, 3);
        check(mod.world().player(c.getUUID()).found.isEmpty(), "創造模式不登錄圖鑑");
        finish(helper);
    }

    /** 原版成就：Fabric 的假玩家拿不到成就、原版的模擬玩家固定是創造模式，所以自己做一個生存模式的玩家 */
    @GameTest(maxTicks = 100)
    public void advancements(GameTestHelper helper) {
        begin("advancements");
        ManaCore mod = ManaCore.get();
        ServerLevel lvl = helper.getLevel();
        ServerPlayer p = new ServerPlayer(lvl.getServer(), lvl, new GameProfile(UUID.nameUUIDFromBytes("mc_adv".getBytes()), "mc_adv"), ClientInformation.createDefault());
        p.connection = new FakePlayerPacketListener(p);
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL); // 測試伺服器預設是創造模式
        mod.world().players.remove(p.getUUID().toString());
        PlayerState s = mod.mana().state(p);
        AdvancementHolder h = helper.getLevel().getServer().getAdvancements().get(Identifier.withDefaultNamespace("story/mine_stone"));
        check(h != null, "找得到成就 story/mine_stone");
        if (h != null) {
            check(!p.isCreative(), "測試玩家是生存模式");
            for (String c : h.value().criteria().keySet()) p.getAdvancements().award(h, c);
            check(s.found.contains("adv:minecraft:story/mine_stone"), "成就完成有記錄");
            check(s.balance == mod.config().advancement.task, "一般成就給 " + mod.config().advancement.task + " mana（實得 " + s.balance + "）");
        }
        AdvancementHolder recipe = helper.getLevel().getServer().getAdvancements().get(Identifier.withDefaultNamespace("recipes/misc/charcoal"));
        if (recipe != null) {
            long b = s.balance;
            for (String c : recipe.value().criteria().keySet()) p.getAdvancements().award(recipe, c);
            check(s.balance == b, "配方解鎖不算成就");
        }
        finish(helper);
    }

    @GameTest(maxTicks = 100)
    public void features(GameTestHelper helper) {
        begin("features");
        ManaCore mod = ManaCore.get();
        Features f = mod.features();
        Config cfg = mod.config();
        FakePlayer p = player(helper, "mc_features");
        PlayerState s = mod.mana().state(p);
        s.balance = 100_000;
        for (Mana.Skill k : Mana.Skill.values()) s.xp.put(k.key, 1e9);

        // 價值表
        long ingot = f.value(Items.IRON_INGOT), block = f.value(Items.IRON_BLOCK);
        check(ingot > 0 && block >= ingot * 9 - 1 && block <= ingot * 9 + 1, "價值表：鐵磚 = 9 × 鐵錠（" + ingot + " / " + block + "）");

        // 祭壇
        Config.AltarCategory cat = cfg.altar.get(0);
        Config.AltarItem ai = cat.items.get(0);
        Item it = Features.item(ai.item);
        int had = Features.count(p, it);
        long bal = s.balance;
        f.buy(p, cat.id, 0, 2);
        check(Features.count(p, it) == had + ai.amount * 2, "祭壇換 2 組 " + ai.item);
        check(s.balance < bal, "祭壇扣了 mana（" + (bal - s.balance) + "）");

        // 煉金爐
        p.getInventory().clearContent();
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 64));
        f.deposit(p, false);
        check(s.alchemy > 0 && s.known.contains("cobblestone"), "煉金爐收鵝卵石並學會（煉金值 " + s.alchemy + "）");
        long av = s.alchemy;
        f.withdraw(p, "cobblestone", 10);
        check(Features.count(p, Items.COBBLESTONE) == Math.min(10, av / f.value(Items.COBBLESTONE)), "煉金爐換出鵝卵石");
        check(!f.withdraw(p, "diamond", 1), "沒學會的物品不能換");

        // 城鎮訂單
        List<Config.OrderDef> orders = f.todaysOrders(p);
        check(orders.size() == cfg.orders.perDay, "今天有 " + orders.size() + " 張訂單");
        Config.OrderDef o = orders.get(0);
        Features.give(p, Features.item(o.item), o.amount);
        long ob = s.balance;
        f.deliverOrder(p, o.id);
        check(s.ordersToday.contains(o.id) && s.balance > ob, "交訂單 " + o.id + " 得到 mana");
        check(!f.deliverOrder(p, o.id), "同一張訂單一天只能交一次");

        // 村民委託
        Villager v = helper.spawn(EntityTypes.VILLAGER, new BlockPos(2, 1, 2));
        WorldState.VillagerRequest r = new WorldState.VillagerRequest();
        r.item = "bread";
        r.amount = 3;
        r.reward = 4;
        r.day = s.day;
        r.player = p.getUUID().toString();
        mod.world().requests.put(v.getUUID().toString(), r);
        check(f.interactVillager(p, v) && mod.world().requests.containsKey(v.getUUID().toString()), "東西不夠時只顯示委託內容");
        Features.give(p, Items.BREAD, 3);
        long vb = s.today("villager");
        f.interactVillager(p, v);
        check(!mod.world().requests.containsKey(v.getUUID().toString()) && s.today("villager") > vb, "交村民委託得到 mana");

        // 工程
        Config.Project pr = cfg.projects.get(0);
        for (Map.Entry<String, Integer> e : pr.needs.entrySet()) {
            Features.give(p, Features.item(e.getKey()), e.getValue());
            f.donate(p, pr.id, e.getKey());
        }
        f.invest(p, pr.id, pr.mana);
        check(s.projectsDone.contains(pr.id), "工程「" + pr.name + "」完工");

        // 選單資料
        String json = Net.snapshot(mod, p).toString();
        check(json.contains("\"orders\"") && json.contains("\"projects\"") && json.contains("\"alchemy\""), "選單資料完整（" + json.length() + " 字元）");
        finish(helper);
    }
}
