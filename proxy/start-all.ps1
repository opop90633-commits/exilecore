# 《星骸》啟動（2026-10）：先開等待室（PicoLimbo）與代理（Velocity），各開一個視窗；再在這個視窗開星骸本體。
# 用 start-all.bat 執行。第一次用之前先執行 setup-proxy.bat（見 proxy/README.md）。
#
# 星骸本體關掉之後（在主控台輸入 stop），這個視窗會問要不要重新建置、再開一次
# （管理網頁或 /ec restart 排的重新啟動不問，直接再開）：
# 這段時間玩家在等待室等，開好了自動送回來，不會被踢出遊戲。
# 代理與等待室已經開著的話不會再開一次（只開本體）。
param([switch]$NoLoop)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$root = Split-Path -Parent $PSScriptRoot
$velocityDir = Join-Path $PSScriptRoot 'velocity'
$limboDir = Join-Path $PSScriptRoot 'limbo'
$secretFile = Join-Path $velocityDir 'forwarding.secret'
$paperGlobal = Join-Path $root 'run\config\paper-global.yml'
$utf8 = New-Object System.Text.UTF8Encoding($false)

$PROXY_PORT = 25565
$PAPER_PORT = 25566
$LIMBO_PORT = 25567

function Fail([string]$message) {
    Write-Host $message -ForegroundColor Red
    Write-Host '（詳細步驟見 proxy/README.md）' -ForegroundColor DarkGray
    exit 1
}

# 有沒有東西在聽這個埠（只看本機）
function Test-Port([int]$port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect('127.0.0.1', $port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(500)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Port([int]$port, [int]$seconds, [string]$name) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $port) { return }
        Start-Sleep -Milliseconds 500
    }
    Fail "$name 在 $seconds 秒內沒有開好（埠 $port）：看它的視窗裡的錯誤訊息"
}

# Java 主版本（看 JDK 資料夾的 release 檔）
function Get-JavaMajor([string]$javaExe) {
    $jdkHome = Split-Path -Parent (Split-Path -Parent $javaExe)
    $release = Join-Path $jdkHome 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
}

# 找 Java 25 以上（Velocity 4 與 VelocityFailover 都要）：JAVA_HOME → IntelliJ 下載的 ~/.jdks → PATH
function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdks) {
        $candidates += Get-ChildItem $jdks -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
    }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($java in $candidates) {
        if ((Test-Path $java) -and (Get-JavaMajor $java) -ge 25) { return $java }
    }
    return $null
}

# ---- 檢查 ----

if (-not (Test-Path $secretFile)) { Fail '還沒設定：先執行 setup-proxy.bat' }
$secret = ([IO.File]::ReadAllText($secretFile, $utf8)).Trim()
$paperText = if (Test-Path $paperGlobal) { [IO.File]::ReadAllText($paperGlobal, $utf8) } else { '' }
if (-not $paperText.Contains($secret)) {
    Fail 'run/config/paper-global.yml 的密鑰跟 proxy/velocity/forwarding.secret 不一樣（或還沒改）：再執行一次 setup-proxy.bat'
}
$velocityJar = Get-ChildItem $velocityDir -Filter 'velocity*.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $velocityJar) { Fail '找不到 proxy/velocity/velocity-*.jar：到 https://papermc.io/downloads/velocity 下載放進去' }
if (-not (Get-ChildItem (Join-Path $velocityDir 'plugins') -Filter '*ailover*.jar' -ErrorAction SilentlyContinue)) {
    Fail '找不到 proxy/velocity/plugins/VelocityFailover-*.jar：到 https://modrinth.com/project/zLB8BIPd 下載放進去'
}
$limboExe = Join-Path $limboDir 'pico_limbo.exe'
if (-not (Test-Path $limboExe)) { Fail '找不到 proxy/limbo/pico_limbo.exe：到 https://github.com/Quozul/PicoLimbo/releases 下載 pico_limbo_windows-x86_64.zip 解壓縮放進去' }
$java = Find-Java
if (-not $java) { Fail '找不到 Java 25 以上：設定 JAVA_HOME，或在 IntelliJ 下載一個 JDK 25（會放在 %USERPROFILE%\.jdks）' }

# ---- 等待室 ----

if (Test-Port $LIMBO_PORT) {
    Write-Host "等待室已經開著（$LIMBO_PORT）" -ForegroundColor DarkGray
} else {
    Write-Host '開等待室（PicoLimbo）…' -ForegroundColor Yellow
    $env:FORWARDING_SECRET = $secret # 等待室的 server.toml 用 ${FORWARDING_SECRET} 讀，密碼不寫在檔案裡
    Start-Process -FilePath 'cmd.exe' -WorkingDirectory $limboDir -ArgumentList "/k title Xinghai Limbo & chcp 65001 >nul & pico_limbo.exe --config server.toml"
    Remove-Item Env:FORWARDING_SECRET
    Wait-Port $LIMBO_PORT 30 '等待室'
}

# ---- 代理 ----

if (Test-Port $PROXY_PORT) {
    Write-Host "代理已經開著（$PROXY_PORT）" -ForegroundColor DarkGray
} else {
    Write-Host "開代理（Velocity，$($velocityJar.Name)，Java $(Get-JavaMajor $java)）…" -ForegroundColor Yellow
    $flags = '-Xms256M -Xmx512M -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8'
    Start-Process -FilePath 'cmd.exe' -WorkingDirectory $velocityDir -ArgumentList ('/k title Xinghai Velocity & chcp 65001 >nul & "' + $java + '" ' + $flags + ' -jar "' + $velocityJar.Name + '"')
    Wait-Port $PROXY_PORT 60 '代理'
}

# ---- 星骸本體 ----

if (Test-Port $PAPER_PORT) {
    Fail "星骸本體已經開著（$PAPER_PORT）：要重開就在它的主控台輸入 stop"
}
while ($true) {
    Write-Host '開星骸（重新建置插件、啟動 Paper）…' -ForegroundColor Yellow
    & (Join-Path $root 'run-server.bat')
    if ($NoLoop) { break }
    Write-Host ''
    # 管理網頁、/ec restart 排的重新啟動：關服前留下 restart.flag，看到就不問、直接再開
    $flag = Join-Path $root 'run\plugins\ExileCore\restart.flag'
    if (Test-Path $flag) {
        Remove-Item $flag -Force
        Write-Host '排定的重新啟動：5 秒後自動重新建置、再開（線上的玩家在等待室等）。要停下來就按 Ctrl+C。' -ForegroundColor Cyan
        Start-Sleep -Seconds 5
        continue
    }
    Write-Host '星骸關掉了。線上的玩家在等待室等，開好了會自動回來。' -ForegroundColor Cyan
    $answer = Read-Host '按 Enter 重新建置並再開一次；輸入 q 結束（代理與等待室的視窗要自己關）'
    if ($answer -eq 'q') { break }
}
