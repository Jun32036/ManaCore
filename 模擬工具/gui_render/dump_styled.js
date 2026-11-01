const fs = require('fs'); const mineflayer = require('mineflayer');
const FIFO = process.argv[2], OUT = process.argv[3]; const con = (c) => fs.appendFileSync(FIFO, c + '\n'); const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const out = {};
function dump(name, w, bot) {
  const slots = [];
  for (let i = 0; i < w.inventoryStart; i++) { const it = w.slots[i]; if (!it) { slots.push(null); continue; }
    const d = (it.nbt && it.nbt.value && it.nbt.value.display && it.nbt.value.display.value) || {};
    slots.push({ name: it.name, count: it.count, displayName: d.Name ? d.Name.value : null, lore: d.Lore && d.Lore.value ? d.Lore.value.value : [], glint: !!(it.nbt && it.nbt.value && it.nbt.value.Enchantments) }); }
  let title = ''; try { title = typeof w.title === 'string' ? w.title : JSON.stringify(w.title); } catch {}
  out[name] = { title, rows: w.inventoryStart / 9, slots, hotbar: bot.inventory.slots.slice(36, 45).map(it => it ? { name: it.name, count: it.count } : null) };
  const codes = [...title].map(c => c.charCodeAt(0)); console.log(name, w.inventoryStart / 9, 'rows; glyph chars:', codes.filter(c => c >= 0xE100 && c < 0xE300).map(c => c.toString(16)).join(' '));
}
async function openCmd(bot, cmd) { const p = new Promise((res) => { bot.once('windowOpen', res); setTimeout(() => res(null), 4000); }); bot.chat(cmd); const w = await p; await sleep(700); return w; }
async function click(bot, slot) { const p = new Promise((res) => { bot.once('windowOpen', res); setTimeout(res, 1500); }); await bot.clickWindow(slot, 0, 0).catch(() => {}); await p; await sleep(700); }
(async () => {
  const A = await new Promise((res) => { const b = mineflayer.createBot({ host: 'localhost', port: 25565, username: 'Alice', version: '1.20.4', auth: 'offline' }); b.once('spawn', () => res(b)); });
  for (const ev of ['resource_pack_send', 'add_resource_pack']) A._client.on(ev, (pk) => console.log('收到資源包要求：', pk.url, String(pk.hash || '').slice(0, 12), 'forced', pk.forced));
  con('op Alice'); await sleep(3000); con('manaadmin style Alice on'); await sleep(800);
  let w = await openCmd(A, '/mana chest'); if (!w) { console.log('no window'); process.exit(1); } dump('main', w, A);
  await A.equip(A.inventory.items().find(i => i.name === 'oak_log') || A.inventory.items()[0], 'hand').catch(() => {});
  for (const [name, slot] of [['skills', 10], ['dex', 11], ['orders', 12], ['alchemy', 13], ['market', 15], ['board', 21], ['projects', 29]]) {
    A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest'); if (!w) continue; await click(A, slot); dump(name, A.currentWindow, A);
  }
  A.closeWindow(A.currentWindow); w = await openCmd(A, '/altar'); if (w) { dump('altar_home', w, A); await click(A, 10); dump('altar_stone', A.currentWindow, A); A.closeWindow(A.currentWindow); }
  fs.writeFileSync(OUT, JSON.stringify(out, null, 1)); A.quit(); await sleep(500); process.exit(0);
})().catch(e => { console.log('FATAL', e.stack); process.exit(1); });
