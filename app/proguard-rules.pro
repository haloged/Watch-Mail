# ============================================================================
# R8 / ProGuard 规则
# 说明：release 构建开启代码压缩与资源压缩。以下规则保证 JavaMail 这类
#       依赖反射与服务发现（ServiceLoader）的库在压缩后仍可正常工作。
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
