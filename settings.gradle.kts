pluginManagement {
    includeBuild("build-logic")

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
        maven("https://repo.itextsupport.com/android")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
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
        maven("https://jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "rikkahub"
include(":app")
include(":highlight")
include(":ai")
include(":search")
include(":speech")
include(":common")
include(":document")
include(":web")
include(":material3")
include(":workspace")
include(":androidvm")
include(":app:baselineprofile")
include(":videogen")

// ===== 仿光速虚拟机：黑盒 BlackBox 引擎接入 =====
// 单开关：在 gradle.properties 设置 blackbox.enable=true（或构建时加 -Pblackbox.enable=true）即自动接入。
// 入库路径 third_party/BlackBox（submodule）。已预置：
//   - Bcore/build.gradle 的 compileSdk 已对齐 35；jitpack 仓库已存在（free_reflection 可解析）。
// 仍需在真机/本地环境完成：安装 NDK（Bcore 用 ndkBuild 编译原生 Hook 代码）。
val blackboxEnabled = providers.gradleProperty("blackbox.enable").getOrElse("false").toBoolean()
if (blackboxEnabled) {
    include(":Bcore")
    include(":Bcore:xposed")
    project(":Bcore").projectDir = file("third_party/BlackBox/Bcore")
    project(":Bcore:xposed").projectDir = file("third_party/BlackBox/Bcore/xposed")
}
include(":oauth")
