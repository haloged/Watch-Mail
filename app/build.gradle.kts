// ============================================================================
// app 模块构建脚本 —— Wear OS 邮箱客户端
// 目标设备：466x466 圆形表盘，Wear OS 3.0+（API 30+）
// ============================================================================
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.wm.wearmail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wm.wearmail"
        // Wear OS 3.0（API 30）起步：可覆盖绝大多数在售智能手表
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Microsoft (Outlook) OAuth2 客户端 ID。
        // 优先级：手表「设置 → Outlook OAuth2」里填写的值 > 这里注入的默认值。
        // 允许在 gradle.properties 里设置 wearmail.microsoftOAuthClientId 而不改代码。
        val msClientId = (project.findProperty("wearmail.microsoftOAuthClientId") as? String).orEmpty()
        buildConfigField("String", "MICROSOFT_OAUTH_CLIENT_ID", "\"$msClientId\"")
        // 无 Robolectric/Instrumentation 依赖，单元测试只覆盖纯逻辑
    }

    // 签名配置（**可选**）。
    //
    // 仓库里刻意不含任何证书（keystore/ 已被 .gitignore 排除），因此构建**不能**依赖它，
    // 否则别人克隆下来第一步就失败。取值顺序：
    //   1) gradle.properties / -P 传入 wearmail.storeFile 等（CI 推荐用环境变量注入）；
    //   2) 本地 keystore/debug.keystore（仅开发机有的自签名调试证书）；
    //   3) 两者都不存在 → 不配置签名：debug 走 AGP 默认调试证书，release 产出未签名包
    //      （构建仍然成功，只是不能用它直接分发）。
    val storeFilePath = (project.findProperty("wearmail.storeFile") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "keystore/debug.keystore"
    val resolvedStoreFile = rootProject.file(storeFilePath)
    val hasLocalKeystore = resolvedStoreFile.exists()
    if (!hasLocalKeystore) {
        logger.lifecycle(
            "[WearMail] 未找到签名证书（$storeFilePath）：debug 将使用 AGP 默认调试证书，" +
                "release 产出未签名包。需要签名时请用 -Pwearmail.storeFile=... 指定。",
        )
    }

    signingConfigs {
        if (hasLocalKeystore) {
            create("wearMailLocal") {
                storeFile = resolvedStoreFile
                storePassword = (project.findProperty("wearmail.storePassword") as? String) ?: "android"
                keyAlias = (project.findProperty("wearmail.keyAlias") as? String) ?: "androiddebugkey"
                keyPassword = (project.findProperty("wearmail.keyPassword") as? String) ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasLocalKeystore) signingConfig = signingConfigs.getByName("wearMailLocal")
        }
        release {
            // 手表存储与内存紧张，正式包开启压缩（keep 规则见 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasLocalKeystore) signingConfig = signingConfigs.getByName("wearMailLocal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // JavaMail 与 Compose 会带来重复的 META-INF 条目，必须排除否则打包失败
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.md",
                "META-INF/NOTICE.txt",
                "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }

    lint {
        // 手表端低优先级告警不阻塞构建
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 统一开启常用实验性 API 的 opt-in，降低调用处噪音；
        // 业务代码仍建议在文件内显式标注 @OptIn 以便审查。
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.wear.compose.foundation.ExperimentalWearFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    // ---------- AndroidX 基础 ----------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // 后台同步（15~30 分钟周期，遵循系统功耗管理）
    implementation(libs.androidx.work.runtime.ktx)

    // ---------- Compose 基础 ----------
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)

    // ---------- Wear OS Material3 ----------
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)

    // ---------- 邮件协议 ----------
    // JavaMail Android 版：IMAP（收取/IDLE）与 SMTP（发送）均已实现
    implementation(libs.javamail.android.mail)
    implementation(libs.javamail.android.activation)

    // ---------- 扫码配对 ----------
    implementation(libs.zxing.core)

    // ---------- 协程 ----------
    implementation(libs.kotlinx.coroutines.android)

    // ---------- 调试工具（仅 debug 包） ----------
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)

    // ---------- 单元测试（纯 JVM，无需设备） ----------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Android 平台自带 org.json，但纯 JVM 单测里只是桩实现；
    // 引入官方实现后，OAuth2 响应解析等纯逻辑才能在 JVM 上被真实测试。
    testImplementation(libs.org.json)
}
