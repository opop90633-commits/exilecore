plugins {
    java
    // 一個指令下載並啟動 Paper 測試伺服器：./gradlew runServer
    // 3.1.0 起才支援 Gradle 9.7+；舊版在 Gradle 9 會因為屬性註解檢查而失敗
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "tw.exilecore"
version = "0.1.0-SNAPSHOT"
description = "類 PoE 的 Minecraft 伺服器核心插件"

java {
    // Minecraft 26.1 起需要 Java 25；找不到會由 foojay 外掛自動下載
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents（2026-10：滑鼠指到物品時「對你的技能」的提示）
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Paper 26.2 API。伺服器本身提供，所以是 compileOnly
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    // 2026-10：PacketEvents（伺服器上另外裝 packetevents 插件；沒裝也能啟動，只是沒有物品的技能提示）
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")

    // 執行期函式庫（HikariCP、H2、MariaDB 驅動）不打包進 jar，
    // 由 tw.exilecore.bootstrap.ExileCoreLoader 在伺服器啟動時下載到 libraries/。
    // 程式碼直接 import 到的只有 HikariCP，所以它要宣告成 compileOnly（版本要和 ExileCoreLoader 一致）；
    // H2 與 MariaDB 驅動只用字串指定類別名稱，編譯時不需要。
    compileOnly("com.zaxxer:HikariCP:7.1.0")
    // 物品資料存成 JSON；伺服器本身就有 Gson，所以 compileOnly
    compileOnly("com.google.code.gson:gson:2.11.0")
    // 資料檔用 SnakeYAML 直接讀（不經過 Bukkit），引擎才能在單元測試裡載入真實資料；伺服器本身就有
    compileOnly("org.yaml:snakeyaml:2.6")

    // 引擎的單元測試（不需要伺服器就能跑）
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // 有些測試會碰到 Bukkit／Adventure 的類別（例如 CapturedOutputTest、PvpService）：測試時也要有 Paper API
    testImplementation("io.papermc.paper:paper-api:26.2.build.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.yaml:snakeyaml:2.6")
    // 資料庫的單元測試用記憶體 H2 跑真正的遷移與 SQL（版本與 ExileCoreLoader 一致）
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testRuntimeOnly("com.h2database:h2:2.4.240")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }

    processResources {
        // 把 build.gradle.kts 的 version 寫進 paper-plugin.yml
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
        }
    }

    jar {
        archiveBaseName.set("ExileCore")
    }

    runServer {
        // 與設計文件一致：鎖定 26.2
        minecraftVersion("26.2")

        // 第一個參數等於同意 Minecraft EULA（https://aka.ms/MinecraftEULA），
        // 這樣第一次啟動不用手動改 eula.txt。
        // 後面幾個讓伺服器的主控台輸出（與輸入）一律用 UTF-8：中文 Windows 預設是 Big5（MS950），
        // Gradle 照 UTF-8 讀就會變成「嚙踝蕭」這種亂碼。終端機也要切到 UTF-8（run-server.bat 會做）。
        jvmArgs(
            "-Dcom.mojang.eula.agree=true", "-Xms2G", "-Xmx2G",
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dstdin.encoding=UTF-8",
        )

        downloadPlugins {
            // FastAsyncWorldEdit：階段 4 的地圖模板貼上會用到，先裝好確認相容
            modrinth("fastasyncworldedit", "2.15.4")
            // MythicMobs 免費版沒有可直接下載的網址，請手動放進 run/plugins/（見 README）
        }
    }
}
