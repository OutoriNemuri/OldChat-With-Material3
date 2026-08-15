# OldChat Material R8/ProGuard rules
# Mirrors proguard-rules.pro from §14 + R8 constraints for minSdk 24

# Keep line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson ----
# Keep all model classes and their fields
-keep class com.oldchat.material.core.model.** { *; }
-keep class com.oldchat.material.feature.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- OkHttp + Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ---- Ktor ----
-keep class io.ktor.** { *; }

# ---- SpongyCastle (if used for ECDH) ----
-keep class org.spongycastle.** { *; }

# ---- Media3 ----
-keep class androidx.media3.** { *; }

# ---- Coil ----
-keep class coil.** { *; }

# ---- Compose ----
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ---- DataStore ----
-keep class androidx.datastore.** { *; }

# ---- AndroidX Lifecycle ----
-keep class androidx.lifecycle.** { *; }

# Remove unused classes to reduce DEX size
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNullParameter(...);
}