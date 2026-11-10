// 构建 Windows Compose 界面与包含 Java 运行时的桌面应用
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// 独立脚本进程使用同一套运行时的 Java 启动器，保留其内存限制和标准输入输出协议
tasks.withType<AbstractJLinkTask>().configureEach {
    freeArgs.add("--compress=1")
    freeArgs.add("--include-locales=en,zh")
    val launcher = javaHome.map { file(it).resolve("bin/java.exe") }
    inputs.file(launcher)
    doLast {
        launcher.get().copyTo(destinationDir.get().file("bin/java.exe").asFile, overwrite = true)
    }
}

val iconSources by configurations.creating
val desktopSources = files(fileTree("src/main/kotlin") { include("**/*.kt") }, fileTree("../uiShared/src/main/kotlin") { include("**/*.kt") })
val usedIconNames = providers.provider {
    desktopSources.files.flatMap { source -> Regex("Icons\\.Outlined\\.([A-Za-z0-9_]+)").findAll(source.readText()).map { it.groupValues[1] }.toList() }.toSet()
}
val packageUsedIcons by tasks.registering(Jar::class) {
    archiveFileName.set("KaffuIcons.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/Icons"))
    inputs.files(desktopSources)
    from({ zipTree(iconSources.singleFile) }) {
        include { entry -> entry.isDirectory || entry.path.startsWith("META-INF/") || usedIconNames.get().any { entry.path == "androidx/compose/material/icons/outlined/" + it + "Kt.class" } }
    }
}

kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir("../uiShared/src/main/kotlin") }
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(project(":desktopHost"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    compileOnly("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    iconSources("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3") { isTransitive = false }
    runtimeOnly(files(packageUsedIcons))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}

compose.desktop {
    application {
        mainClass = "io.kaffubox.app.DesktopAppKt"
        javaHome = providers.gradleProperty("org.gradle.java.home").orElse(System.getProperty("java.home")).get()
        jvmArgs += listOf("-Xmx1024m", "-Dfile.encoding=UTF-8")
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from("ProguardRules.pro")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "KaffuBox"
            packageVersion = "0.1.0"
            description = "KaffuBox"
            modules("java.desktop", "java.logging", "jdk.dynalink", "jdk.crypto.ec", "jdk.unsupported", "jdk.charsets", "jdk.localedata")
            windows {
                dirChooser = true
                perUserInstall = true
                menuGroup = "KaffuBox"
                shortcut = true
            }
        }
    }
}
