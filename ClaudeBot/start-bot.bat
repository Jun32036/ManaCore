@echo off
chcp 65001 >nul
title ClaudeBot（第二位測試玩家）
cd /d "%~dp0ClaudeBot"
echo 請先確認伺服器已經顯示 Done。關閉這個視窗即可讓 ClaudeBot 離線。
:loop
node.exe bot.js
echo ClaudeBot 已停止，3 秒後自動重新啟動（關閉這個視窗即可結束）
timeout /t 3 /nobreak >nul
goto loop
