#!/usr/bin/env bash
# ManaCore 測試伺服器：一鍵啟動（Linux / macOS）
# 第一次執行會自動下載 Java 25 與 DivineMC 26.3，之後直接啟動。
set -euo pipefail

KIT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER="$KIT/server"

# ---- 設定（讀 設定.ini；JAVA_URL 在這裡依系統自動決定）----
MEMORY=4G
SERVER_URL=https://files.mcjars.app/divinemc/26.3/5/server.jar
AUTO_RESTART=false
if [ -f "$KIT/設定.ini" ]; then
  while IFS='=' read -r k v; do
    v="${v%$'\r'}"
    case "$k" in
      MEMORY) MEMORY="$v" ;;
      SERVER_URL) SERVER_URL="$v" ;;
      AUTO_RESTART) AUTO_RESTART="$v" ;;
    esac
  done < <(grep -E '^[A-Z_]+=' "$KIT/設定.ini")
fi

step() { printf '\n\033[36m▶ %s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✔ %s\033[0m\n' "$1"; }
fail() { printf '\n\033[31m✘ %s\033[0m\n' "$1"; exit 1; }
download() { # url dest what
  echo "  下載 $3…（第一次需要幾分鐘）"
  curl -fL --retry 3 -o "$2.part" "$1" || { rm -f "$2.part"; fail "下載 $3 失敗：$1（可手動下載後放到 $2）"; }
  mv "$2.part" "$2"
}
java_major() { "$1" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1; }

mkdir -p "$SERVER/plugins"

step '檢查 Java 25'
JAVA="$SERVER/jre/bin/java"
[ -x "$SERVER/jre/Contents/Home/bin/java" ] && JAVA="$SERVER/jre/Contents/Home/bin/java"
if [ -x "$JAVA" ]; then
  ok "使用工具包內建的 Java $(java_major "$JAVA")"
elif command -v java >/dev/null && [ "$(java_major java || echo 0)" -ge 25 ] 2>/dev/null; then
  JAVA=java; ok "使用系統的 Java $(java_major java)"
else
  case "$(uname -s)" in Darwin) OS=mac ;; *) OS=linux ;; esac
  case "$(uname -m)" in arm64|aarch64) ARCH=aarch64 ;; *) ARCH=x64 ;; esac
  JAVA_URL="https://api.adoptium.net/v3/binary/latest/25/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
  download "$JAVA_URL" "$SERVER/jre.tar.gz" 'Java 25（Eclipse Temurin JRE）'
  rm -rf "$SERVER/jre-extract" && mkdir -p "$SERVER/jre-extract"
  tar -xzf "$SERVER/jre.tar.gz" -C "$SERVER/jre-extract"
  mv "$SERVER/jre-extract/"* "$SERVER/jre" && rm -rf "$SERVER/jre-extract" "$SERVER/jre.tar.gz"
  JAVA="$SERVER/jre/bin/java"
  [ -x "$SERVER/jre/Contents/Home/bin/java" ] && JAVA="$SERVER/jre/Contents/Home/bin/java"
  [ -x "$JAVA" ] || fail '找不到解壓後的 java'
  ok "已安裝 Java $(java_major "$JAVA")"
fi

step '檢查伺服器核心（DivineMC 26.3）'
if [ -s "$SERVER/server.jar" ] && [ "$(wc -c < "$SERVER/server.jar")" -gt 1000000 ]; then
  ok 'server.jar 已存在'
else
  download "$SERVER_URL" "$SERVER/server.jar" 'DivineMC 26.3（約 70 MB）'
fi

step '同步插件'
for p in "$KIT"/plugins/*.jar; do
  [ -e "$p" ] || continue
  f="$(basename "$p")"; name="${f%%-*}"
  for old in "$SERVER"/plugins/"$name"-*.jar; do
    [ -e "$old" ] && [ "$(basename "$old")" != "$f" ] && rm -f "$old" && echo "  移除舊版 $(basename "$old")"
  done
  if ! cmp -s "$p" "$SERVER/plugins/$f"; then cp "$p" "$SERVER/plugins/$f"; ok "安裝 $f"; else ok "$f（已是最新）"; fi
done

[ -f "$SERVER/server.properties" ] || { cp "$KIT/defaults/server.properties" "$SERVER/server.properties"; ok '建立預設 server.properties'; }

if ! grep -qs 'eula=true' "$SERVER/eula.txt"; then
  step 'Minecraft 使用者授權合約（EULA）'
  echo '  架設伺服器前必須同意 Minecraft EULA：https://aka.ms/MinecraftEULA'
  read -r -p '  已閱讀並同意請輸入 Y：' ans
  [[ "$ans" =~ ^[Yy] ]] || fail '未同意 EULA，無法啟動伺服器。'
  echo 'eula=true' > "$SERVER/eula.txt"
fi

cd "$SERVER"
while true; do
  step "啟動伺服器（記憶體 $MEMORY）"
  echo '  連線方式：Minecraft 26.3 → 多人遊戲 → 直接連線 → localhost'
  echo '  關閉伺服器：輸入 stop 再按 Enter'
  "$JAVA" "-Xms$MEMORY" "-Xmx$MEMORY" -jar server.jar --nogui || true
  [ "$AUTO_RESTART" = true ] || break
  echo '伺服器已停止，10 秒後自動重啟（Ctrl+C 取消）…'; sleep 10
done
