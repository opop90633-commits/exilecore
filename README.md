# ExileCore

類 PoE 的 Minecraft 伺服器核心插件（Paper 26.2、Java 25）。
設計文件：《類 PoE Minecraft 伺服器：設計與技術規劃》。

目前進度：**階段 0 環境建置**。這個版本只做四件事：
專案能建置、一個指令啟動測試伺服器、`/ec` 管理指令會回應、資料庫連得上。

## 你需要先裝的東西

| 東西 | 說明 |
| --- | --- |
| JDK 25 | IntelliJ 已裝在 `~/.jdks/ms-25.0.4.1`（Microsoft OpenJDK）。沒有的話 Gradle 會自動下載 Temurin 25。 |
| Git | 版本控制與 GitHub 推送。 |
| IntelliJ IDEA（可選） | 直接「開啟」這個資料夾，它會認出 Gradle 專案。 |
| Docker Desktop（可選） | 只有要用 MariaDB 時才需要；開發期預設用 H2，不用裝。 |

## 第一次啟動測試伺服器

在專案資料夾開一個終端機（IntelliJ 底下的 Terminal 也可以）：

```powershell
# Windows
.\gradlew.bat runServer
```

```bash
# macOS / Linux
./gradlew runServer
```

第一次會花幾分鐘：下載 Gradle 9.8、Paper 26.2、FastAsyncWorldEdit，
然後編譯插件、把它放進 `run/plugins/` 並啟動伺服器。
看到 `Done (…s)! For help, type "help"` 就是啟動成功，主控台會有一行
`ExileCore 0.1.0-SNAPSHOT 已啟用（Minecraft 26.2，Java 25）`。

> 啟動參數裡的 `-Dcom.mojang.eula.agree=true` 等於同意 Minecraft EULA
> （https://aka.ms/MinecraftEULA），所以不用手動改 `eula.txt`。

接著：

1. 用 Minecraft 26.2 原版客戶端連 `localhost`。
2. 在伺服器主控台輸入 `op 你的遊戲名稱`（`/ec` 指令預設只有 OP 能用）。
3. 遊戲內輸入 `/ec ping`，應該看到 `ExileCore » pong TPS 20.0，每 tick x.x 毫秒`。
4. `/ec version` 看版本與已啟用模組，`/ec db` 看資料庫狀態。

主控台輸入 `stop` 關閉伺服器。之後每次啟動一樣是 `gradlew runServer`，
改了程式碼它會自動重新編譯。

### MythicMobs

MythicMobs 免費版沒有可以自動下載的網址，請手動安裝一次：

1. 到 https://mythiccraft.io/index.php?resources/mythicmobs.1/ 下載 5.13.0（支援 Paper 1.21.1 到 26.2）。
2. 把 jar 放進 `run/plugins/`。
3. 重新 `gradlew runServer`，啟動訊息裡會看到 MythicMobs 先於 ExileCore 載入。

沒裝也能啟動，ExileCore 把它設成可選依賴。

## 只建置、只跑測試

```powershell
.\gradlew.bat build        # 編譯 + 單元測試 + 產出 build\libs\ExileCore-0.1.0-SNAPSHOT.jar
.\gradlew.bat test         # 只跑單元測試
```

## 切換到 MariaDB

```powershell
docker compose up -d       # 啟動 MariaDB（帳密都在 docker-compose.yml）
```

把 `run/plugins/ExileCore/config.yml` 的 `storage.type` 改成 `mariadb`，
遊戲內 `/ec reload`，再 `/ec db` 確認顯示 `MariaDB 127.0.0.1:3306/exilecore`。

## 專案結構

```
build.gradle.kts                   Gradle 設定：Paper API、run-paper、測試
settings.gradle.kts                自動下載 JDK 的外掛
docker-compose.yml                 MariaDB
ci/github-actions-build.yml        GitHub Actions 設定（推上 GitHub 前搬到 .github/workflows/build.yml）
src/main/resources/
  paper-plugin.yml                 插件描述（api-version 26.2、可選依賴 MythicMobs／FAWE）
  config.yml                       預設設定檔
  data/                            階段 1 起放基底、詞綴、通貨等資料檔
src/main/java/tw/exilecore/
  ExileCore.java                   主類別：載入設定、啟用模組、註冊指令
  bootstrap/ExileCoreLoader.java   啟動前下載 HikariCP、H2、MariaDB 驅動到 libraries/
  module/                          Module 介面與 ModuleRegistry（依序啟用、反序停用）
  core/                            core 模組：設定快照、資料庫連線池、/ec 指令、文字工具
src/test/java/                     單元測試（不需要伺服器）
run/                               測試伺服器（自動產生，已在 .gitignore）
```

之後的模組依設計文件加在 `ExileCore.registerModules()`：
階段 1 `stats`、`item`、`currency`、`monster`；階段 2 `character`、`passive`、`zone`、`stash`；
階段 3 `skill`；階段 4 `map`；階段 5 `economy`。

## 推上 GitHub

先把 CI 設定搬到 GitHub 讀得到的位置（遠端工具不能直接寫 .github 資料夾，所以放在 ci/）：

```powershell
New-Item -ItemType Directory -Force .github\workflows | Out-Null
Move-Item ci\github-actions-build.yml .github\workflows\build.yml
```

```powershell
git init
git add .
git commit -m "階段 0：專案骨架與測試伺服器"
git branch -M main
git remote add origin https://github.com/你的帳號/exilecore.git
git push -u origin main
```

推上去之後 Actions 分頁會自動跑建置，成功的話 Artifacts 裡有 jar。
