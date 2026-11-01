# 選單重繪工具

在沒有 Minecraft 客戶端的環境（例如雲端容器）看 ManaCore 的選單長什麼樣：

1. `dump.js`：用 mineflayer 機器人登入測試伺服器，打開每個選單，把每一格的物品、名稱、說明文字存成 `gui.json`。
   `node dump.js <伺服器主控台 FIFO> gui.json`（需要 `npm install mineflayer`，伺服器 `online-mode=false`，並裝 ViaVersion/ViaBackwards 讓 1.20.4 協定能連線）
2. `render.py`：用 Minecraft 官方客戶端的材質（箱子介面背景、物品與方塊貼圖、`ascii.png` 與 unifont 字型）照遊戲的畫法重繪。
   需要先下載對應版本的 `client.jar`（piston-meta 的版本清單）並解壓 `assets/minecraft/{textures,models,items,font,lang}`，
   再從資產索引下載 `minecraft/lang/zh_tw.json` 與 `minecraft/font/unifont.zip`。
   `MCDIR=<資產目錄> HEAD_SKIN=<藍色寶石皮膚.png> python3 render.py gui.json <輸出資料夾>`

輸出是重繪圖，不是遊戲截圖：版面、物品、文字內容與遊戲相同，但方塊的 3D 角度與光影是近似值。
成品放在 `ManaCore/docs/gui/`。
