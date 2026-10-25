// ============================================================================
// 根项目构建脚本
// 所有插件在此声明但不在根项目应用（apply false），由子模块按需启用。
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
