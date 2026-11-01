// 驗證留言的「同一對玩家 7 天內只有 3 天產生 mana」
const mineflayer = require('/home/claude/bot/node_modules/mineflayer');
const fs = require('fs');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const fifo = c => fs.appendFileSync('/home/claude/divtest/in.fifo', c + '\n');
const mk = n => {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: n, version: '1.20.4', auth: 'offline' });
  b.on('messagestr', (m, p) => { if (p !== 'game_info' && /留言|作品|這週|沒有|參觀/.test(m)) console.log(`  [${n}] ${m}`); });
  return b;
};
(async () => {
  const A = mk('PairA'); await new Promise(r => A.once('spawn', r));
  const B = mk('PairB'); await new Promise(r => B.once('spawn', r));
  const C = mk('PairC'); await new Promise(r => C.once('spawn', r));
  fifo('tp PairB PairA'); fifo('tp PairC PairA'); await sleep(2500);
  B.chat('/mana work remove 1'); C.chat('/mana work remove 1'); await sleep(1000);
  B.chat('/mana work set B 的家'); C.chat('/mana work set C 的家'); await sleep(1500);
  fs.writeFileSync('/home/claude/sim/weekly.ready', '1');
  while (!fs.existsSync('/home/claude/sim/weekly.go')) await sleep(500);
  console.log('== PairA 在 PairB 的作品留言（過去 3 天都留過）==');
  A.chat('/mana comment PairB 今天也很漂亮'); await sleep(2500);
  console.log('== PairA 在 PairC 的作品留言（第一次）==');
  A.chat('/mana comment PairC 好看'); await sleep(2500);
  A.quit(); B.quit(); C.quit(); await sleep(500); process.exit(0);
})();
setTimeout(() => process.exit(1), 60000);
