#!/usr/bin/env python3
"""
ManaCore 1.3.0 經濟平衡試算（不需要伺服器）。

用和插件相同的公式（新鮮度、多樣性、結伴、每日上限）與 config.yml 預設值，
推算幾種玩家「玩一天」的 mana 來源。數值是估計，用來比較玩法之間的相對收入，
以及確認「刷」比「換著玩」划不來。改了 config 的數值後，同步改下面的 CFG 再跑一次。

用法：python3 economy_model.py
"""

CFG = {
    "points_per_mana": 10,
    "per_skill_cap": 10,
    "craft_cap": 30,
    "decay": 0.005, "floor": 0.3, "recover": 0.01,
    "var_window": 32, "var_base": 0.5, "var_per": 0.125,
    "companion_bonus": 0.25,
    "dex_cap": 30, "order_cap": 40, "help_cap": 15, "challenge_cap": 60, "project_cap": 20,
}


class Skill:
    """一種手藝的狀態：新鮮度、多樣性視窗、點數累積、今日 mana"""

    def __init__(self):
        self.fresh = 1.0
        self.last_min = 0.0
        self.recent = []
        self.acc = 0.0
        self.mana = 0

    def act(self, t_min, raw, item, companions, craft_total):
        c = CFG
        self.fresh = min(1.0, self.fresh + c["recover"] * (t_min - self.last_min))
        self.last_min = t_min
        self.recent.append(item)
        self.recent = self.recent[-c["var_window"]:]
        vf = min(1.0, c["var_base"] + c["var_per"] * len(set(self.recent)))
        cf = 1 + c["companion_bonus"] if companions else 1.0
        eff = raw * vf * cf * self.fresh
        self.fresh = max(c["floor"], self.fresh - eff * c["decay"])
        if self.mana >= c["per_skill_cap"] or craft_total[0] >= c["craft_cap"]:
            return
        self.acc += eff
        n = int(self.acc // c["points_per_mana"])
        n = min(n, c["per_skill_cap"] - self.mana, c["craft_cap"] - craft_total[0])
        if n > 0:
            self.acc -= n * c["points_per_mana"]
            self.mana += n
            craft_total[0] += n


def session(blocks, companions=False):
    """blocks: [(手藝, 分鐘, 每分鐘動作數, 每次點數, [輪流做的東西])] 依序進行"""
    skills, total, t = {}, [0], 0.0
    for name, minutes, per_min, raw, items in blocks:
        sk = skills.setdefault(name, Skill())
        n = int(minutes * per_min)
        for i in range(n):
            sk.act(t + i / per_min, raw, items[i % len(items)], companions, total)
        t += minutes
    return {k: v.mana for k, v in skills.items()}, total[0]


def cap(v, key):
    return min(v, CFG[key])


ARCHETYPES = []

# 1) 內向・田園型：種田、煮飯、釣魚、交訂單（不太說話）
crafts, ct = session([
    ("種植", 25, 6, 1, ["wheat", "carrot", "potato", "beetroot"]),   # 收成 4 種作物共約 150 株
    ("烹飪", 20, 3, 1, ["bread", "baked_potato", "pumpkin_pie"]),    # 做麵包、烤馬鈴薯、派
    ("釣魚", 30, 2.5, 3, ["cod", "salmon", "cod", "tropical", "junk"]),
])
ARCHETYPES.append(("內向・田園（2 小時，獨自）", {
    "生活手藝": ct, "圖鑑": cap(8, "dex_cap"), "城鎮訂單": cap(3 * 2 * 4, "order_cap"),
    "探索": 6, "訂單完成獎勵": 3,
}, crafts))

# 2) 內向・礦工建築型：挖礦、砍樹、蓋房子
crafts, ct = session([
    ("採集", 40, 3, 1.3, ["coal", "iron", "copper", "redstone", "oak_log", "lapis"]),
    ("建造", 50, 12, 0.25, ["stone_bricks", "oak_planks", "glass", "spruce_stairs", "lantern", "bricks", "andesite"]),
])
ARCHETYPES.append(("內向・礦工建築（1.5 小時，獨自）", {
    "生活手藝": ct, "圖鑑": cap(6, "dex_cap"), "城鎮訂單": cap(2 * 2 * 4, "order_cap"),
    "作品被參觀": 6, "社區工程捐物資": cap(6, "project_cap"),
}, crafts))

# 3) 刷子：自動農場只收小麥，1 小時收 3000 株
crafts, ct = session([("種植", 60, 50, 1, ["wheat"])])
ARCHETYPES.append(("刷子・單一作物農場（1 小時）", {
    "生活手藝": ct, "城鎮訂單": cap(1 * 2 * 3, "order_cap"),
}, crafts))

# 4) 外向・社交型：聊天、感謝、帶新手、接委託、和朋友一起種田釣魚
crafts, ct = session([
    ("種植", 20, 5, 1, ["wheat", "carrot", "potato"]),
    ("釣魚", 20, 2.5, 3, ["cod", "salmon", "junk"]),
], companions=True)
ARCHETYPES.append(("外向・社交（2 小時，常和朋友一起）", {
    "生活手藝（結伴）": ct, "聊天互動": 20, "被感謝": 10, "助人委託": cap(2 * 3, "help_cap"),
    "委託酬謝（他人支付）": 15, "城鎮訂單": cap(2 * 2 * 4, "order_cap"),
}, crafts))

# 5) 冒險隊：4 人週末打終界龍＋突襲
ARCHETYPES.append(("冒險隊・4 人週末（打龍＋突襲）", {
    "合作挑戰：終界龍 30×1.75": 53, "合作挑戰：突襲 15×1.75": 26, "探索": 20, "圖鑑（首領）": 10,
}, {}))

# 6) 對照：1.2.0 的同一位田園型玩家（收成點數 16 點換 1 mana、上限 15，4 分鐘拿滿；釣魚與烹飪無收入）
ARCHETYPES.append(("對照・1.2.0 田園型（同樣 2 小時）", {"生產收成": 15, "探索": 6}, {}))


def main():
    print("ManaCore 1.3.0 經濟試算（預設值；單位 mana／天）\n")
    for name, src, crafts in ARCHETYPES:
        total = sum(src.values())
        print(f"■ {name}：合計 {total}")
        for k, v in src.items():
            print(f"    {k:<24}{v:>5}")
        if crafts:
            print("    （手藝明細：" + "、".join(f"{k} {v}" for k, v in crafts.items()) + "）")
        print()

    # 新鮮度效果：同一種手藝連續做 N 分鐘，每分鐘的有效點數
    print("■ 新鮮度：每分鐘做 30 次 1 點的動作（單一手藝、4 種東西輪流）")
    sk, tot = Skill(), [0]
    for m in range(0, 60):
        before = sk.fresh
        for i in range(30):
            sk.act(m + i / 30, 1, ["a", "b", "c", "d"][i % 4], False, tot)
        if m in (0, 5, 10, 20, 40, 59):
            print(f"    第 {m:>2} 分鐘：新鮮度 {before:.0%}")
    rest = min(1.0, sk.fresh + CFG["recover"] * 30)
    print(f"    休息 30 分鐘後：{rest:.0%}")


if __name__ == "__main__":
    main()
