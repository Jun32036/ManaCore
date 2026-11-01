package io.github.jun32036.manacore.economy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 價值表的推算規則（用字串代替物品，不需要伺服器） */
class ValueTableTest {

    private static ValueTable.Rec<String> rec(String result, int count, List<List<String>> ings) {
        return new ValueTable.Rec<>(result, count, ings);
    }

    @Test
    void derivesFromRecipesTakingCheapestPath() {
        Map<String, Long> base = Map.of("log", 19L, "cobblestone", 6L, "iron_ingot", 25L, "stick", 3L);
        List<ValueTable.Rec<String>> recs = List.of(
                rec("planks", 4, List.of(List.of("log"))),                                   // 19/4 → 5
                rec("stick", 4, List.of(List.of("planks"), List.of("planks"))),              // 基礎值優先，不覆蓋
                rec("stone", 1, List.of(List.of("cobblestone"))),                            // 燒製 → 6
                rec("stone_bricks", 4, List.of(List.of("stone"), List.of("stone"), List.of("stone"), List.of("stone"))), // 6
                rec("stone_bricks", 1, List.of(List.of("stone"))),                           // 切石：1 → 1，更便宜 → 6
                rec("iron_pickaxe", 1, List.of(List.of("iron_ingot"), List.of("iron_ingot"), List.of("iron_ingot"), List.of("stick"), List.of("stick"))),
                rec("chest", 1, List.of(List.of("planks", "log"), List.of("planks", "log"))), // 可選材料取最便宜 → 10
                rec("mystery", 1, List.of(List.of("unobtainium")))                           // 材料沒價值 → 沒有價值
        );
        Map<String, Long> v = ValueTable.derive(base, recs, 25);
        assertEquals(5, v.get("planks"));
        assertEquals(3, v.get("stick"), "基礎值優先");
        assertEquals(6, v.get("stone"));
        assertEquals(6, v.get("stone_bricks"), "有多種做法時取最便宜");
        assertEquals(81, v.get("iron_pickaxe"));
        assertEquals(10, v.get("chest"));
        assertFalse(v.containsKey("mystery"));
    }

    @Test
    void uncraftingNeverGainsValue() {
        // 任何物品的價值 ≤ 任一配方的材料總和，所以「拆解」拿不回比最便宜配方更多的材料
        Map<String, Long> base = Map.of("a", 10L, "b", 30L);
        List<ValueTable.Rec<String>> recs = List.of(
                rec("x", 1, List.of(List.of("a"), List.of("a"))),
                rec("x", 1, List.of(List.of("b"))),
                rec("y", 2, List.of(List.of("x"), List.of("x"), List.of("x"))));
        Map<String, Long> v = ValueTable.derive(base, recs, 25);
        assertEquals(20, v.get("x"));
        assertEquals(30, v.get("y"));
    }
}
