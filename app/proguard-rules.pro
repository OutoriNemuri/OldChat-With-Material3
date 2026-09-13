# OldChat Material R8/ProGuard rules
# Mirrors proguard-rules.pro from §14 + R8 constraints for minSdk 24

# Keep line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson ----
# Keep all model classes and their fields
# BUG-25：原来这里还有 `-keep class ...feature.** { *; }`，
# 等于把整个 UI 层（工程 75% 的代码）从混淆与裁剪里摘出去，R8 形同虚设。
# UI 层不需要反射保护，只保留真正的数据模型（Gson 反射依赖字段名）。
-keep class com.oldchat.material.core.model.** { *; }
-keep class com.oldchat.material.core.network.dto.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- OkHttp + Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.ws.** { *; }
-keep class okio.** { *; }

# ---- Ktor ----
# Ktor 只需要保留序列化与引擎的可反射部分，整包 keep 会让 DEX 体积翻倍
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.client.engine.okhttp.** { *; }

# ---- SpongyCastle (if used for ECDH) ----
-keep class org.spongycastle.** { *; }

# ---- Media3 ----
-keep class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# ---- Coil ----
-keep class coil.decode.** { *; }

# ---- Compose ----
-dontwarn androidx.compose.**
-dontwarn androidx.compose.**

# ---- DataStore ----
-dontwarn androidx.datastore.**

# ---- AndroidX Lifecycle ----
-keep class androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }

# Remove unused classes to reduce DEX size
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(...);
}

# ---- slf4j 静态绑定探测（CI: :app:minifyReleaseWithR8 实测缺类） ----
# R8 会因 org.slf4j.LoggerFactory 里对 StaticLoggerBinder 的可选探测而报 Missing class。
# 这类引用在运行期是「找不到就走 NOP logger」的可选路径，声明 dontwarn 即可。
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**
