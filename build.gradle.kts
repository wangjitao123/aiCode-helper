import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.aicode"
version = "1.0.0"

repositories {

    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

kotlin {
    // IntelliJ IDEA 2025.1 插件以 JDK 17 为目标即可（沙箱运行在 JBR 21，向下兼容 17 字节码）。
    // 这里与本机可用的 JDK 17 对齐，避免 Gradle 触发 JDK 21 的自动下载。
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name = "AI Code Helper"
        version = "1.0.0"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "253.*"
        }
    }
    buildSearchableOptions = false
}

tasks {
    buildPlugin {
        archiveBaseName = "aiCode-helper"
    }
}
