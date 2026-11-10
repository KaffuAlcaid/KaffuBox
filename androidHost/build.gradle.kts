// 构建 Android 文件授权、设备能力和隔离脚本服务适配库
plugins {
    id("com.android.library")
}

android {
    namespace = "io.kaffubox.android"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup:gifencoder:0.10.1")
}
