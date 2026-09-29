// 讓 Gradle 在找不到 JDK 25 時自動下載（Temurin）。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "exilecore"
