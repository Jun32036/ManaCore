# ManaCore 測試伺服器：一鍵啟動（由「一鍵啟動.bat」呼叫）
# 第一次執行會自動下載 Java 25 與 DivineMC 26.3，之後直接啟動。
# 本檔必須以 UTF-8（含 BOM）儲存，Windows PowerShell 5.1 才能正確顯示中文。

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # 關掉進度條，下載快很多
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

$Kit    = Split-Path -Parent $PSScriptRoot
$Server = Join-Path $Kit 'server'

function Step($m) { Write-Host ''; Write-Host "▶ $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  ✔ $m" -ForegroundColor Green }
function Fail($m) {
    Write-Host ''
    Write-Host "✘ $m" -ForegroundColor Red
    Read-Host '按 Enter 關閉'
    exit 1
}

# ---------------- 設定 ----------------
$cfg = @{
    MEMORY       = '4G'
    SERVER_URL   = 'https://files.mcjars.app/divinemc/26.3/5/server.jar'
    JAVA_URL     = 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jre/hotspot/normal/eclipse'
    AUTO_RESTART = 'false'
}
$ini = Join-Path $Kit '設定.ini'
if (Test-Path $ini) {
    foreach ($line in Get-Content $ini -Encoding UTF8) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*?)\s*$') { $cfg[$matches[1]] = $matches[2] }
    }
}

Write-Host '========================================' -ForegroundColor DarkGreen
Write-Host '  生生不息 · ManaCore 測試伺服器（26.3）' -ForegroundColor Green
Write-Host '========================================' -ForegroundColor DarkGreen

New-Item -ItemType Directory -Force -Path $Server, (Join-Path $Server 'plugins') | Out-Null

function Download($url, $dest, $what) {
    $tmp = "$dest.part"
    Write-Host "  下載 $what…（第一次需要幾分鐘）"
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    } catch {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        Fail "下載 $what 失敗：$($_.Exception.Message)`n  網址：$url`n  可以手動下載後放到：$dest"
    }
    Move-Item -Force $tmp $dest
}

# ---------------- 1. Java 25 ----------------
Step '檢查 Java 25'
function JavaMajor($exe) {
    # java -version 輸出在 stderr；暫時放寬錯誤處理，避免 PowerShell 把它當成錯誤中斷
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $out = (& $exe -version 2>&1 | Out-String) } catch { $out = '' }
    $ErrorActionPreference = $old
    if ($out -match 'version "(\d+)') { return [int]$matches[1] }
    return 0
}
$java = Join-Path $Server 'jre\bin\java.exe'
if (Test-Path $java) {
    Ok "使用工具包內建的 Java（$(JavaMajor $java)）"
} else {
    $sys = Get-Command java -ErrorAction SilentlyContinue
    if ($sys -and (JavaMajor $sys.Source) -ge 25) {
        $java = $sys.Source
        Ok "使用電腦上已安裝的 Java $(JavaMajor $java)"
    } else {
        $zip = Join-Path $Server 'jre.zip'
        Download $cfg.JAVA_URL $zip 'Java 25（Eclipse Temurin JRE，約 50 MB）'
        $tmpDir = Join-Path $Server 'jre-extract'
        if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
        Expand-Archive -Path $zip -DestinationPath $tmpDir -Force
        $inner = Get-ChildItem $tmpDir -Directory | Select-Object -First 1
        if (-not $inner) { Fail 'Java 壓縮檔內容不正確' }
        Move-Item $inner.FullName (Join-Path $Server 'jre')
        Remove-Item $tmpDir -Recurse -Force
        Remove-Item $zip -Force
        $java = Join-Path $Server 'jre\bin\java.exe'
        if (-not (Test-Path $java)) { Fail '找不到解壓後的 java.exe' }
        Ok "已安裝 Java $(JavaMajor $java) 到 server\jre"
    }
}

# ---------------- 2. 伺服器核心 ----------------
Step '檢查伺服器核心（DivineMC 26.3）'
$jar = Join-Path $Server 'server.jar'
if ((Test-Path $jar) -and ((Get-Item $jar).Length -gt 1MB)) {
    Ok 'server.jar 已存在'
} else {
    Download $cfg.SERVER_URL $jar 'DivineMC 26.3（約 70 MB）'
    Ok '已下載 server.jar'
}

# ---------------- 3. 插件 ----------------
Step '同步插件（工具包 plugins 資料夾 → server\plugins）'
foreach ($p in Get-ChildItem (Join-Path $Kit 'plugins') -Filter *.jar) {
    $name = ($p.BaseName -split '-')[0]            # ManaCore-1.3.0 → ManaCore
    $dest = Join-Path $Server "plugins\$($p.Name)"
    # 移除同一個插件的其他版本（伺服器沒在執行，可以直接換）
    Get-ChildItem (Join-Path $Server 'plugins') -Filter "$name-*.jar" |
        Where-Object { $_.Name -ne $p.Name } |
        ForEach-Object { Remove-Item $_.FullName -Force; Write-Host "  移除舊版 $($_.Name)" }
    if (-not (Test-Path $dest) -or (Get-FileHash $dest).Hash -ne (Get-FileHash $p.FullName).Hash) {
        Copy-Item $p.FullName $dest -Force
        Ok "安裝 $($p.Name)"
    } else {
        Ok "$($p.Name)（已是最新）"
    }
}

# ---------------- 4. 預設設定 ----------------
$props = Join-Path $Server 'server.properties'
if (-not (Test-Path $props)) {
    Copy-Item (Join-Path $Kit 'defaults\server.properties') $props
    Ok '建立預設 server.properties'
}

# ---------------- 5. EULA ----------------
$eula = Join-Path $Server 'eula.txt'
if (-not ((Test-Path $eula) -and ((Get-Content $eula -Raw) -match 'eula\s*=\s*true'))) {
    Step 'Minecraft 使用者授權合約（EULA）'
    Write-Host '  架設伺服器前必須同意 Minecraft EULA：https://aka.ms/MinecraftEULA'
    $ans = Read-Host '  已閱讀並同意請輸入 Y'
    if ($ans -notmatch '^[Yy]') { Fail '未同意 EULA，無法啟動伺服器。' }
    Set-Content -Path $eula -Value 'eula=true' -Encoding ASCII
    Ok '已同意 EULA'
}

# ---------------- 6. 啟動 ----------------
Set-Location $Server
do {
    Step "啟動伺服器（記憶體 $($cfg.MEMORY)）"
    Write-Host '  連線方式：Minecraft 26.3 → 多人遊戲 → 直接連線 → localhost' -ForegroundColor Yellow
    Write-Host '  關閉伺服器：在這個視窗輸入 stop 再按 Enter（不要直接按 X）' -ForegroundColor Yellow
    Write-Host ''
    & $java "-Xms$($cfg.MEMORY)" "-Xmx$($cfg.MEMORY)" -jar server.jar --nogui
    $code = $LASTEXITCODE
    if ($cfg.AUTO_RESTART -eq 'true') {
        Write-Host ''
        Write-Host '伺服器已停止，10 秒後自動重啟（按 Ctrl+C 取消）…' -ForegroundColor Yellow
        Start-Sleep -Seconds 10
    }
} while ($cfg.AUTO_RESTART -eq 'true')

Write-Host ''
Write-Host "伺服器已關閉（代碼 $code）。" -ForegroundColor Yellow
Read-Host '按 Enter 關閉視窗'
