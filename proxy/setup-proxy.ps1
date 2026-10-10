# 《星骸》改成「Velocity 代理在前面」的一次性設定（2026-10）。用 setup-proxy.bat 執行，先關掉伺服器。
#
#   setup-proxy.bat          改成走代理：產生轉發密鑰、改 run/ 底下 Paper 的設定、複製伺服器圖示
#   setup-proxy.bat -Undo    改回以前的直連（不走代理）
#
# 改的東西（第一次改之前會把原檔備份成 *.before-proxy）：
#   run/server.properties          online-mode=false、server-ip=127.0.0.1、server-port=25566、network-compression-threshold=-1
#   run/config/paper-global.yml    proxies.velocity：enabled=true、online-mode=true、secret=（跟代理同一把）
#   run/bukkit.yml                 settings.connection-throttle=-1（大家都從代理 127.0.0.1 連進來，不關掉會被當成洗連線）
param([switch]$Undo)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root 'run'
$velocityDir = Join-Path $PSScriptRoot 'velocity'
$limboDir = Join-Path $PSScriptRoot 'limbo'
$secretFile = Join-Path $velocityDir 'forwarding.secret'
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$path) { [IO.File]::ReadAllText($path, $utf8) }
function Write-Text([string]$path, [string]$text) { [IO.File]::WriteAllText($path, $text, $utf8) }
# 照原檔的換行寫回去（Windows 上是 CRLF）
function Newline([string]$path) { if ((Read-Text $path).Contains("`r`n")) { "`r`n" } else { "`n" } }

function Backup([string]$path) {
    $backup = "$path.before-proxy"
    if (-not (Test-Path $backup)) {
        Copy-Item $path $backup
        Write-Host "  備份 $(Split-Path -Leaf $path) → $(Split-Path -Leaf $backup)"
    }
}

# server.properties：key=value 一行一個
function Set-Properties([string]$path, [hashtable]$values) {
    $lines = [Collections.Generic.List[string]]((Read-Text $path) -split "`r?`n")
    foreach ($key in $values.Keys) {
        $found = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") {
                $lines[$i] = "$key=$($values[$key])"
                $found = $true
            }
        }
        if (-not $found) { $lines.Add("$key=$($values[$key])") }
    }
    Write-Text $path ($lines -join (Newline $path))
}

# YAML：只改某個路徑底下的鍵（例如 proxies → velocity 底下的 enabled），保留其他內容與註解。
# 照縮排（每層兩格）記住目前在哪個路徑底下。
function Set-Yaml([string]$path, [string[]]$parents, [hashtable]$values) {
    $lines = (Read-Text $path) -split "`r?`n"
    $stack = New-Object string[] 32
    $changed = @{}
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -notmatch '^(\s*)([A-Za-z0-9_.-]+):') { continue }
        $indent = $Matches[1].Length
        $key = $Matches[2]
        if ($indent % 2 -ne 0) { continue }
        $level = $indent / 2
        $stack[$level] = $key
        if ($level -ne $parents.Count -or -not $values.ContainsKey($key)) { continue }
        $match = $true
        for ($d = 0; $d -lt $parents.Count; $d++) {
            if ($stack[$d] -ne $parents[$d]) { $match = $false }
        }
        if ($match) {
            $lines[$i] = (' ' * $indent) + "${key}: $($values[$key])"
            $changed[$key] = $true
        }
    }
    foreach ($key in $values.Keys) {
        if (-not $changed[$key]) { throw "$(Split-Path -Leaf $path) 裡找不到 $($parents -join '.').$key（Paper 改版了？請照 proxy/README.md 手動改）" }
    }
    Write-Text $path ($lines -join (Newline $path))
}

$properties = Join-Path $run 'server.properties'
$paperGlobal = Join-Path $run 'config\paper-global.yml'
$bukkit = Join-Path $run 'bukkit.yml'
foreach ($file in @($properties, $paperGlobal, $bukkit)) {
    if (-not (Test-Path $file)) {
        Write-Host "找不到 $file。先用 run-server.bat 開一次伺服器（產生設定檔）、關掉，再執行這個。" -ForegroundColor Red
        exit 1
    }
}

if ($Undo) {
    Write-Host '改回直連（不走代理）…' -ForegroundColor Yellow
    Set-Properties $properties @{ 'online-mode' = 'true'; 'server-ip' = ''; 'server-port' = '25565'; 'network-compression-threshold' = '256' }
    Set-Yaml $paperGlobal @('proxies', 'velocity') @{ 'enabled' = 'false' }
    Set-Yaml $bukkit @('settings') @{ 'connection-throttle' = '4000' }
    Write-Host '好了。之後用 run-server.bat 直接開（玩家連 25565，跟以前一樣）；不要再開 Velocity（會搶 25565）。' -ForegroundColor Green
    exit 0
}

Write-Host '改成走 Velocity 代理…' -ForegroundColor Yellow

# 1. 轉發密鑰：代理、Paper、等待室三邊要一樣。已經有就沿用
if (-not (Test-Path $secretFile)) {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = ([Convert]::ToBase64String($bytes) -replace '[+/=]', '')
    Write-Text $secretFile $secret
    Write-Host '  產生了新的轉發密鑰 proxy/velocity/forwarding.secret（不要給別人看）'
}
$secret = (Read-Text $secretFile).Trim()
if ($secret.Length -lt 8) { throw 'forwarding.secret 是空的或太短：刪掉它再執行一次會重新產生' }

# 2. Paper 的設定
Backup $properties
Backup $paperGlobal
Backup $bukkit
Set-Properties $properties @{ 'online-mode' = 'false'; 'server-ip' = '127.0.0.1'; 'server-port' = '25566'; 'network-compression-threshold' = '-1' }
Set-Yaml $paperGlobal @('proxies', 'velocity') @{ 'enabled' = 'true'; 'online-mode' = 'true'; 'secret' = "'$secret'" }
Set-Yaml $bukkit @('settings') @{ 'connection-throttle' = '-1' }
Write-Host '  改好 run/server.properties、run/config/paper-global.yml、run/bukkit.yml'

# 3. 伺服器列表的圖示：代理與等待室也用同一張
$icon = Join-Path $run 'server-icon.png'
if (Test-Path $icon) {
    Copy-Item $icon (Join-Path $velocityDir 'server-icon.png') -Force
    Copy-Item $icon (Join-Path $limboDir 'server-icon.png') -Force
    Write-Host '  複製了伺服器圖示'
}

# 4. 還缺什麼（要自己下載的）
$missing = @()
if (-not (Get-ChildItem $velocityDir -Filter 'velocity*.jar' -ErrorAction SilentlyContinue)) { $missing += 'proxy/velocity/velocity-4.x.jar（https://papermc.io/downloads/velocity）' }
if (-not (Get-ChildItem (Join-Path $velocityDir 'plugins') -Filter '*ailover*.jar' -ErrorAction SilentlyContinue)) { $missing += 'proxy/velocity/plugins/VelocityFailover-*.jar（https://modrinth.com/project/zLB8BIPd）' }
if (-not (Test-Path (Join-Path $limboDir 'pico_limbo.exe'))) { $missing += 'proxy/limbo/pico_limbo.exe（https://github.com/Quozul/PicoLimbo/releases 的 pico_limbo_windows-x86_64.zip）' }

Write-Host ''
Write-Host '設定好了。' -ForegroundColor Green
if ($missing.Count -gt 0) {
    Write-Host '還要下載這些（放到對的位置），再用 start-all.bat 啟動：' -ForegroundColor Yellow
    foreach ($m in $missing) { Write-Host "  - $m" }
} else {
    Write-Host '之後用 start-all.bat 啟動（先開代理與等待室，再開星骸）。'
}
Write-Host '注意：從現在起只能從代理（25565）進來；單獨用 run-server.bat 開的話，玩家要等代理也開著才連得上。'
