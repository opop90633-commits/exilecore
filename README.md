# ExileCore

類 PoE 的 Minecraft 伺服器核心插件（Paper 26.2、Java 25）。
設計文件：《類 PoE Minecraft 伺服器：設計與技術規劃》。倉庫：https://github.com/opop90633-commits/exilecore

目前進度：**階段 1 批次 1 — 數值與物品**。已經可以：打怪掉裝、鑑定、用通貨改造、換裝後屬性即時變化。
機制照 PoE，名稱是自己的（通貨叫微光石、亂流石、至臻石…）。

## 你需要先裝的東西

| 東西 | 說明 |
| --- | --- |
| JDK 25 | IntelliJ 已裝在 `~/.jdks/ms-25.0.4.1`（Microsoft OpenJDK）。沒有的話 Gradle 會自動下載 Temurin 25。 |
| Git | 版本控制與 GitHub 推送。 |
| IntelliJ IDEA（可選） | 直接「開啟」這個資料夾，它會認出 Gradle 專案。 |
| Docker Desktop（可選） | 只有要用 MariaDB 時才需要；開發期預設用 H2，不用裝。 |

## 啟動測試伺服器

在專案資料夾開一個終端機（IntelliJ 底下的 Terminal 也可以）：

```powershell
.\gradlew.bat runServer
```

第一次會下載 Gradle 9.8、Paper 26.2、FastAsyncWorldEdit，然後編譯插件、放進 `run/plugins/` 並啟動。
看到 `Done (…s)!` 與 `ExileCore 0.1.0-SNAPSHOT 已啟用（Minecraft 26.2，Java 25）` 就是成功。
啟動參數裡的 `-Dcom.mojang.eula.agree=true` 等於同意 Minecraft EULA（https://aka.ms/MinecraftEULA）。

用 26.2 原版客戶端連 `localhost`，主控台輸入 `op 你的遊戲名稱`（`/ec` 指令預設只有 OP 能用）。
主控台 `stop` 關閉；改了程式碼再 `gradlew runServer` 會自動重新編譯。

MythicMobs 免費版沒有可自動下載的網址：到 https://mythiccraft.io/index.php?resources/mythicmobs.1/ 下載 5.13.0
放進 `run/plugins/`。沒裝也能啟動（可選依賴）；階段 1 批次 1 還沒用到它，怪物先用原版生物。

## 階段 1 批次 1 怎麼測

登入後畫面下方會出現動作列 `❤ 生命 / 上限　◆ 魔力 / 上限`（有護盾裝備時多一個 ⛨）。
原版的飢餓、自然回血、經驗都關掉了：血條只是比例顯示，經驗條顯示魔力。

### 1. 物品與通貨

```
/ec give short_sword            給一把普通短劍（物品等級 = 你的等級）
/ec give plate_body rare 40     稀有的鐵胸甲，物品等級 40（掉落時預設未鑑定）
/ec givecurrency identify_scroll 10
/ec givecurrency glimmer_orb 10
/ec givecurrency turmoil_orb 10
```

通貨用法與 PoE 相同：把通貨**拿在游標上**，左鍵點一件裝備就套用一顆。不能用會在動作列提示。

| 通貨 | 作用 |
| --- | --- |
| 鑑定卷軸 identify_scroll | 鑑定 |
| 微光石 glimmer_orb | 普通 → 魔法 |
| 增輝石 lustre_orb | 魔法物品加一條詞綴 |
| 流轉石 flux_orb | 重骰魔法物品 |
| 豐饒石 bounty_orb | 魔法 → 稀有（保留詞綴再加一條） |
| 星火石 spark_orb | 普通 → 稀有 |
| 亂流石 turmoil_orb | 重骰稀有物品全部詞綴 |
| 至臻石 zenith_orb | 稀有物品加一條詞綴 |
| 淨白石 blank_orb | 移除所有詞綴 |
| 天啟石 oracle_orb | 重骰數值，階級不變 |
| 賜福石 blessing_orb | 重骰固定詞綴 |
| 磨刀石 whetstone／護甲碎片 armour_scrap | 品質 |

`/ec item` 印出手上物品的原始資料與修飾器，`/ec identify` 免卷軸鑑定（測試用）。

### 2. 角色與屬性

```
/ec level 30        設定測試等級（存在你的玩家資料裡，重啟還在）
/ec stats           看生命、魔力、護盾、防禦、抗性、預設攻擊的平均傷害與每秒傷害
/ec debug           開關命中除錯訊息：每次你打人或被打都會在聊天顯示計算結果
```

裝備需求（等級、力量、敏捷、智慧）不符的裝備，詞綴不會生效。目前有算的欄位：主手、副手（盾）、四件防具；
戒指、項鍊、腰帶要等階段 2 的角色面板。

### 3. 怪物與掉落

自然生成的敵對生物會等級化：名字變成「Lv.12 殭屍」，離出生點越遠越高（每 64 格 +2 級，上限 60）。
魔法怪（藍名）帶一個詞綴、稀有怪（黃名）帶 2 到 4 個並有隨機名字，血量與傷害倍率、掉落都比較好。

```
/ec spawn zombie 20          在面前生成一隻 20 級殭屍
/ec spawn skeleton 45 rare   45 級稀有骷髏
```

擊殺後走我們的掉落表：通貨與裝備（魔法與稀有裝備未鑑定，物品等級 = 怪物等級），掉落後 5 秒內只有擊殺者能撿。
原版掉落物與經驗球全部關掉。

### 4. 戰鬥手感

- 原版傷害數字一律丟掉。你打怪：武器傷害 + 詞綴 → 命中／暴擊 → 怪物護甲減免；怪打你：等級表 → 你的護甲／抗性 → 先扣護盾再扣生命。
- 攻擊速度由武器決定（`/ec stats` 看每秒次數），連點超過攻速的揮擊不算數；原版的攻擊冷卻條已拉到無感。
- 怪物與玩家的無敵幀都歸零，多隻怪同時打你每一下都算。
- 掉落、火、岩漿等環境傷害換算成我們的傷害類型（`config.yml` 的 `combat.environment`）。
- 死亡不掉裝備與經驗（掉經驗的懲罰在階段 2）。

### 5. 想改數值

`run/plugins/ExileCore/data/` 底下是全部資料檔（第一次啟動從 jar 複製出來），
改完在遊戲內 `/ec reload`。哪個檔管什麼見 `data/README.md`。設定檔寫錯會在主控台指出檔名與欄位。

## 只建置、只跑測試

```powershell
.\gradlew.bat build        # 編譯 + 單元測試 + 產出 build\libs\ExileCore-0.1.0-SNAPSHOT.jar
.\gradlew.bat test         # 只跑單元測試（引擎、生成、通貨、怪物、掉落，用真實資料檔）
```

## 切換到 MariaDB

```powershell
docker compose up -d       # 啟動 MariaDB（帳密都在 docker-compose.yml）
```

把 `run/plugins/ExileCore/config.yml` 的 `storage.type` 改成 `mariadb`，遊戲內 `/ec reload`，再 `/ec db` 確認。

## 專案結構

```
build.gradle.kts                   Gradle 設定：Paper API、run-paper、測試
docker-compose.yml                 MariaDB
ci/github-actions-build.yml        GitHub Actions 設定（推上 GitHub 前搬到 .github/workflows/）
src/main/resources/
  paper-plugin.yml                 插件描述（api-version 26.2、可選依賴 MythicMobs／FAWE）
  config.yml                       預設設定檔（資料庫、資源包開關、環境傷害）
  data/                            基底、詞綴、通貨、怪物、掉落、名字（見 data/README.md）
src/main/java/tw/exilecore/
  ExileCore.java                   主類別：載入設定、依序啟用模組、註冊指令
  bootstrap/                       啟動前下載 HikariCP、H2、MariaDB 驅動到 libraries/
  module/                          Module 介面與 ModuleRegistry
  core/                            設定快照、資料庫連線池、/ec 指令、文字工具、YAML 讀取
  stats/                           數值引擎：修飾器、屬性表、公式、傷害計算、HitEvent
  item/                            基底、詞綴、稀有度、生成、PDC 儲存、描述渲染
  currency/                        通貨定義、操作、游標套用
  character/                       玩家狀態（生命／魔力／護盾）、屬性表重算、動作列
  monster/                         原版生物等級化、稀有度、詞綴、掉落表
  combat/                          接管原版傷害事件、環境傷害換算
src/test/java/                     單元測試（不需要伺服器）
run/                               測試伺服器（自動產生，已在 .gitignore）
```

之後的模組依設計文件加在 `ExileCore.registerModules()`：階段 2 `passive`、`zone`、`stash`（character 補職業、經驗、持久化）；
階段 3 `skill`；階段 4 `map`；階段 5 `economy`。

## 推上 GitHub

倉庫已建好（上面的連結）。要讓本機資料夾和它連起來（之後改完只要 add／commit／push）：

```powershell
New-Item -ItemType Directory -Force .github\workflows | Out-Null
Copy-Item ci\github-actions-build.yml .github\workflows\build.yml
git init
git add .
git commit -m "階段 1 批次 1：數值引擎與物品"
git branch -M main
git remote add origin https://github.com/opop90633-commits/exilecore.git
git push -f origin main
```

`push -f` 會以本機這份能跑的版本覆蓋 GitHub 上用網頁上傳的那份。之後 Actions 會自動建置與跑測試。
