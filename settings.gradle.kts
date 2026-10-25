// ============================================================================
// WearMail —— 智能手表邮箱客户端
// Gradle 设置脚本：声明插件仓库、依赖仓库与包含的模块
// ============================================================================

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

dependencyResolutionManagement {
    // 统一在此处声明仓库，禁止子模块自行添加，保证依赖来源一致
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WearMail"

// 应用模块：
//   :app    —— 手表端（Wear OS 独立应用）
//   :phone  —— 手机配套应用（扫码同步手表配置；只收信、只发手机通知，不提供查看邮件）
include(":app")
include(":phone")
