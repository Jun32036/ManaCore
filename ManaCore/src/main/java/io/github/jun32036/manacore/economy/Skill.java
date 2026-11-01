package io.github.jun32036.manacore.economy;

import org.bukkit.Material;

/** 六種生活手藝。key 寫進資料庫與設定檔，不要改。 */
public enum Skill {
    GATHER("gather", "採集", Material.IRON_PICKAXE, "挖礦、砍樹"),
    FARM("farm", "種植", Material.WHEAT, "收成成熟的作物、南瓜西瓜、甘蔗"),
    HUSBANDRY("husbandry", "畜牧", Material.LEAD, "繁殖、剪毛、馴服動物"),
    FISHING("fishing", "釣魚", Material.FISHING_ROD, "釣到魚和寶物"),
    COOKING("cooking", "烹飪", Material.SMOKER, "烤熟食物、做料理"),
    BUILD("build", "建造", Material.BRICKS, "放置方塊蓋東西（越多樣越好）");

    public final String key;
    public final String display;
    public final Material icon;
    public final String how;

    Skill(String key, String display, Material icon, String how) {
        this.key = key;
        this.display = display;
        this.icon = icon;
        this.how = how;
    }

    public static Skill byKey(String key) {
        if (key == null) return null;
        for (Skill s : values()) if (s.key.equalsIgnoreCase(key)) return s;
        return null;
    }
}
