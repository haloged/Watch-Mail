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

// 唯一应用模块（手表端单模块应用，避免不必要的模块拆分开销）
include(":app")
