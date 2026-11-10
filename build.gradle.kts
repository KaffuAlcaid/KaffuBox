// 固定各模块共用的 Kotlin、序列化与 Android 构建插件版本
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
}

allprojects {
    group = "io.kaffubox"
    version = "0.1.0"
}
