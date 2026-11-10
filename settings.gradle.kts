// 配置 KaffuBox 的依赖仓库、共用核心和各平台宿主及桌面界面模块
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KaffuBox"
include(":core", ":desktopHost", ":androidHost", ":desktopApp", ":androidApp")
