package io.github.jun32036.manacore;

import io.github.jun32036.manacore.data.Config;
import io.github.jun32036.manacore.data.PlayerState;
import io.github.jun32036.manacore.logic.EconMath;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * mana 的產生與花費。「一天」是遊戲內的一天。
 *
 * 核心原則：每個動作都有一點價值，但同一件事一直做會越來越不划算（新鮮度、多樣性、每日上限），
 * 嘗試新東西（圖鑑、探索、成就）和完成有目標的事（訂單、村民委託、工程）才是主要收入。
 */
public final class Mana {

    /** mana 的來源；key 寫進存檔 */
    public enum Source {
        CRAFT("craft", "生活手藝"), DEX("dex", "圖鑑"), ADVANCEMENT("advancement", "成就"), EXPLORE("explore", "探索"),
        ORDER("order", "城鎮訂單"), VILLAGER("villager", "村民委託"), PROJECT("project", "工程完工");

        public final String key, display;

        Source(String key, String display) {
            this.key = key;
            this.display = display;
        }
    }

    public enum Skill {
        GATHER("gather", "採集", "iron_pickaxe", "挖礦、砍樹"),
        FARM("farm", "種植", "wheat", "收成成熟的作物、南瓜西瓜、甘蔗"),
        HUSBANDRY("husbandry", "畜牧", "lead", "繁殖、馴服動物"),
        FISHING("fishing", "釣魚", "fishing_rod", "釣到魚和寶物"),
        COOKING("cooking", "烹飪", "smoker", "烤熟食物、做料理"),
        BUILD("build", "建造", "bricks", "放置方塊蓋東西（越多樣越好）");

        public final String key, display, icon, how;

        Skill(String key, String display, String icon, String how) {
            this.key = key;
            this.display = display;
            this.icon = icon;
            this.how = how;
        }
    }

    private final ManaCore mod;
    /** 新鮮度與多樣性只放在記憶體（重新進入世界會恢復，單人遊戲可以接受） */
    private final Map<UUID, Map<Skill, EconMath.Freshness>> freshness = new HashMap<>();
    private final Map<UUID, Map<Skill, EconMath.Variety>> variety = new HashMap<>();

    public Mana(ManaCore mod) {
        this.mod = mod;
    }

    private Config cfg() {
        return mod.config();
    }

    public long day(ServerPlayer p) {
        return Math.floorDiv(p.level().getOverworldClockTime(), 24000L);
    }

    public PlayerState state(ServerPlayer p) {
        PlayerState s = mod.world().player(p.getUUID());
        s.rollDay(day(p));
        return s;
    }

    public long cap(Source s) {
        Config.DayCaps c = cfg().dayCaps;
        return switch (s) {
            case CRAFT -> c.craft;
            case DEX -> c.dex;
            case EXPLORE -> c.explore;
            case ORDER -> c.order;
            case VILLAGER -> c.villager;
            default -> 0; // 成就、工程完工：一次性，不設上限
        };
    }

    /** 今天還能拿多少（-1 = 不限） */
    public long remaining(PlayerState s, Source src) {
        long cap = cap(src);
        if (cap <= 0) return -1;
        return Math.max(0, cap - s.today(src.key));
    }

    // ======================================================================
    // 產生與花費
    // ======================================================================

    /** @return 實際得到的數量（受每日上限） */
    public long earn(ServerPlayer p, Source src, long amount, Component reason) {
        if (amount <= 0) return 0;
        PlayerState s = state(p);
        long rem = remaining(s, src);
        long got = rem < 0 ? amount : Math.min(amount, rem);
        if (got <= 0) return 0;
        s.balance += got;
        s.today.merge(src.key, got, Long::sum);
        s.addLog("+" + got + " " + src.display + "：" + reason.getString());
        MutableComponent line = Component.literal("◆ +" + got + " mana · ").withStyle(ChatFormatting.AQUA).append(reason.copy().withStyle(ChatFormatting.GRAY));
        p.sendSystemMessage(line, true);
        mod.markDirty();
        return got;
    }

    public boolean spend(ServerPlayer p, long amount, String what) {
        PlayerState s = state(p);
        if (amount <= 0) return true;
        if (s.balance < amount) {
            p.sendSystemMessage(Component.literal("mana 不夠：需要 " + amount + "，你有 " + s.balance).withStyle(ChatFormatting.RED));
            return false;
        }
        s.balance -= amount;
        s.addLog("-" + amount + " " + what);
        mod.markDirty();
        return true;
    }

    // ======================================================================
    // 生活手藝
    // ======================================================================

    public int level(PlayerState s, Skill k) {
        return EconMath.level(s.xp.getOrDefault(k.key, 0.0), cfg().craft.levels);
    }

    public String title(int level) {
        List<String> t = cfg().craft.titles;
        return t.isEmpty() ? "" : t.get(Math.max(0, Math.min(t.size() - 1, level - 1)));
    }

    public double freshness(UUID id, Skill k) {
        return fresh(id, k).peek(System.currentTimeMillis(), cfg().craft.freshness.recoverPerMinute);
    }

    public double varietyFactor(UUID id, Skill k) {
        return var(id, k).factor(cfg().craft.variety.base, cfg().craft.variety.perDistinct);
    }

    private EconMath.Freshness fresh(UUID id, Skill k) {
        return freshness.computeIfAbsent(id, x -> new EnumMap<>(Skill.class)).computeIfAbsent(k, x -> new EconMath.Freshness(System.currentTimeMillis()));
    }

    private EconMath.Variety var(UUID id, Skill k) {
        return variety.computeIfAbsent(id, x -> new EnumMap<>(Skill.class)).computeIfAbsent(k, x -> new EconMath.Variety(cfg().craft.variety.window));
    }

    public double points(String key, double def) {
        return cfg().craft.points.getOrDefault(key, def);
    }

    /**
     * 做了一次手藝動作。點數 = 基礎 × 多樣性 × 新鮮度；累積滿 pointsPerMana 換 1 mana。
     * @return 有效點數（建造用來在拆掉時收回）
     */
    public double craft(ServerPlayer p, Skill k, double raw, String itemKey) {
        if (raw <= 0 || p.isCreative()) return 0;
        PlayerState s = state(p);
        Config.Craft c = cfg().craft;
        EconMath.Variety v = var(p.getUUID(), k);
        v.record(itemKey);
        double eff = fresh(p.getUUID(), k).consume(raw * v.factor(c.variety.base, c.variety.perDistinct), System.currentTimeMillis(),
                c.freshness.recoverPerMinute, c.freshness.decayPerPoint, c.freshness.floor);
        addXp(p, s, k, eff);
        long perSkill = cfg().dayCaps.craftPerSkill;
        long skillRem = perSkill <= 0 ? Long.MAX_VALUE : Math.max(0, perSkill - s.skillToday.getOrDefault(k.key, 0L));
        long totalRem = remaining(s, Source.CRAFT);
        if (skillRem == 0 || totalRem == 0) return eff; // 今天滿了：只累積經驗
        double acc = s.points.getOrDefault(k.key, 0.0) + eff;
        long n = (long) Math.floor(acc / c.pointsPerMana);
        if (n > 0) {
            n = Math.min(n, skillRem);
            if (totalRem > 0) n = Math.min(n, totalRem);
            long got = earn(p, Source.CRAFT, n, Component.literal("生活手藝・" + k.display));
            acc -= got * c.pointsPerMana;
            s.skillToday.merge(k.key, got, Long::sum);
        }
        s.points.put(k.key, acc);
        mod.markDirty();
        return eff;
    }

    /** 收回點數（剛放的方塊馬上拆掉） */
    public void revoke(ServerPlayer p, Skill k, double eff) {
        PlayerState s = state(p);
        s.points.merge(k.key, -eff, Double::sum);
        s.xp.put(k.key, Math.max(0, s.xp.getOrDefault(k.key, 0.0) - eff));
    }

    public void addXp(ServerPlayer p, PlayerState s, Skill k, double xp) {
        int before = level(s, k);
        s.xp.merge(k.key, xp, Double::sum);
        int after = level(s, k);
        if (after > before) {
            p.sendSystemMessage(Component.literal("✦ " + k.display + " 升到 Lv" + after + "「" + title(after) + "」！").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" 被動加成提高了。").withStyle(ChatFormatting.GRAY)));
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
    }

    /** 被動加成比例 */
    public double passive(PlayerState s, Skill k) {
        Config.Passive pv = cfg().craft.passives.get(k.key);
        if (pv == null) return 0;
        return Math.max(0, Math.min(pv.max, pv.perLevel * (level(s, k) - 1)));
    }

    public boolean roll(ServerPlayer p, Skill k) {
        double b = passive(state(p), k);
        return b > 0 && ThreadLocalRandom.current().nextDouble() < b;
    }

    // ======================================================================
    // 圖鑑、探索、成就（一次性）
    // ======================================================================

    public static final Map<String, String> DEX_NAMES = Map.of(
            "fish", "魚獲與寶物", "crop", "作物", "animal", "繁殖過的動物", "tame", "馴服的夥伴",
            "dish", "料理", "gather", "礦物與木材", "craft", "合成過的物品");

    public void discover(ServerPlayer p, String category, String id, Component name) {
        if (p.isCreative()) return;
        PlayerState s = state(p);
        String key = "dex:" + category + ":" + id;
        if (s.found.contains(key)) return;
        long amount = cfg().dex.amounts.getOrDefault(category, 1L);
        if ("craft".equals(category)) {
            long left = cfg().dex.craftPerDay - s.dexCraftToday;
            if (left <= 0) return; // 今天合成類滿了：明天再做一次仍算第一次
            amount = Math.min(amount, left);
        }
        long got = earn(p, Source.DEX, amount, Component.literal("圖鑑新登錄【" + DEX_NAMES.getOrDefault(category, category) + "】").append(name));
        if (got <= 0) return;
        if ("craft".equals(category)) s.dexCraftToday += got;
        s.found.add(key);
        p.level().playSound(null, p.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5f, 1.6f);
        int every = cfg().dex.milestoneEvery;
        if (every > 0 && !"craft".equals(category)) {
            long n = s.found.stream().filter(x -> x.startsWith("dex:" + category + ":")).count();
            if (n % every == 0) earn(p, Source.ADVANCEMENT, cfg().dex.milestoneBonus, Component.literal("圖鑑【" + DEX_NAMES.get(category) + "】收集滿 " + n + " 種"));
        }
    }

    public void explore(ServerPlayer p, String key, long amount, Component what) {
        PlayerState s = state(p);
        if (amount <= 0 || s.found.contains(key) || p.isCreative()) return;
        if (earn(p, Source.EXPLORE, amount, what) > 0) s.found.add(key);
    }
}
