# Velocity 代理：重開星骸不踢人

星骸前面多放一個 **Velocity 代理**。玩家連的是代理，代理再把他們轉給星骸本體。

重開星骸時，代理先把線上的玩家送進一個**等待室**：一片黑色的終界天空，上方有 Boss 條寫「重新啟動中」。等星骸開好，再一個一個自動送回去。玩家全程不會斷線，也不用重新登入。

```
玩家 ──► Velocity 代理（:25565，跟以前一樣）
            │
            ├──► 星骸本體 Paper（127.0.0.1:25566）
            │
            └──► 等待室 PicoLimbo（127.0.0.1:25567，只在星骸重開時用）

VelocityFailover（代理的插件）：看到「Server closed」就把人送進等待室，
每 2 秒 ping 一次本體，開好了再等 5 秒，一個一個送回去。
```

玩家要連的位址**不用改**，還是 `你的 IP:25565`（Radmin 的 `26.59.203.226`）。

---

## 要下載的三樣

| 東西 | 放到哪裡 | 哪裡下載 | 版本 |
|---|---|---|---|
| **Velocity**（代理） | `proxy/velocity/velocity-4.x.x-xx.jar` | <https://papermc.io/downloads/velocity> | **4.2 以上**（26.2 要 4.x；3.x 不支援） |
| **VelocityFailover**（代理的插件） | `proxy/velocity/plugins/` | <https://modrinth.com/project/zLB8BIPd> | 支援 26.2、要 Velocity 4.x 與 Java 25 |
| **PicoLimbo**（等待室） | `proxy/limbo/pico_limbo.exe` | <https://github.com/Quozul/PicoLimbo/releases> 的 `pico_limbo_windows-x86_64.zip`，解壓縮出 `pico_limbo.exe` | **1.14 以上**（支援 26.2） |

Java 用 25 以上的版本。啟動腳本會依序找：`JAVA_HOME` → IntelliJ 下載的 `%USERPROFILE%\.jdks\` → PATH。你電腦上的 `ms-25.0.4.1` 就可以用。

---

## 第一次設定

1. **關掉星骸**（在主控台輸入 `stop`）。
2. 把上面三樣下載好，放到表格裡的位置。
3. 在專案資料夾執行 **`setup-proxy.bat`**。它會：
   - 產生轉發密鑰 `proxy/velocity/forwarding.secret`。代理、星骸、等待室三邊共用這一把；不要給別人看，也不要放上 Git。
   - 修改 `run/` 底下 Paper 的設定（見下面「Paper 改了什麼」）。改之前會把原檔備份成 `*.before-proxy`。
   - 把 `run/server-icon.png` 複製給代理和等待室，伺服器列表的圖示才會一樣。
4. 執行 **`start-all.bat`**，然後進遊戲連 `你的 IP:25565`，確認進得去，而且角色、倉庫都在。

> 如果進去後**角色、倉庫都不見了**，像一個新玩家，就是轉發沒接好：星骸拿到的是離線版的 UUID。
> 這時**不要建立角色**。先關掉伺服器，看下面的「出問題時」。角色資料不會因此消失，接好後就會回來。

---

## 平常怎麼開

執行 **`start-all.bat`**，會依序做這些事：

1. 開等待室（新視窗「Xinghai Limbo」）。
2. 開代理（新視窗「Xinghai Velocity」）。
3. 在原本的視窗重新建置插件，再開星骸（跟 `run-server.bat` 一樣）。

代理和等待室已經開著的話，不會再開一次。

## 重開星骸（不踢人）

1. 在星骸的主控台輸入 **`stop`**。
   - 線上的玩家會被送進等待室，畫面中間顯示「重新啟動中」。
2. `start-all.bat` 的視窗會問要不要再開一次：**按 Enter**，它會重新建置插件、再開星骸。
3. 星骸開好約 5～10 秒後，玩家會被一個一個送回來。

改了程式、需要「重新建置、重開伺服器」的時候，以後就照這樣做。只改 yml 的話，照舊用 `/ec reload` 就好。

**一定要用 `stop` 關。** 直接關掉視窗、按 Ctrl+C、按 IntelliJ 的紅色停止鍵，都是硬關。硬關時玩家不會收到「Server closed」，代理不知道是重開，就會直接把人斷線。

從 IntelliJ 開星骸（Gradle `runServer`）也可以，只要代理和等待室開著就好。關的時候一樣在主控台輸入 `stop`。

---

## Paper 改了什麼

`setup-proxy.bat` 會幫你改。要手動改的話，改這幾個地方：

**`run/server.properties`**

```properties
online-mode=false                 # 正版驗證改由代理做（不是關掉！轉發會把正版 UUID 交過來）
server-ip=127.0.0.1               # 只讓本機的代理連進來，外面直連不到
server-port=25566                 # 25565 讓給代理
network-compression-threshold=-1  # 代理在同一台電腦，這段不用壓縮；壓縮交給代理做
```

**`run/config/paper-global.yml`**

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: '（forwarding.secret 的內容）'
```

**`run/bukkit.yml`**

```yaml
settings:
  connection-throttle: -1   # 大家都從代理（127.0.0.1）連進來，不關的話一起回來時會被當成洗連線
```

`spigot.yml` 的 `bungeecord` 維持 `false`。Velocity 用的是自己的轉發，不是 BungeeCord 的。

## 這個資料夾裡的設定檔

| 檔案 | 內容 |
|---|---|
| `velocity/velocity.toml` | 代理：`modern` 轉發；`xinghai = 127.0.0.1:25566`、`limbo = 127.0.0.1:25567`；登入只進 xinghai；伺服器列表的說明照星骸本體的（`data/world.yml` 的 motd、維護中的提示），本體重開時才用這裡寫的同一段 |
| `velocity/plugins/velocityfailover/config.yml` | 等待室是 `limbo`、照顧 `xinghai`；關機訊息含「Server closed」才算重開；中文訊息 |
| `limbo/server.toml` | 等待室：只聽 127.0.0.1、終界的夜空、旁觀者模式、Boss 條、標題；密鑰從環境變數讀（啟動腳本會設） |
| `setup-proxy.ps1` | 第一次設定（`setup-proxy.bat`）；`-Undo` 改回直連 |
| `start-all.ps1` | 啟動（`start-all.bat`） |

改 `velocity.toml` 要重開代理才會生效：關掉「Xinghai Velocity」視窗，再執行一次 `start-all.bat`。這時玩家會斷線，所以盡量少改。

## 跟 ExileCore 有關的

- **角色資料不受影響**。`modern` 轉發把正版 UUID、皮膚、真實 IP 交給星骸，跟以前直連一模一樣。
- **介面資源包、天賦規劃器、管理網頁**照舊走 `25580`，網址是 `config.yml` 的 `web.public-url`，不受代理影響。
- **TAB 的 Ping** 還是玩家到你這台電腦的延遲：代理會把心跳封包原樣轉過去。
- 轉發的是真實 IP，所以從這台電腦連進來的人，ExileCore 一樣認得出是本機。
- 維修模式、封鎖、AFK 等其他踢人訊息裡沒有「Server closed」，所以照常把人踢掉，不會送進等待室。

## 出問題時

| 狀況 | 原因、怎麼辦 |
|---|---|
| 進不去，畫面寫 `Unable to verify player details`（或 Velocity 視窗有轉發失敗的錯誤） | 三邊的密鑰不一樣：再執行一次 `setup-proxy.bat`（已經有的密鑰會沿用，只重新寫進 Paper） |
| 進遊戲變成新玩家 | Paper 沒開 Velocity 轉發，或是你直接連到 25566：檢查 `paper-global.yml` 的 `velocity.enabled: true`，玩家要連 25565 |
| 連線時顯示 `Unable to connect you to xinghai` 或 `There are no available servers…` | 星骸本體還沒開好，或已經關了：等它開好再連 |
| 重開時玩家直接被斷線，沒有進等待室 | 不是用 `stop` 關的（硬關），或是等待室沒開：看「Xinghai Limbo」視窗 |
| 等待室視窗出現 `Missing environment variable: FORWARDING_SECRET` | 直接點兩下 `pico_limbo.exe` 開的：要用 `start-all.bat` 開 |
| `start-all.bat` 說找不到 Java 25 | 設定 `JAVA_HOME` 指向 JDK 25，或在 IntelliJ 的 Project Structure 下載一個 JDK 25 |
| 想改回不走代理 | 關掉所有視窗，執行 `setup-proxy.bat -Undo`，之後照舊用 `run-server.bat` |

## 參考

- Velocity 設定：<https://docs.papermc.io/velocity/configuration/>
- Velocity 支援的版本：<https://docs.papermc.io/velocity/server-compatibility/>
- VelocityFailover 原始碼：<https://github.com/blixy77/VelocityFailover>
- PicoLimbo 文件：<https://github.com/Quozul/PicoLimbo>（`docs/config/`）
