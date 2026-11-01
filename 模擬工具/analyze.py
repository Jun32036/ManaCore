"""分析模擬結果：每類玩家的 mana 來源、達上限時間、聊天效率、互刷收益、花費。"""
import json, sqlite3, sys, statistics as st
from collections import defaultdict

DB, LOG = sys.argv[1], sys.argv[2]
db = sqlite3.connect(DB)
names = {u: n for u, n in db.execute("select uuid, name from accounts")}
typ = lambda n: ''.join(c for c in n if not c.isdigit()).lower()

events = [json.loads(l) for l in open(LOG, encoding='utf-8') if l.strip()]
start = next(e['t'] for e in events if e.get('ev') == 'start')
end = next((e['t'] for e in events if e.get('ev') == 'end'), max(e['t'] for e in events))
minutes = (end - start) / 60000

rows = db.execute("select ts, uuid, delta, source, detail from ledger order by id").fetchall()
earn = defaultdict(lambda: defaultdict(int))
first_cap = {}
CAPS = {'chat': 30, 'thanks': 25, 'works': 20, 'explore': 30, 'harvest': 15, 'mentor': 20}
running = defaultdict(lambda: defaultdict(int))
chat_credit_per_msg = defaultdict(int)   # 一則接話訊息讓幾個人拿到 chat mana
for ts, u, d, s, det in rows:
    n = names.get(u, u)
    if ts < start - 120000: continue
    earn[n][s] += d
    if d > 0 and s in CAPS:
        running[n][s] += d
        if running[n][s] >= CAPS[s] and (n, s) not in first_cap:
            first_cap[(n, s)] = (ts - start) / 60000
    if s == 'chat' and det and det.startswith('接話者 '):
        chat_credit_per_msg[(det, ts // 1500)] += 1

chats = defaultdict(int)
for e in events:
    if e.get('ev') == 'chat': chats[e['who']] += 1

bal = {names[u]: b for u, b in db.execute("select uuid, balance from accounts")}
types = defaultdict(list)
for n in bal: types[typ(n)].append(n)

print(f"模擬長度：{minutes:.1f} 分鐘，玩家 {len(bal)} 人\n")
print("== 每類玩家平均（mana）==")
src = ['chat', 'thanks', 'works', 'explore', 'harvest', 'mentor', 'gift_in', 'gift_out', 'purchase', 'admin']
print('類型'.ljust(10) + ''.join(s.ljust(9) for s in src) + '餘額     發言數')
for t, ns in sorted(types.items()):
    avg = lambda s: st.mean(earn[n][s] for n in ns)
    print(t.ljust(10) + ''.join(f"{avg(s):<9.1f}" for s in src) + f"{st.mean(bal[n] for n in ns):<9.1f}{st.mean(chats[n] for n in ns):.0f}")

print("\n== 碰到每日上限的時間（分鐘，從模擬開始算）==")
for (n, s), m in sorted(first_cap.items(), key=lambda x: x[1]):
    print(f"  {n:<11}{s:<8}{m:5.1f}")

print("\n== 聊天 ==")
for t, ns in sorted(types.items()):
    msgs = sum(chats[n] for n in ns); got = sum(earn[n]['chat'] for n in ns)
    if msgs: print(f"  {t:<10}發言 {msgs:4d} 則，拿到聊天 mana {got:4d}，每則 {got / msgs:.2f}")
per = list(chat_credit_per_msg.values())
if per:
    print(f"  一則接話平均讓 {st.mean(per):.1f} 人拿到 mana，最多 {max(per)} 人")

buys = sum(1 for e in events if e.get('ev') == 'buy'); fails = sum(1 for e in events if e.get('ev') == 'buy-failed')
print(f"\n== 花費 == 祭壇兌換成功 {buys} 次、失敗 {fails} 次")
total_earn = sum(v for n in earn for s, v in earn[n].items() if v > 0 and s not in ('gift_in', 'admin'))
total_spent = -sum(v for n in earn for s, v in earn[n].items() if s in ('purchase', 'donate'))
print(f"全服產生 {total_earn} mana，花掉 {total_spent}（{total_spent / max(1, total_earn) * 100:.0f}%）")

vals = sorted(bal.values())
g = sum((2 * i - len(vals) - 1) * v for i, v in enumerate(vals, 1)) / (len(vals) * max(1, sum(vals)))
print(f"餘額吉尼係數 {g:.2f}（0 = 完全平均）")

hits = defaultdict(int)
for e in events:
    if e.get('ev') == 'sys' and '上限' in e['m']: hits[typ(e['who'])] += 1
print("收到「已達上限」提示：", dict(hits))
json.dump({'minutes': minutes, 'earn': {n: dict(v) for n, v in earn.items()}, 'bal': bal, 'chats': chats,
           'first_cap': {f'{n}|{s}': m for (n, s), m in first_cap.items()}}, open(LOG + '.summary.json', 'w'), ensure_ascii=False)
