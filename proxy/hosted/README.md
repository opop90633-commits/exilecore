# 主機商版（3 個 port）：等待室塞進 Velocity 裡

主機商只給 3 個 port：主伺服器、網頁（25580，資源包也從這裡下載）、Velocity。所以不開第四台等待室，
改用 Velocity 的 **LimboAPI + LimboReconnect**：主伺服器重開時玩家留在代理裡面的虛擬等待室（終界夜空、旁觀者），
開好了自動送回去。本機測試（`proxy/velocity/`、`proxy/limbo/`）照舊，這個資料夾只給主機商那邊用。

```
玩家 ──► Velocity（主機 IP:VELOCITY_PORT）
            └──► 星骸本體（內部位址 node0.tj:XINGHAI_PORT；直連會被拒絕）
                 重開時：玩家留在 Velocity 的虛擬等待室 → 開好了送回去
```

## 分割

| 分割 | 核心（Egg） | CPU | 記憶體 | 硬碟 | 資料庫 | 端口 |
|---|---|---|---|---|---|---|
| Velocity | Velocity | 100% | 1 GiB | 2 GiB | 0 | 1 |

主伺服器留下其他全部。

## Velocity 這台要放的檔案（SFTP 到根目錄）

| 檔案 | 哪裡來 |
|---|---|
| `velocity.toml` | 這個資料夾的；把 `VELOCITY_PORT`、`XINGHAI_PORT` 換成真的（`xinghai` 的主機一定寫 `node0.tj`，見下面） |
| `forwarding.secret` | 專案 `proxy/velocity/forwarding.secret`（跟主伺服器共用；不要放上 Git） |
| `server-icon.png` | 專案 `proxy/velocity/server-icon.png` |
| `plugins/limboapi-1.1.27-SNAPSHOT.jar` | <https://github.com/Elytrium/LimboAPI/releases/tag/dev-build>（**要 dev-build**：正式版 1.1.26 是 2024 年的，不支援 26.2） |
| `plugins/LimboReconnect-*.jar` | <https://github.com/Elytrium/LimboReconnect/releases/tag/dev-build>（dev-build，26.1 以上） |
| `plugins/limboreconnect/config.yml` | 這個資料夾的 |

**不要放 VelocityFailover**（本機版用的那個）：它要一台真的等待室，跟 LimboReconnect 會打架。
Egg 會自己下載 Velocity 本體，版本選 4.x（26.2 要 4.2 以上）。

## Velocity 連主伺服器要用內部位址（node0.tj）

主機商說明：架分流時用內部位址 **`node0.tj`**，不要用 `proxy.tjservice.org`，讓流量走內網。

`proxy.tjservice.org` 是主機商在新加坡的**對外轉發點**。Velocity 如果用它連主伺服器，
代理跟本體之間的流量會先出去轉發點再繞回同一台主機：轉發點的頻寬被塞滿，所有封包排隊，
全服 PING 從約 120ms 變成 500ms 以上（2026-10-11 實測），流量額度也被算兩次。

```toml
[servers]
xinghai = "node0.tj:XINGHAI_PORT"
```

改完 `velocity reload`（或重啟 Velocity）。想先試：多加一行 `xinghai-lan = "node0.tj:XINGHAI_PORT"`，
`velocity reload` 後在遊戲裡 `/server xinghai-lan`，進得去再改正式的那行。

## 主伺服器（星骸本體）要改的

`server.properties`
```properties
online-mode=false                 # 正版驗證改由 Velocity 做；轉發會把正版 UUID 交過來
server-ip=                        # 留空（不能填 127.0.0.1，Velocity 在別的容器）
network-compression-threshold=256
```
`config/paper-global.yml`
```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '（forwarding.secret 的內容）'
```
`bukkit.yml`
```yaml
settings:
  connection-throttle: -1   # 大家都從 Velocity 的 IP 進來，不關的話一起回來時會被當成洗連線
```

主伺服器的 port 對外看得到也沒關係：開了 Velocity 轉發之後，沒帶密鑰的連線會被拒絕（`Unable to verify player details`）。

## 玩家連哪裡

伺服器列表的位址改成 **主機 IP:VELOCITY_PORT**。網頁、資源包照舊走 25580（`config.yml` 的 `web.public-url`）。

## 重開

面板的「重啟」或主控台 `stop` 都會送出 `Server closed`，玩家會留在等待室。**不要用「強制停止」**，玩家會直接斷線。
部署腳本 `python tools/deploy.py` 走的就是面板的重啟。

## 出問題時

| 狀況 | 怎麼辦 |
|---|---|
| Velocity 主控台說 LimboAPI 載入失敗、版本不合 | 兩個都換成最新的 dev-build；Velocity 版本要 4.x |
| 重開時玩家直接斷線 | 看踢人訊息是不是 `Server closed`（用了強制停止就不是）；或 `useLimbo` 被改成 false |
| 進遊戲變成新玩家 | Paper 沒開 Velocity 轉發，或密鑰不一樣：檢查 `paper-global.yml` |
| 連線顯示 `Unable to connect you to xinghai` | 主伺服器還沒開好，或 `velocity.toml` 的 `xinghai` 填錯位址／port（要 `node0.tj:XINGHAI_PORT`） |
| 全服 PING 都很高（`/spark ping` 中位數好幾百 ms），但伺服器列表的 ms 正常 | `xinghai` 寫成了 `proxy.tjservice.org`：改回 `node0.tj`，`velocity reload` |
| 人多時 PING 一起飆高 | `/ec skill fx` 看過去 10 秒誰的技能特效送最多封包 |
