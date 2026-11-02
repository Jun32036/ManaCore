#!/usr/bin/env python3
"""從伺服器插件的 config.yml 取出訂單池、祭壇品項、價值表，產生單人模組的預設設定 manacore-defaults.json。"""
import json, sys, yaml
src = yaml.safe_load(open(sys.argv[1], encoding='utf-8'))
out_path = sys.argv[2]
low = lambda m: m.lower()

pool = []
for oid, o in src['sources']['order']['pool'].items():
    pool.append({'id': oid, 'item': low(o['item']), 'amount': o['amount'], 'reward': o['reward'], 'skill': o['skill'], 'level': o.get('level', 1)})

altar = []
for cid, c in src['altar']['shop'].items():
    altar.append({'id': cid, 'name': c['name'], 'icon': low(c['icon']),
                  'discount': cid in src['altar'].get('discount-categories', []),
                  'items': [{'item': low(v['material']), 'amount': v['amount'], 'price': v['price']} for v in c['items'].values()]})

values = {low(k): v for k, v in src['values']['base'].items()}
craft = src['sources']['craft']
pts = dict(craft['points']); ores = pts.pop('ores'); dishes = pts.pop('dishes')

cfg = {
    "_說明": "ManaCore 單人模組設定。「一天」是遊戲內的一天（20 分鐘）。改完存檔，重新進入世界生效。",
    "dayCaps": {"craft": 12, "craftPerSkill": 5, "dex": 15, "order": 24, "explore": 15, "villager": 12},
    "craft": {
        "pointsPerMana": craft['points-per-mana'],
        "freshness": {"decayPerPoint": craft['freshness']['decay-per-point'], "floor": craft['freshness']['floor'], "recoverPerMinute": 0.02},
        "variety": {"window": craft['variety']['window'], "base": craft['variety']['base'], "perDistinct": craft['variety']['per-distinct']},
        "levels": craft['levels'], "titles": craft['titles'],
        "points": pts, "ores": ores, "dishes": dishes,
        "passives": {k: {"perLevel": v['per-level'], "max": v['max']} for k, v in craft['passives'].items() if isinstance(v, dict)}
    },
    "dex": {"amounts": src['sources']['dex']['amounts'], "craftPerDay": 4, "milestoneEvery": 10, "milestoneBonus": 5},
    "advancement": {"task": 5, "goal": 15, "challenge": 40, "overrides": src['sources']['advancement']['overrides']},
    "explore": {"biome": 3, "structure": 5, "structureOverrides": src['sources']['explore']['structure-overrides']},
    "orders": {"perDay": 4, "maxPerSkill": 1, "pool": pool},
    "villager": {
        "chancePerDay": 0.5, "maxActive": 3, "radius": 48,
        "requests": {
            "farmer": [{"item": "bread", "amount": 6, "reward": 4}, {"item": "pumpkin_pie", "amount": 3, "reward": 5}, {"item": "bone_meal", "amount": 16, "reward": 3}],
            "fisherman": [{"item": "cooked_cod", "amount": 6, "reward": 4}, {"item": "cooked_salmon", "amount": 4, "reward": 5}, {"item": "string", "amount": 10, "reward": 3}],
            "shepherd": [{"item": "white_wool", "amount": 12, "reward": 4}, {"item": "shears", "amount": 1, "reward": 3}],
            "butcher": [{"item": "cooked_beef", "amount": 6, "reward": 4}, {"item": "cooked_porkchop", "amount": 6, "reward": 4}, {"item": "coal", "amount": 12, "reward": 3}],
            "librarian": [{"item": "paper", "amount": 18, "reward": 4}, {"item": "book", "amount": 3, "reward": 5}, {"item": "bookshelf", "amount": 1, "reward": 5}],
            "mason": [{"item": "stone_bricks", "amount": 32, "reward": 4}, {"item": "bricks", "amount": 16, "reward": 4}, {"item": "polished_andesite", "amount": 32, "reward": 4}],
            "toolsmith": [{"item": "iron_ingot", "amount": 6, "reward": 5}, {"item": "iron_pickaxe", "amount": 1, "reward": 5}],
            "weaponsmith": [{"item": "iron_ingot", "amount": 6, "reward": 5}, {"item": "flint", "amount": 8, "reward": 3}],
            "armorer": [{"item": "iron_ingot", "amount": 8, "reward": 5}, {"item": "lava_bucket", "amount": 1, "reward": 4}],
            "cleric": [{"item": "rotten_flesh", "amount": 16, "reward": 3}, {"item": "glass_bottle", "amount": 6, "reward": 3}, {"item": "redstone", "amount": 12, "reward": 4}],
            "cartographer": [{"item": "paper", "amount": 18, "reward": 4}, {"item": "glass_pane", "amount": 12, "reward": 4}],
            "fletcher": [{"item": "stick", "amount": 32, "reward": 3}, {"item": "feather", "amount": 12, "reward": 4}, {"item": "arrow", "amount": 16, "reward": 4}],
            "leatherworker": [{"item": "leather", "amount": 6, "reward": 5}],
            "none": [{"item": "apple", "amount": 4, "reward": 3}, {"item": "torch", "amount": 16, "reward": 3}],
            "nitwit": [{"item": "cookie", "amount": 8, "reward": 3}]
        }
    },
    "altar": altar,
    "alchemy": {"lossPercent": 10, "learnRequired": True},
    "values": values,
    "projects": [
        {"id": "watchtower", "name": "瞭望塔", "description": "在家附近蓋一座瞭望塔。完工獎勵：望遠鏡、指南針與 mana。",
         "icon": "spyglass", "needs": {"stone_bricks": 192, "oak_log": 64, "lantern": 8}, "mana": 30, "reward": {"mana": 60, "items": {"spyglass": 1, "compass": 1}}},
        {"id": "farmstead", "name": "豐收農莊", "description": "建一座像樣的農莊。完工獎勵：骨粉與種子禮包。",
         "icon": "hay_block", "needs": {"hay_block": 32, "pumpkin": 16, "white_wool": 32, "oak_fence": 64}, "mana": 30, "reward": {"mana": 80, "items": {"bone_meal": 64, "pumpkin_seeds": 8, "melon_seeds": 8}}},
        {"id": "harbor", "name": "港口與燈塔", "description": "在水邊蓋一座港口。完工獎勵：海晶燈、三叉戟。",
         "icon": "sea_lantern", "needs": {"prismarine": 128, "spruce_planks": 192, "cooked_salmon": 32}, "mana": 80, "reward": {"mana": 120, "items": {"sea_lantern": 16, "trident": 1}}},
        {"id": "library", "name": "大圖書館", "description": "收藏知識的圖書館。完工獎勵：附魔台組。",
         "icon": "bookshelf", "needs": {"bookshelf": 32, "paper": 128, "lantern": 16}, "mana": 100, "reward": {"mana": 150, "items": {"enchanting_table": 1, "experience_bottle": 32}}}
    ]
}
json.dump(cfg, open(out_path, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('pool', len(pool), 'altar', sum(len(c['items']) for c in altar), 'values', len(values))
