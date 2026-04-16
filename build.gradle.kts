import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
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
        create("IC", "2023.1")
        testFramework(TestFrameworkType.Platform)
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name = "AI Code Helper"
        version = "1.0.0"
        ideaVersion {
            sinceBuild = "231"
        }
    }
    buildSearchableOptions = false
}

tasks {
    buildPlugin {
        archiveBaseName = "aiCode-helper"
    }
}
