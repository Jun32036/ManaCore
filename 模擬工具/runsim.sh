#!/bin/bash
# 重設 mana 資料庫 → 啟動伺服器 → 跑模擬（參數：輸出檔名、分鐘數）
OUT=$1; MIN=${2:-12}
cd /home/claude/divtest
if pgrep -f "[j]ava -D" >/dev/null; then echo stop > in.fifo; timeout 60 bash -c 'while pgrep -f "[j]ava -D" >/dev/null; do sleep 1; done'; fi
rm -f plugins/ManaCore/mana.db*
python3 -c "
import sqlite3; c=sqlite3.connect('/home/claude/divtest/plugins/ManaCore/mana.db')
c.execute('CREATE TABLE IF NOT EXISTS altars(world TEXT, x INTEGER, y INTEGER, z INTEGER, PRIMARY KEY(world, x, y, z))')
c.execute(\"INSERT OR IGNORE INTO altars VALUES('world',100,120,100)\"); c.commit()"
bash /tmp/claude-0/-home-claude/d32ae91b-6349-5f47-b928-8c7d85ab0afe/scratchpad/divstart.sh | tail -1
grep "ManaCore v" server.log | tail -1
cd /home/claude/sim && SIM_MINUTES=$MIN SIM_OUT=/home/claude/sim/$OUT.jsonl node sim.js > $OUT.out 2>&1
cp /home/claude/divtest/plugins/ManaCore/mana.db /home/claude/sim/$OUT.db
python3 /home/claude/sim/analyze.py /home/claude/sim/$OUT.db /home/claude/sim/$OUT.jsonl > /home/claude/sim/$OUT.report.txt
echo DONE
