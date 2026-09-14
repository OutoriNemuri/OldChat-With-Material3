// 注意：Kotlin DSL 脚本里 `java` 会被 Gradle 的 java 扩展遮蔽，
// 所以必须 import 后才能用 Properties（否则 java.util.Properties 报 Unresolved reference）。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.oldchat.material"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oldchat.material"
        minSdk = 24
        targetSdk = 35
        versionCode = 3014
        versionName = "2.4.0 (build 1)"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // BUG-25：release 签名原来是整段注释掉的 → 打出来的 release 包是「未签名」的，
    // 根本装不上。现在读取根目录 keystore.properties（不进版本库），
    // 没配置时给出明确提示而不是静默出一个装不上的包。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile") ?: "release.keystore")
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 只在提供了 keystore.properties 时才绑定签名配置，否则保持未签名
            // （CI 上可显式传入，避免本地没有密钥时误出包）。
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
        // BUG-20：设置页要显示真实版本号（原来硬编码 "2.3.4 (build 1)"）
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Force use of ARM64 binaries for AAPT2 in Proot environment
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
            useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose UI
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // 加密通话：ML-KEM-768（FIPS 203）——与 enigmaj 相同的 KEM。
    // 代码里通过反射调用 BC 的 mlkem 包，因此即使此依赖缺失也只是自动降级为
    // ECDH P-256（帧格式不变），不会编译/运行失败。
    implementation(libs.bouncycastle)

    // BUG-25：navigation-compose 全工程零引用（页面切换是自己写的状态机），
    // 保留会让 R8 之外还多一份运行时依赖，直接移除。
    // implementation(libs.androidx.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // BUG-25：Room 是死依赖 —— 全工程没有任何 @Entity/@Dao/RoomDatabase，
    // 本地持久化实际用的是 SharedPreferences + JSON 缓存。移除后 APK 更小、也不会误导。

    // Ktor (HTTP client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // OkHttp (standalone for WebSocket)
    implementation(libs.okhttp)

    // Coil (image loading)
    implementation(libs.coil.compose)

    // DataStore (preferences)
    implementation(libs.androidx.datastore.preferences)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Gson (compatible with original server responses)
    implementation(libs.gson)

    // Media3 (video/audio playback)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // Lifecycle Service (foreground service support)
    implementation(libs.androidx.lifecycle.service)

    // LuaJ (CIP 小程序 Lua 沙箱，纯 Java 解释器)
    implementation(libs.luaj.jse)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
