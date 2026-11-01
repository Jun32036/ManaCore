# 伺服器工具包：一鍵開啟 ManaCore 測試伺服器（Minecraft 26.3）

## 使用方式（Windows）

1. 把整個 `伺服器工具包` 資料夾複製到任何地方（例如 `C:\MINECRAFT\伺服器工具包`）。
2. 雙擊 **`一鍵啟動.bat`**。
3. 第一次啟動時，工具會依序：
   - 檢查 Java 25。電腦上沒有的話，自動下載 Eclipse Temurin JRE 25 到 `server\jre`（約 50 MB，不會安裝到系統）。
   - 下載伺服器核心 **DivineMC 26.3**（build 5，和原本的測試服相同，約 70 MB）。
   - 把 `plugins\` 裡的插件（ManaCore 1.5.0）裝進伺服器。
   - 請你閱讀並同意 [Minecraft EULA](https://aka.ms/MinecraftEULA)，輸入 `Y` 後繼續。
   - 啟動伺服器。
4. 打開 Minecraft 26.3 →「多人遊戲」→「直接連線」→ 輸入 `localhost`。
5. 要關閉伺服器時，在黑色視窗輸入 `stop` 再按 Enter，**不要直接按 X**，否則世界可能沒有存檔。

第二次之後不用再下載，雙擊就直接啟動。

Linux / macOS：在終端機執行 `bash tools/start-server.sh`，流程相同。

## 資料夾內容

| 檔案 | 用途 |
| --- | --- |
| `一鍵啟動.bat` | 雙擊啟動（Windows） |
| `設定.ini` | 記憶體、核心下載網址、自動重啟。用記事本修改 |
| `plugins\` | 要裝進伺服器的插件。每次啟動都會同步到 `server\plugins` |
| `defaults\server.properties` | 第一次啟動時使用的伺服器設定 |
| `tools\` | 啟動程式（Windows 用 `start-server.ps1`，Linux/macOS 用 `start-server.sh`） |
| `server\` | 第一次啟動後才會出現：世界、設定、紀錄、Java。**備份時備份這個資料夾** |

## 常見操作

- **讓自己成為管理員**：伺服器啟動後，在黑色視窗輸入 `op 你的遊戲名稱`。
- **設定生命祭壇**：在遊戲中看著一個方塊，輸入 `/manaadmin altar set`。
- **更新 ManaCore**：把新的 `ManaCore-x.y.z.jar` 放進工具包的 `plugins\`，再刪掉舊版的 jar。下次啟動時會自動替換 `server\plugins` 裡的舊版。
- **調整 ManaCore 數值**：編輯 `server\plugins\ManaCore\config.yml`，在遊戲中輸入 `/manaadmin reload`。
- **調整記憶體**：修改 `設定.ini` 的 `MEMORY`。電腦有 8 GB 記憶體建議 `3G`～`4G`。
- **讓朋友從外面連進來**：需要在路由器設定轉發 25565 埠，或使用 Radmin VPN、ZeroTier 這類虛擬區網工具。

## 和測試機器人 ClaudeBot 一起用（選用）

ClaudeBot 只能用 1.20.4 協定、離線模式連線，所以需要額外設定：

1. 在 `server\server.properties` 把 `online-mode` 改成 `false`。這樣任何人都能冒用名稱登入，請只在自己電腦上測試，Windows 防火牆詢問時選「拒絕」。
2. 把 ViaVersion、ViaBackwards 和 `BotBridge-1.0.0.jar` 放進工具包的 `plugins\`。
3. 在 ManaCore 的 `config.yml` 把 `block-same-ip` 改成 `false`，因為你和機器人都從本機連線。

## 疑難排解

| 狀況 | 處理方式 |
| --- | --- |
| 下載失敗 | 依畫面顯示的網址手動下載，放到畫面指示的位置（例如 `server\server.jar`），再雙擊一次 |
| 視窗一閃就關掉 | 在資料夾空白處按住 Shift 再按右鍵 →「在這裡開啟 PowerShell 視窗」，執行 `.\一鍵啟動.bat`，就能看到錯誤訊息 |
| 「無法載入，因為這個系統上已停用指令碼執行」 | `一鍵啟動.bat` 已經用 `-ExecutionPolicy Bypass` 執行。如果公司或學校電腦仍然擋下，請改用個人電腦 |
| 連不上 `localhost` | 確認黑色視窗出現 `Done (...)! For help, type "help"` 之後再連線 |
