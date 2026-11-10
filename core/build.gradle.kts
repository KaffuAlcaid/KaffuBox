// 编译 Windows 和 Android 共用的工具、任务、文件与插件核心
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    android {
        namespace = "io.kaffubox.core"
        compileSdk = 36
        minSdk = 26
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.mozilla:rhino:1.9.2")
                implementation("com.google.zxing:core:3.5.4")
                implementation("org.jsoup:jsoup:1.22.1")
                implementation("org.commonmark:commonmark:0.27.1")
                implementation("org.yaml:snakeyaml:2.5")
                implementation("org.tomlj:tomlj:1.1.1")
                implementation("com.drewnoakes:metadata-extractor:2.19.0")
                implementation("ch.obermuhlner:big-math:2.3.2")
            }
        }
        getByName("desktopMain") { dependsOn(jvmSharedMain) }
        getByName("androidMain") { dependsOn(jvmSharedMain) }
    }
}
