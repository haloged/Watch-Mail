# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# ==================== Room ====================
# Keep Room entities（反射/序列化需要）
-keep class com.haloged.watchmail.data.local.entity.** { *; }
-keep class com.haloged.watchmail.data.local.dao.** { *; }
-keep class com.haloged.watchmail.data.local.WatchMailDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ==================== JavaMail（Android 移植版）====================
# 核心协议实现必须保留，否则 R8 剥离后运行时 NoClassDefFoundError
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }
-keep class jakarta.activation.** { *; }
-keep class jakarta.mail.** { *; }

# JavaMail 在 Android 上引用了桌面 JDK 类（AWT/Beans/Activation），这些在 Android 不存在
# 必须屏蔽告警，否则 release 构建失败
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-dontwarn com.sun.activation.**
-dontwarn jakarta.mail.**
-dontwarn jakarta.activation.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn java.awt.datatransfer.**
-dontwarn javax.activation.CommandInfo**
-dontwarn javax.activation.DataHandler**
-dontwarn javax.activation.DataSource**
-dontwarn javax.activation.FileDataSource**
-dontwarn javax.activation.MailcapCommandMap**
-dontwarn javax.activation.MimeType**
-dontwarn javax.activation.MimeTypeParseException**

# 保留 JavaMail 的 SPI/服务发现资源
-keep class META-INF/services.** { *; }
-keepresourcexmlelements META-INF/services/**
-keepresourcefiles META-INF/services/**
-keepresourcefiles mailcap
-keepresourcefiles **/mailcap
-keepresourcefiles **/mime.types

# ==================== Kotlin coroutines ====================
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ==================== WorkManager ====================
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
-keep class com.haloged.watchmail.service.** { *; }

# ==================== Compose ====================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ==================== Security / KeyStore ====================
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ==================== 通用 ====================
# 保留异常栈信息
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# 枚举不缩减
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
