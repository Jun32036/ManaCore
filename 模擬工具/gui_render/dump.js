// 把各選單的每一格內容讀出來（給重繪用）
const fs = require('fs'); const mineflayer = require('mineflayer'); const { Vec3 } = require('vec3');
const FIFO = process.argv[2], OUT = process.argv[3];
const con = (c) => fs.appendFileSync(FIFO, c + '\n'); const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const out = {};
function dump(name, w, bot) {
  const slots = [];
  for (let i = 0; i < w.inventoryStart; i++) {
    const it = w.slots[i]; if (!it) { slots.push(null); continue; }
    const d = (it.nbt && it.nbt.value && it.nbt.value.display && it.nbt.value.display.value) || {};
    const lore = d.Lore && d.Lore.value && d.Lore.value.value ? d.Lore.value.value : [];
    let tex = null; try { const so = it.nbt.value.SkullOwner.value.Properties.value.textures.value.value[0].value.Value.value; tex = so; } catch {}
    const ench = !!(it.nbt && it.nbt.value && (it.nbt.value.Enchantments || it.nbt.value.StoredEnchantments));
    slots.push({ name: it.name, count: it.count, displayName: d.Name ? d.Name.value : null, lore, tex, glint: ench });
  }
  let title = ''; try { title = typeof w.title === 'string' ? w.title : JSON.stringify(w.title); } catch {}
  out[name] = { title, rows: w.inventoryStart / 9, slots, hotbar: bot.inventory.slots.slice(36, 45).map(it => it ? { name: it.name, count: it.count } : null) };
  console.log(name, w.inventoryStart / 9, 'rows');
}
async function openCmd(bot, cmd) { const p = new Promise((res) => { bot.once('windowOpen', res); setTimeout(() => res(null), 4000); }); bot.chat(cmd); const w = await p; await sleep(700); return w; }
async function click(bot, slot, mode = 0) { const p = new Promise((res) => { bot.once('windowOpen', res); setTimeout(res, 1500); }); await bot.clickWindow(slot, 0, mode).catch(() => {}); await p; await sleep(700); }
(async () => {
  const A = await new Promise((res) => { const b = mineflayer.createBot({ host: 'localhost', port: 25565, username: 'Alice', version: '1.20.4', auth: 'offline' }); b.once('spawn', () => res(b)); });
  const B = await new Promise((res) => { const b = mineflayer.createBot({ host: 'localhost', port: 25565, username: 'Bob', version: '1.20.4', auth: 'offline' }); b.once('spawn', () => res(b)); });
  con('op Alice'); con('gamemode survival Alice'); con('tp Alice 0.5 150 0.5'); con('tp Bob 2.5 150 0.5'); con('clear Alice');
  con('give Alice bread 16'); con('give Alice cooked_salmon 8'); con('give Alice oak_log 32'); con('give Alice diamond_pickaxe'); con('give Alice stone_bricks 64');
  con('give Bob white_wool 32'); con('manaadmin give Bob 50 x');
  await sleep(2500);
  // 讓市集與委託板有內容
  await B.equip(B.inventory.items().find(i => i.name === 'white_wool'), 'hand'); B.chat('/mana sell'); await sleep(1200); if (B.currentWindow) B.closeWindow(B.currentWindow);
  B.chat('/mana request service 10 幫我一起蓋村莊的屋頂'); await sleep(800);
  await B.equip(B.inventory.items().find(i => i.name === 'bread') || B.inventory.items()[0], 'hand').catch(() => {});
  con('give Bob cooked_beef 1'); await sleep(800); await B.equip(B.inventory.items().find(i => i.name === 'cooked_beef'), 'hand'); B.chat('/mana request 16 5'); await sleep(1200);
  await A.equip(A.inventory.items().find(i => i.name === 'oak_log'), 'hand');
  let w = await openCmd(A, '/mana chest'); dump('main', w, A);
  await click(A, 10); dump('skills', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 11); dump('dex', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 12); dump('orders', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 13); dump('alchemy', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 15); dump('market', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 21); dump('board', A.currentWindow, A); A.closeWindow(A.currentWindow); w = await openCmd(A, '/mana chest');
  await click(A, 29); dump('projects', A.currentWindow, A); await click(A, 10); dump('project', A.currentWindow, A);
  A.closeWindow(A.currentWindow);
  w = await openCmd(A, '/altar'); dump('altar_home', w, A); await click(A, 10); dump('altar_stone', A.currentWindow, A);
  A.closeWindow(A.currentWindow);
  fs.writeFileSync(OUT, JSON.stringify(out, null, 1));
  A.quit(); B.quit(); await sleep(800); process.exit(0);
})().catch(e => { console.log('FATAL', e.stack); process.exit(1); });
