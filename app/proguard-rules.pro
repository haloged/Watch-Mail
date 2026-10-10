# ============================================================================
# R8 / ProGuard 规则
# 说明：release 构建开启代码压缩与资源压缩（isMinifyEnabled / isShrinkResources），
#       默认规则使用 proguard-android-optimize.txt —— 即 R8 不仅做收缩与混淆，
#       还会执行类合并、内联等**优化**（该文件含 -allowaccessmodification 等开关）。
#       以下规则保证 JavaMail 这类依赖反射与服务发现（ServiceLoader）的库
#       在压缩与优化后仍可正常工作。
#
# 约束（防回归）：本文件及构建脚本中不得添加 -dontoptimize / -dontobfuscate，
#       也不得把 optimize 版默认文件换成 proguard-android.txt（那会关闭优化）。
#       tools/static_checks.py 的「release 启用 R8 优化」检查会对此持续校验。
# ============================================================================

# ---------- JavaMail / Activation（IMAP、SMTP 实现依赖反射加载 Provider）----------
-keep class javax.mail.** { *; }
-keep class javax.mail.internet.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.mail.imap.** { *; }
-keep class com.sun.mail.smtp.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-keep class com.sun.mail.util.** { *; }

# ServiceLoader 需要保留 provider 配置文件与无参构造
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepnames class * implements javax.mail.Provider
-keepnames class * implements javax.activation.DataContentHandler
-keep class * implements javax.mail.Provider { public <init>(); }
-keep class * implements javax.activation.DataContentHandler { public <init>(); }

# JavaMail 在 Android 上会引用部分桌面 JDK 类，需忽略缺失告警
-dontwarn java.awt.**
-dontwarn javax.security.**
-dontwarn javax.naming.**
-dontwarn org.apache.**
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# ---------- 数据模型 ----------
# 模型类虽然不直接反射，但保留字段名便于问题定位与后续 JSON 序列化扩展
-keep class com.wm.wearmail.model.** { *; }

# ---------- Room 之外的本地存储 ----------
# 项目使用 SQLiteOpenHelper，无注解处理产物，无需额外规则。

# ---------- 协程 ----------
-dontwarn kotlinx.coroutines.**
