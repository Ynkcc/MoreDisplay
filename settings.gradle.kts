pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MoreDisplay"
include(":app")
// 屏幕可见性/录屏/无障碍的独立探测程序（普通 App，用于验收 system_server 侧策略）。
include(":probe")
// DWPC 隐藏类编译期占位（compileOnly，不打包）：RecentsGate 最近任务门禁的父类签名。
include(":hiddenapi")
