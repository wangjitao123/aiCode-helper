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
    jvmToolchain(21)
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
