// ClaudeBot：ManaCore 測試伺服器的第二位玩家
// - 會回應聊天、接受導師邀請、被感謝時道謝、閒置時轉頭（避免被判定 AFK）
// - 玩家用伺服器指令 /bot 叫它做事（由 BotBridge 插件轉成系統訊息「⟦BOT⟧ 玩家 動作」）
// - Claude 透過同資料夾的 commands.txt 下指令，從 chat.log 讀取遊戲內訊息
const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');
// 幹活模組還在開發中，沒有一起安裝時就略過
let toolPlugin = null, attachWork = null;
try { toolPlugin = require('mineflayer-tool').plugin; attachWork = require('./work'); } catch (_) {}
const BOT_MARK = '⟦BOT⟧';
const fs = require('fs');
const path = require('path');

const HOST = process.env.MC_HOST || '127.0.0.1';
const PORT = Number(process.env.MC_PORT || 25565);
const NAME = process.env.MC_NAME || 'ClaudeBot';
const DIR = __dirname;
const CMD_FILE = path.join(DIR, 'commands.txt');
const LOG_FILE = path.join(DIR, 'chat.log');

const sleep = ms => new Promise(r => setTimeout(r, ms));
const pick = arr => arr[Math.floor(Math.random() * arr.length)];
const now = () => new Date().toLocaleTimeString('zh-TW', { hour12: false });

function log(line) {
  const s = `[${now()}] ${line}`;
  console.log(s);
  try { fs.appendFileSync(LOG_FILE, s + '\n', 'utf8'); } catch (_) {}
}

let bot = null;
let lastSaid = '';
let lastReplyAt = 0;
let cmdOffset = 0;
let pendingBalanceFor = null;
let work = null;
let walkMv = null;

// 一律送出與上一句不同的話（ManaCore 不計重複訊息）
function say(text) {
  if (!bot || !text) return;
  if (text === lastSaid && !text.startsWith('/')) text += ' ～';
  lastSaid = text;
  bot.chat(text);
  log(`(我) ${text}`);
}

const GREETINGS = ['嗨嗨，我是 ClaudeBot，第二位測試玩家！', '我上線囉，今天一起測 mana 吧', '哈囉～我是來陪你測試的 ClaudeBot'];
const REPLIES = {
  hello: ['嗨！今天想測哪個部分？', '哈囉～我在這', '你好呀，有什麼需要幫忙的嗎'],
  question: ['嗯…我想想', '這個問題好，我也不太確定', '可以試試看，我陪你'],
  mana: ['mana 要有人接話才會長，我們多聊聊吧', '我也在存 mana，不過超過 500 會歸土喔', '要不要一起把 mana 捐給生命之樹？'],
  thanks: ['不客氣！', '小事啦～', '能幫上忙就好'],
  generic: ['了解！', '聽起來不錯耶', '原來如此', '哈哈好喔', '我記下來了', '真的嗎？', '好呀', '嗯嗯', '這主意不錯', '我也這麼覺得', '收到～', '有意思']
};

function chooseReply(msg) {
  const m = msg.toLowerCase();
  if (/(你好|嗨|哈囉|hello|hi\b|安安)/.test(m)) return pick(REPLIES.hello);
  if (/(謝謝|感謝|thx|thank)/.test(m)) return pick(REPLIES.thanks);
  if (/mana/.test(m)) return pick(REPLIES.mana);
  if (/[?？嗎]$/.test(m)) return pick(REPLIES.question);
  return pick(REPLIES.generic);
}

const HELP = [
  '叫我做事請打這些指令：',
  '・「/bot 來」走到你旁邊　「/bot 跟」跟著你　「/bot 停」停下來',
  '・「/bot 謝」我感謝你　「/bot 參觀」我在你附近的作品留言',
  '・「/bot 送 數字」我送你 mana　「/bot 狀態」報告我的 mana',
  '・「/bot 導師」我邀請你當我的導師　一般聊天我也會回（有冷卻）'
];
let lastHintAt = 0;

function findPlayerEntity(name) {
  const p = bot.players[name];
  return p && p.entity ? p.entity : null;
}

async function goTo(name, range = 2) {
  const ent = findPlayerEntity(name);
  if (!ent) {
    say(`看不到你耶，你離我太遠了，可以用 /tp ${NAME} ${name} 把我拉過去`);
    return false;
  }
  if (work && work.busy()) work.stop();
  if (walkMv) bot.pathfinder.setMovements(walkMv);
  bot.pathfinder.setGoal(null); // 取消上一個目標，避免兩次 !來 互相打斷
  await sleep(100);
  try {
    await Promise.race([
      bot.pathfinder.goto(new goals.GoalNear(ent.position.x, ent.position.y, ent.position.z, range)),
      sleep(45000).then(() => { throw new Error('timeout'); })
    ]);
    return true;
  } catch (e) {
    if (/goal was changed|GoalChanged|interrupted/i.test(String(e && (e.name + e.message)))) return false;
    bot.pathfinder.setGoal(null);
    log('尋路失敗：' + (e && e.message));
    say(`我過不去，請在伺服器的黑色視窗輸入：tp ${NAME} ${name}`);
    return false;
  }
}

async function handleCommand(from, msg) {
  const t = msg.trim();
  if (t === '!說明' || t === '!help') { for (const l of HELP) { say(l); await sleep(400); } return true; }
  if (t === '!來') { say('來了！'); await goTo(from); return true; }
  if (t === '!跟') {
    const ent = findPlayerEntity(from);
    if (!ent) { say(`看不到你，先用 /tp ${NAME} ${from}`); return true; }
    bot.pathfinder.setGoal(new goals.GoalFollow(ent, 2), true);
    say('好，我跟著你'); return true;
  }
  if (t === '!停') { if (work) work.stop(); bot.pathfinder.setGoal(null); say('停下來了'); return true; }
  if (t === '!謝') { say(`/thanks ${from} 謝謝你陪我測試`); return true; }
  if (t === '!參觀') {
    say('我過去看看～');
    if (await goTo(from, 3)) {
      await sleep(800);
      say(`/mana comment ${from} ${pick(['好漂亮！', '這裡好有感覺', '細節做得好用心', '我喜歡這個配色', '下次也想來玩'])}`);
    }
    return true;
  }
  const give = t.match(/^!送\s*(\d+)$/);
  if (give) { say(`/mana give ${from} ${give[1]} 送你的`); return true; }
  if (t === '!狀態') { pendingBalanceFor = from; say('/mana status'); return true; }
  if (t === '!導師') { say(`/mana mentor ${from}`); return true; }
  if (!work) return false;
  let m;
  if ((m = t.match(/^!砍樹\s*(\d*)$/))) { work.chop(Math.min(64, Number(m[1] || 8))); return true; }
  if ((m = t.match(/^!挖礦\s*(\S*)\s*(\d*)$/))) { work.mine(m[1] || '石頭', Math.min(64, Number(m[2] || 8))); return true; }
  if ((m = t.match(/^!做\s+(\S+)\s*(\d*)$/))) { work.craft(m[1], Math.min(64, Number(m[2] || 1))); return true; }
  if (t === '!收成') { work.harvest(); return true; }
  if ((m = t.match(/^!給我\s*(\S*)$/))) { work.give(from, goTo, m[1] || null); return true; }
  if (t === '!背包') { say(work.inventorySummary()); return true; }
  return false;
}

function onSystemMessage(text) {
  // BotBridge 轉來的 /bot 指令
  if (text.startsWith(BOT_MARK + ' ')) {
    const m = text.slice(BOT_MARK.length + 1).match(/^(\S+) (\S+)(?: (.*))?$/);
    if (m) {
      const [, from, action, extra] = m;
      log(`/bot ${action}${extra ? ' ' + extra : ''}（來自 ${from}）`);
      handleCommand(from, '!' + action + (extra ? ' ' + extra : '')).catch(e => log('指令錯誤 ' + e.message));
    }
    return;
  }
  if (text.includes('想當你的導師')) setTimeout(() => say('/mana mentor accept'), 1500);
  const thanked = text.match(/^✿ (\S+) 感謝了你/);
  if (thanked) setTimeout(() => say(`謝謝 ${thanked[1]}！`), 2000);
  const bal = text.match(/你的 mana：(\d+)/);
  if (bal && pendingBalanceFor) { const n = bal[1]; pendingBalanceFor = null; setTimeout(() => say(`我現在有 ${n} mana`), 800); }
}

function onPlayerChat(from, msg) {
  if (from === NAME) return;
  log(`<${from}> ${msg}`);
  if (msg.startsWith('!')) {
    // 舊的 ! 指令已改成 /bot
    if (Date.now() - lastHintAt > 20000) { lastHintAt = Date.now(); say(`現在改用 /bot 了，例如 /bot ${msg.slice(1).split(' ')[0] || '來'}（輸入 /bot 看全部）`); }
    return;
  }
  // 一般聊天：冷卻 18 秒，等 2–5 秒再回，像真人一點
  if (Date.now() - lastReplyAt < 18000) return;
  lastReplyAt = Date.now();
  setTimeout(() => say(chooseReply(msg)), 2000 + Math.random() * 3000);
}

// Claude 從 commands.txt 下的指令：一般文字照打；!goto 名稱、!follow 名稱、!stop 等內建動作
function pollCommands() {
  fs.stat(CMD_FILE, (err, st) => {
    if (err) return;
    if (st.size < cmdOffset) cmdOffset = 0;
    if (st.size === cmdOffset) return;
    const fd = fs.openSync(CMD_FILE, 'r');
    const buf = Buffer.alloc(st.size - cmdOffset);
    fs.readSync(fd, buf, 0, buf.length, cmdOffset);
    fs.closeSync(fd);
    cmdOffset = st.size;
    const lines = buf.toString('utf8').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    (async () => {
      for (const l of lines) {
        if (!bot) break;
        const goto = l.match(/^!goto\s+(\S+)$/);
        const follow = l.match(/^!follow\s+(\S+)$/);
        if (goto) await goTo(goto[1]);
        else if (follow) { const e = findPlayerEntity(follow[1]); if (e) bot.pathfinder.setGoal(new goals.GoalFollow(e, 2), true); }
        else if (l === '!stop') { if (work) work.stop(); bot.pathfinder.setGoal(null); }
        else if (/^!as\s+\S+\s+!/.test(l)) { const [, who, cmd] = l.match(/^!as\s+(\S+)\s+(!.*)$/); await handleCommand(who, cmd); }
        else if (l === '!players') log('在線：' + Object.keys(bot.players).join(', '));
        else if (l === '!where') { const p = bot.entity.position; log(`我的位置：${p.x.toFixed(1)}, ${p.y.toFixed(1)}, ${p.z.toFixed(1)}`); }
        else if (l === '!reload') { log('重新載入程式…'); bot.quit(); setTimeout(() => process.exit(42), 1500); return; }
        else say(l);
        await sleep(1200);
      }
    })();
  });
}

function connect() {
  log(`連線到 ${HOST}:${PORT}…`);
  bot = mineflayer.createBot({ host: HOST, port: PORT, username: NAME, version: '1.20.4', auth: 'offline' });
  bot.loadPlugin(pathfinder);
  if (toolPlugin) bot.loadPlugin(toolPlugin);

  bot.once('spawn', () => {
    log('已進入伺服器');
    const mv = new Movements(bot);
    // 只能拆樹葉（卡在樹上時才下得來），其他方塊一律不破壞；可以跳下 10 格以內
    mv.canDig = true;
    mv.blocksCantBreak = new Set(bot.registry.blocksArray.filter(b => !b.name.endsWith('_leaves')).map(b => b.id));
    mv.maxDropDown = 10;
    mv.allowParkour = true;
    mv.allowSprinting = false; // 經過 ViaVersion 時衝刺會被伺服器拉回，只用走的
    mv.allow1by1towers = false;
    bot.pathfinder.setMovements(mv);
    walkMv = mv;
    work = attachWork ? attachWork(bot, say, log) : null;
    setTimeout(() => say(pick(GREETINGS)), 3000);
  });

  bot.on('messagestr', (text, position) => {
    if (position === 'game_info' || !text.trim()) return;
    const m = text.match(/^<([^>]+)> (.*)$/);
    if (m) { onPlayerChat(m[1], m[2]); return; }
    log(text);
    onSystemMessage(text);
  });

  bot.on('death', () => log('我倒下了，等等自動重生'));
  bot.on('kicked', r => log('被踢出：' + JSON.stringify(r).slice(0, 200)));
  bot.on('error', e => log('錯誤：' + e.message));
  bot.on('end', () => {
    log('已離線，10 秒後重新連線');
    bot = null;
    setTimeout(connect, 10000);
  });
}

// 閒置時轉頭，讓 ManaCore 知道我不是 AFK
setInterval(() => {
  if (!bot || !bot.entity || bot.pathfinder?.isMoving()) return;
  bot.look(Math.random() * Math.PI * 2, (Math.random() - 0.5) * 0.6, false).catch(() => {});
}, 40000);

try { cmdOffset = fs.existsSync(CMD_FILE) ? fs.statSync(CMD_FILE).size : 0; } catch (_) { cmdOffset = 0; }
if (!fs.existsSync(CMD_FILE)) fs.writeFileSync(CMD_FILE, '', 'utf8');
setInterval(pollCommands, 1000);
connect();
