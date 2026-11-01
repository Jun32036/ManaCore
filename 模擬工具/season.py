"""用模擬實測的「每分鐘產出」推算一整季（30 天）的 mana 經濟。

假設（在報告中列出）：
- 每類玩家每天上線時間不同（SESSION）
- 每天的產出 = min(每日上限, 每分鐘產出 × 上線分鐘)
- 每人每天平均在祭壇花 SPEND mana（模擬時間太短，花費以假設值代入）
- 歸土：超過 500 的部分每天 5%
"""
import json, sys
from collections import defaultdict

summary = json.load(open(sys.argv[1], encoding='utf-8'))
label = sys.argv[2] if len(sys.argv) > 2 else ''
caps = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {'chat': 30, 'thanks': 25, 'works': 20, 'explore': 30, 'harvest': 15, 'mentor': 20}
SESSION = {'social': 90, 'builder': 90, 'explorer': 60, 'farmer': 60, 'ring': 90, 'spammer': 60, 'idle': 240, 'newbie': 45}
SPEND, DAYS, TH, RATE = 15, 30, 500, 0.05
PAIRWEEK = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
EXPLORE_TOTAL = 600   # 一個人一季能探索到的新地點大約上限（生態域約 60 種 + 結構與地標）

mins = summary['minutes']
typ = lambda n: ''.join(c for c in n if not c.isdigit()).lower()
per_min = defaultdict(lambda: defaultdict(list))
for n, src in summary['earn'].items():
    for s, v in src.items():
        if s in caps and v > 0: per_min[typ(n)][s].append(v / mins)
types = sorted({typ(n) for n in summary['bal']})

def daily(t):
    out = {}
    for s, cap in caps.items():
        vals = per_min[t].get(s, [])
        r = sum(vals) / max(1, len([n for n in summary['bal'] if typ(n) == t])) if vals else 0
        out[s] = min(cap, r * SESSION.get(t, 60))
    # 探索是一次性的發現：模擬裡用傳送加速，非探索型玩家每天只算少量新地點
    if t != 'explorer': out['explore'] = 0 if t == 'idle' else min(out['explore'], 3)
    # 修正版：同一對玩家 7 天內只有 3 天的感謝/留言產生 mana（固定小圈圈只剩 3/7）
    for s, f in PAIRWEEK.get(t, {}).items(): out[s] *= f
    return out

bal = {t: 0.0 for t in types}; explored = {t: 0.0 for t in types}; tree = 0.0
print(f"== 一季推算 {label} ==")
print('類型'.ljust(10) + '每天產出  其中聊天  其中感謝  第30天餘額  每天被歸土')
rows = {}
for day in range(1, DAYS + 1):
    for t in types:
        d = daily(t)
        if explored[t] >= EXPLORE_TOTAL: d['explore'] = 0
        explored[t] += d['explore']
        gain = sum(d.values())
        bal[t] = max(0, bal[t] + gain - min(SPEND, bal[t] + gain))
        decay = max(0, bal[t] - TH) * RATE
        bal[t] -= decay; tree += decay
        rows[t] = (gain, d['chat'], d['thanks'], bal[t], decay)
for t in types:
    g, c, th, b, dc = rows[t]
    print(f"{t:<10}{g:8.1f}{c:10.1f}{th:10.1f}{b:12.0f}{dc:12.1f}")
top = max(rows[t][0] for t in types if t != 'idle'); low = min(rows[t][0] for t in types if t not in ('idle', 'spammer'))
print(f"最高與最低的每日產出差 {top / max(0.1, low):.1f} 倍；生命之樹一季累積 {tree:.0f}")
