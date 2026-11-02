package io.github.jun32036.manacore.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 價值表推算（純計算）：基礎物資的價值寫在設定檔，其他物品從配方推算，取最便宜的做法。
 * 這樣拆解一樣東西，最多只能拿回最便宜配方的材料，不會憑空多出價值。
 */
public final class ValueDerive {

    private ValueDerive() {}

    /** 一條配方：result 產出 count 個，每個材料格是一組可選材料（取最便宜） */
    public record Rec<K>(K result, int count, List<List<K>> ingredients) {}

    public static <K> Map<K, Long> derive(Map<K, Long> base, List<Rec<K>> recs, int maxPasses) {
        Map<K, Long> v = new HashMap<>(base);
        for (int pass = 0; pass < Math.max(1, maxPasses); pass++) {
            boolean changed = false;
            for (Rec<K> r : recs) {
                if (base.containsKey(r.result()) || r.count() <= 0) continue;
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
                if (!ok || sum <= 0) continue;
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
}
