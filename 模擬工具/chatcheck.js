// ManaCore 1.2.0 聊天規則小測試：洗頻、回覆對象、同一對玩家每日上限
const mineflayer = require('/home/claude/bot/node_modules/mineflayer');
const sqlite3 = null;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const mk = n => mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: n, version: '1.20.4', auth: 'offline' });
const got = {};
(async () => {
  const names = ['Ann', 'Ben', 'Spam', 'Cat'];
  const B = {};
  for (const n of names) { B[n] = mk(n); got[n] = 0; await new Promise(r => B[n].once('spawn', r)); await sleep(300);
    B[n].on('actionBar', m => { const t = m.toString(); const k = t.match(/\+(\d+) mana/); if (k && /聊天/.test(t)) got[n] += Number(k[1]); });
    B[n].on('messagestr', (m, p) => { if (p === 'game_info') { const k = m.match(/\+(\d+) mana/); if (k && /聊天/.test(m)) got[n] += Number(k[1]); } }); }
  const say = (n, t) => B[n].chat(t);
  const spam = setInterval(() => say('Spam', ['1', '嗨嗨嗨', '？？？', 'hi'][Math.floor(Math.random() * 4)]), 3000);
  console.log('1) Ann 說有內容的話，Spam 洗頻插進來，Ben 沒指名回覆 → 應算給 Ann');
  say('Ann', '有人要一起去找村莊嗎'); await sleep(1500); await sleep(3500);
  say('Ben', '好啊我也要去'); await sleep(2000);
  console.log('   Ann', got.Ann, '／Spam', got.Spam);
  console.log('2) Spam 被回覆也拿不到（訊息沒有內容）');
  await sleep(2000); say('Cat', '哈哈你在幹嘛 Spam'); await sleep(2000);
  console.log('   Spam', got.Spam);
  console.log('3) Ann 與 Ben 一直對話：Ann 從 Ben 那邊每天最多 5 點');
  clearInterval(spam);
  for (let i = 0; i < 9; i++) { say('Ann', `第 ${i} 個話題我們來聊聊`); await sleep(1200); say('Ben', `回你第 ${i} 個話題`); await sleep(15500); }
  console.log('   Ann 總共', got.Ann, '（第 1 項得到的 1 點 + 和 Ben 對話最多 5 點，應為 5）');
  for (const n of names) B[n].quit(); await sleep(500); process.exit(0);
})();
setTimeout(() => { console.log('timeout', JSON.stringify(got)); process.exit(1); }, 240000);
