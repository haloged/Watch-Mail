// ============================================================================
// phone 模块构建脚本 —— WearMail 手机配套应用
//
// 定位：手表端通知被系统限制（OPPO 等）时的替代通道 ——
// 扫码同步手表上的账户配置 → 手机收信 → 发手机通知（手表自动同步手机通知）。
// 明确边界：**不提供任何查看邮件的界面**（无邮件列表/详情），只做同步与通知。
// ============================================================================
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 与手表端**同一 namespace**：共享代码（mail/model/crypto/Logs）原样复用、
    // 零改动即可编译（R 与 BuildConfig 都落在 com.wm.wearmail 包下，各模块独立生成）。
    // 两个 APK 的 applicationId 不同，互不覆盖。
    namespace = "com.wm.wearmail"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wm.wearmail.phone"
        // 手机端与手表端保持一致的最低版本：Android 12+
        minSdk = 30
        targetSdk = 35
        // 与手表端版本同步（用户要求「手机应用版本和手表同步」）
        versionCode = 7
        versionName = "1.2.5"
    }

    // 签名配置（可选，与手表端同一套证书）：见 app/build.gradle.kts 的说明
    val storeFilePath = (project.findProperty("wearmail.storeFile") as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "keystore/debug.keystore"
    val resolvedStoreFile = rootProject.file(storeFilePath)
    val hasLocalKeystore = resolvedStoreFile.exists()
    if (!hasLocalKeystore) {
        logger.lifecycle(
            "[WearMail-phone] 未找到签名证书（$storeFilePath）：debug 使用 AGP 默认调试证书，" +
                "release 产出未签名包。",
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
            if (hasLocalKeystore) signingConfig = signingConfigs.getByName("wearMailLocal")
        }
        release {
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // 共享代码（core/Logs.kt）引用 BuildConfig.DEBUG；AGP 8 默认不生成，必须显式开启
        buildConfig = true
    }

    packaging {
        resources {
            // JavaMail 与 Compose / zxing 会带来重复的 META-INF 条目，必须排除否则打包失败
            // （与 app 模块保持一致）
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
}

dependencies {
    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose（手机版 Material3；手表端用 wear-compose-material3，两者互不冲突）
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)

    // 扫码（内置相机 Activity，无需自写 CameraX 预览）
    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.appcompat)

    // 收信（与手表端同一套 JavaMail 依赖与客户端代码）
    implementation(libs.javamail.android.mail)
    implementation(libs.javamail.android.activation)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
