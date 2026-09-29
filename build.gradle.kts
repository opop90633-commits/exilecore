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
}

dependencies {
    // Paper 26.2 API。伺服器本身提供，所以是 compileOnly
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.yaml:snakeyaml:2.6")
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
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Xms2G", "-Xmx2G")

        downloadPlugins {
            // FastAsyncWorldEdit：階段 4 的地圖模板貼上會用到，先裝好確認相容
            modrinth("fastasyncworldedit", "2.15.4")
            // MythicMobs 免費版沒有可直接下載的網址，請手動放進 run/plugins/（見 README）
        }
    }
}
