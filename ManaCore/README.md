# ManaCore 1.2.0 —「生生不息」mana 核心插件

伺服器核心：DivineMC 26.3（Paper 分支）　Java 25　建置：`mvn package`（JDK 25 + Maven），輸出 `target/ManaCore-1.2.0.jar`
依賴：paper-api 26.3.build.231-stable（provided）、sqlite-jdbc 3.54.1.0（plugin.yml `libraries:` 執行時自動下載）

## 原始碼結構（xyz.teacafe.manacore）

| 檔案 | 職責 |
| --- | --- |
| `ManaCorePlugin` | 進入點：載入設定、資料庫、各服務、指令、排程 |
| `Database` | SQLite，單一背景執行緒依序執行；餘額一律以差額寫入 |
| `ManaService` | 在線玩家快取、`earn`（每日上限＋AFK）、`spend`、`adjust`、`earnAnywhere`（離線也可）、歸土、跨日 |
| `Actions` | 指令與 GUI 共用的動作：感謝、贈與、捐獻、登記作品、留言、查詢留言/紀錄 |
| `LifeTree` | 生命之樹累積與階段祝福（執行設定中的主控台指令） |
| `Places` | 作品、地標、祭壇方塊位置（記憶體快取） |
| `MentorService` | 導師制度 |
| `AltarMenu` | 生命祭壇兌換介面（公定價、季節祭品） |
| `gui/Menu`、`gui/ManaMenu` | `/mana` 箱子選單框架與所有選單頁 |
| `listener/ChatListener` | 聊天接話 mana（1.2.0 重寫，見下） |
| `listener/ExploreTask` | 探索：生態域、結構、地標 |
| `listener/PlayerListener` | 進出、AFK、收成、祭壇右鍵 |
| `command/*` | `/mana`、`/thanks`、`/manaadmin` |

資料表：accounts、daily、ledger、discoveries、thanks_log、works、comments、mentors、landmarks、altars、kv（mana.db）。

## 指令

玩家：`/mana`（開選單）、`/mana status`、`/thanks [玩家] [原因]`（不加玩家開選單）、`/mana give|donate|tree|work set/list/remove|comment|comments|mentor|log`
管理員：`/manaadmin give|take|event|check|altar set/remove|landmark add/remove/list|tree [set]|decay|reload`、`/altar`

## 1.2.0 的反濫用規則（config 中有預設值，舊設定檔不必改）

- 聊天：一則回覆只算給一個人（提到名字者優先，否則最近一則有內容且未被回過的別人訊息）。
  `min-length-speaker: 3`（被接話的訊息需有 3 個有意義的字）、`min-length: 2`（回覆本身）、
  `max-per-pair-day: 5`（同一對玩家每日上限）、洗頻 `spam-burst: 6` / `spam-window-seconds: 30` / `spam-cooldown-seconds: 60`
- 感謝：`sources.thanks.max-per-pair-week: 3`（同一對 7 天內只有 3 天產生 mana）
- 留言：`sources.works.max-per-pair-week: 3`

## 版本紀錄

- 1.0.0：mana 來源、每日上限、歸土、生命之樹、祭壇、作品、導師、管理指令
- 1.1.0：`/mana` 圖形選單、預設留言、`Actions` 共用邏輯
- 1.2.0：修正聊天 mana 被洗頻與群聊灌水、同一對玩家的每日/每週上限
