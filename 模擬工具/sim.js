// ManaCore 遊玩模擬：30 位不同類型的玩家同時在線，照各自的習慣玩一段時間
const mineflayer = require('/home/claude/bot/node_modules/mineflayer');
const { Vec3 } = require('/home/claude/bot/node_modules/vec3');
const fs = require('fs');

const MINUTES = Number(process.env.SIM_MINUTES || 12);
const FIFO = '/home/claude/divtest/in.fifo';
const OUT = process.env.SIM_OUT || '/home/claude/sim/run.jsonl';
const sleep = ms => new Promise(r => setTimeout(r, ms));
const rnd = (a, b) => a + Math.random() * (b - a);
const pick = a => a[Math.floor(Math.random() * a.length)];
const fifo = c => fs.appendFileSync(FIFO, c + '\n');
const record = o => fs.appendFileSync(OUT, JSON.stringify({ t: Date.now(), ...o }) + '\n');

// ---------- 玩家名單 ----------
const P = [];
const add = (prefix, n, type) => { for (let i = 1; i <= n; i++) P.push({ name: `${prefix}${String(i).padStart(2, '0')}`, type }); };
add('Social', 8, 'social'); add('Builder', 5, 'builder'); add('Explorer', 5, 'explorer'); add('Farmer', 4, 'farmer');
add('Ring', 4, 'ring'); add('Spammer', 1, 'spammer'); add('Idle', 1, 'afk'); add('Newbie', 2, 'newbie');
const byType = t => P.filter(p => p.type === t);

// 每位建築型、互刷型、農夫各有一塊地（彼此相距 40 格，都在作品留言半徑外）
const plotOf = p => {
  const i = P.indexOf(p);
  return { x: 200 + (i % 6) * 40, z: 200 + Math.floor(i / 6) * 40 };
};
const ALTAR = { x: 100, y: 120, z: 100 };

// ---------- 聊天內容（盡量不重複，ManaCore 不計重複訊息） ----------
const SUBJ = ['今天', '剛剛', '等等', '昨天', '這週', '晚上', '我'];
const TOPIC = ['挖到鑽石了', '在蓋房子', '去找村莊', '想種小麥', '要去地獄', '迷路了', '在釣魚', '被苦力怕炸了', '想找人一起探險', '換了玻璃', '在整理倉庫', '看到很漂亮的山'];
const TAIL = ['', '！', '～', '欸', '哈哈', '，有人要一起嗎', '，好累', '，好開心'];
const line = (who) => `${pick(SUBJ)}${pick(TOPIC)}${pick(TAIL)}${Math.random() < 0.25 && who ? ' @' + who : ''}`;
const REPLY = ['真的假的', '好耶', '我也想去', '要幫忙嗎', '在哪裡？', '哈哈哈', '太猛了', '加油', '我等等過去', '帶我一個', '小心喔', '讚啦'];

// ---------- 建立機器人 ----------
const bots = {};
function connect(p) {
  return new Promise(res => {
    const b = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: p.name, version: '1.20.4', auth: 'offline' });
    b.on('messagestr', (m, pos) => {
      if (pos === 'game_info') { if (/mana/.test(m)) record({ who: p.name, ev: 'actionbar', m }); return; }
      if (/^✿ \+\d+ mana|上限|AFK|同一個網路|已經感謝|已經給|感謝了你|送給你|已達/.test(m)) record({ who: p.name, ev: 'sys', m });
    });
    b.on('kicked', r => record({ who: p.name, ev: 'kicked', r: JSON.stringify(r).slice(0, 120) }));
    b.on('error', () => {});
    b.once('spawn', () => { bots[p.name] = b; res(b); });
    setTimeout(res, 20000);
  });
}
const say = (p, text) => { const b = bots[p.name]; if (!b) return; b.chat(text); if (!text.startsWith('/')) record({ who: p.name, ev: 'chat', m: text }); };
const look = p => { const b = bots[p.name]; if (b && b.entity) b.look(rnd(0, 6.28), rnd(-0.4, 0.4), false).catch(() => {}); };
const tp = (p, x, y, z) => fifo(`tp ${p.name} ${x} ${y} ${z}`);

// ---------- 各類型的行為 ----------
const until = () => Date.now() < END;
let END = 0;

async function loopEvery(min, max, fn) { while (until()) { await sleep(rnd(min, max) * 1000); if (!until()) break; try { await fn(); } catch (_) {} } }

function antiAfk(p) { if (p.type !== 'afk') loopEvery(20, 40, () => look(p)); }

async function social(p) {
  loopEvery(12, 30, () => {
    const others = Object.keys(bots).filter(n => n !== p.name);
    if (Math.random() < 0.45) say(p, pick(REPLY) + (Math.random() < 0.3 ? ' ' + pick(others) : ''));
    else say(p, line(pick(others)));
  });
  loopEvery(150, 240, () => { const t = pick(byType('social').filter(x => x !== p)); say(p, `/thanks ${t.name} 謝謝陪聊`); });
  await sleep(rnd(120, 300) * 1000); await buy(p);
}

async function builder(p) {
  const { x, z } = plotOf(p);
  tp(p, x, 101, z); await sleep(2500); say(p, '/mana work set 我的小屋');
  loopEvery(150, 240, () => say(p, line()));
  loopEvery(90, 150, async () => {
    const others = [...byType('builder'), ...byType('ring')].filter(o => o !== p);
    const o = pick(others); const q = plotOf(o);
    tp(p, q.x + 3, 101, q.z + 3); await sleep(2500);
    say(p, `/mana comment ${o.name} ${pick(['好漂亮', '好有感覺', '細節好多', '想住這裡', '配色很棒'])}`);
    await sleep(3000); tp(p, x, 101, z);
  });
}

async function explorer(p) {
  loopEvery(30, 45, () => fifo(`spreadplayers ${Math.round(rnd(-6000, 6000))} ${Math.round(rnd(-6000, 6000))} 0 1 false ${p.name}`));
  loopEvery(150, 240, () => say(p, line()));
}

async function farmer(p) {
  const { x, z } = plotOf(p);
  const refill = () => { fifo(`fill ${x - 3} 100 ${z - 3} ${x + 3} 100 ${z + 3} farmland`); fifo(`fill ${x - 3} 101 ${z - 3} ${x + 3} 101 ${z + 3} wheat[age=7]`); fifo(`setblock ${x} 101 ${z} air`); };
  refill(); tp(p, x + 0.5, 101, z + 0.5); await sleep(3000);
  loopEvery(40, 50, refill);
  loopEvery(200, 300, () => say(p, line()));
  while (until()) {
    const b = bots[p.name];
    // 成熟度在客戶端是字串 "7"；只看自己田裡的格子
    let w = null;
    if (b) for (let dx = -3; dx <= 3 && !w; dx++) for (let dz = -3; dz <= 3 && !w; dz++) {
      const bl = b.blockAt(new Vec3(x + dx, 101, z + dz));
      if (bl && bl.name === 'wheat' && String(bl.getProperties().age) === '7') w = bl;
    }
    if (w) { try { await Promise.race([b.dig(w, true), sleep(2000)]); } catch (_) {} await sleep(rnd(600, 1200)); }
    else await sleep(2000);
  }
}

async function ring(p) {
  const mates = byType('ring').filter(o => o !== p);
  const { x, z } = plotOf(p);
  tp(p, x, 101, z); await sleep(2500); say(p, '/mana work set 圈圈小屋');
  await sleep(4000);
  for (const m of mates) { say(p, `/thanks ${m.name} 互相幫忙`); await sleep(1500); }
  for (const m of mates) { const q = plotOf(m); tp(p, q.x + 3, 101, q.z + 3); await sleep(2500); say(p, `/mana comment ${m.name} 讚`); await sleep(1500); }
  tp(p, x, 101, z);
  loopEvery(10, 16, () => say(p, `${pick(REPLY)} ${pick(mates).name} ${Math.floor(Math.random() * 1000)}`));
  loopEvery(60, 90, () => { const m = pick(mates); say(p, `/mana give ${m.name} 5 輪流`); });
}

async function spammer(p) { loopEvery(4, 6, () => say(p, pick(['1', 'hi', '嗨嗨嗨', '嗨嗨嗨', '.', '？？？']))); }

async function newbie(p, mentor) {
  await sleep(rnd(20, 40) * 1000);
  say(mentor, `/mana mentor ${p.name}`); await sleep(3000); say(p, '/mana mentor accept');
  loopEvery(60, 120, () => say(p, pick(['請問祭壇在哪', '這裡怎麼玩', 'mana 要怎麼賺', '有人可以帶我嗎', '我是新手', '剛來這裡'])));
  await sleep(rnd(60, 180) * 1000); await buy(p);
}

async function buy(p) {
  const b = bots[p.name]; if (!b) return;
  tp(p, ALTAR.x + 1.5, ALTAR.y, ALTAR.z + 0.5); await sleep(3000);
  const block = b.blockAt(new Vec3(ALTAR.x, ALTAR.y, ALTAR.z));
  if (!block) return;
  const w = new Promise(r => { b.once('windowOpen', r); setTimeout(r, 5000); });
  try { await b.activateBlock(block); } catch (_) {}
  const win = await w;
  if (win && b.currentWindow) { await sleep(500); try { await b.clickWindow(0, 0, 0); } catch (_) {} await sleep(800); b.closeWindow(b.currentWindow); record({ who: p.name, ev: 'buy' }); }
  else record({ who: p.name, ev: 'buy-failed' });
}

// ---------- 主程序 ----------
(async () => {
  fs.writeFileSync(OUT, '');
  fifo('time set day'); fifo('gamerule doDaylightCycle false'); fifo('difficulty peaceful'); fifo('weather clear');
  fifo(`setblock ${ALTAR.x} ${ALTAR.y - 1} ${ALTAR.z} stone`); fifo(`setblock ${ALTAR.x} ${ALTAR.y} ${ALTAR.z} lodestone`);
  for (const p of P) { await connect(p); await sleep(400); }
  console.log('上線人數', Object.keys(bots).length);
  record({ ev: 'start', online: Object.keys(bots).length });
  END = Date.now() + MINUTES * 60000;
  for (const p of P) antiAfk(p);
  for (const p of byType('social')) social(p);
  for (const p of byType('builder')) builder(p);
  for (const p of byType('explorer')) explorer(p);
  for (const p of byType('farmer')) farmer(p);
  for (const p of byType('ring')) ring(p);
  for (const p of byType('spammer')) spammer(p);
  const socials = byType('social');
  byType('newbie').forEach((p, i) => newbie(p, socials[i]));
  while (until()) { await sleep(30000); console.log(`剩 ${Math.round((END - Date.now()) / 60000)} 分鐘，在線 ${Object.values(bots).filter(b => b.entity).length}`); }
  record({ ev: 'end' });
  for (const b of Object.values(bots)) b.quit();
  await sleep(2000);
  process.exit(0);
})();
