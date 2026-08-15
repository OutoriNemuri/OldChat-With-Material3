# OldChat With Material3

A modern Android client for the OldChat messenger, rewritten in **Kotlin** with **Jetpack Compose** and **Material Design 3**.

## 🚀 Features

✅ **Jetpack Compose** — modern declarative UI framework
✅ **Material Design 3** — latest design guidelines (Material You)
✅ **Kotlin** — 100% Kotlin
✅ **Gradle Version Catalog** — unified dependency management
✅ **Ready to run** — complete project structure included

## 📁 Project Structure

```
android-project/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/oldchat/material/
│   │   │   │   ├── MainActivity.kt          # Main activity
│   │   │   │   └── ui/theme/
│   │   │   │       ├── Color.kt             # Color definitions
│   │   │   │       ├── Theme.kt             # Theme configuration
│   │   │   │       └── Type.kt              # Typography configuration
│   │   │   ├── res/                         # Resources
│   │   │   └── AndroidManifest.xml          # App manifest
│   │   ├── androidTest/                     # Instrumented tests
│   │   └── test/                            # Unit tests
│   ├── build.gradle.kts                     # App module configuration
│   └── proguard-rules.pro                   # ProGuard rules
├── gradle/
│   ├── libs.versions.toml                   # Dependency version management
│   └── wrapper/                             # Gradle Wrapper
├── build.gradle.kts                         # Project-level configuration
├── settings.gradle.kts                      # Project settings
├── gradle.properties                        # Gradle properties
├── gradlew / gradlew.bat                    # Gradle commands
└── .gitignore                               # Git ignore rules
```

## 🛠️ Getting Started

### 1. Requirements
- ✅ **JDK 17+** (required)
- ✅ **Gradle** (Wrapper is included)
- ✅ **Android SDK** (optional, for full builds)

### 2. Build the Project

#### Command line
```bash
# Linux/macOS
./gradlew build              # Build the project
./gradlew assembleDebug      # Build the debug APK
./gradlew installDebug       # Install to a device
./gradlew clean              # Clean the build

# Windows
gradlew.bat build
gradlew.bat assembleDebug
```

### 3. Output APK location
```
app/build/outputs/apk/debug/app-debug.apk
```

## 📦 Dependency Management

This project uses the **Gradle Version Catalog** for unified dependency management.

### Viewing current dependencies
Defined in `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.0.0"
kotlin = "2.3.10"
composeBom = "2026.01.01"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
```

### Adding a new dependency
1. Add the version and library definition to `gradle/libs.versions.toml`
2. Reference it in `app/build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(libs.your.library.name)
   }
   ```

## 🎨 Customizing the App

### Changing the app name
Edit `app/src/main/res/values/strings.xml`:
```xml
<string name="app_name">Your App Name</string>
```

### Changing the package name
1. Update `namespace` and `applicationId` in `app/build.gradle.kts`
2. Rename the `java/com/oldchat/material` directory structure
3. Update package references in `AndroidManifest.xml`

### Changing theme colors
Edit `app/src/main/java/.../ui/theme/Color.kt`:
```kotlin
val Purple80 = Color(0xFFD0BCFF)  // Replace with your color
```

## 📱 Compose Example

`MainActivity.kt` includes a simple `Greeting` example:

```kotlin
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}
```

You can:
- Add more composable functions
- Use Material3 components
- Implement navigation (Navigation Compose is recommended)
- Integrate ViewModel, Repository and other architecture components

## 🔧 Common Gradle Tasks

```bash
./gradlew tasks              # List all available tasks
./gradlew clean              # Clean the build
./gradlew build              # Full build
./gradlew assembleDebug      # Build debug APK
./gradlew assembleRelease    # Build release APK
./gradlew installDebug       # Install debug build to device
./gradlew test               # Run unit tests
./gradlew connectedAndroidTest # Run instrumented tests
```

## 📝 Notes

⚠️ **About the Android SDK**
- This template can be built in Operit's Ubuntu environment
- Full compilation requires the Android SDK
- Android Studio is recommended for full development

### ⚠️ ARM64 AAPT2 replacement (built into the template)

Gradle automatically downloads AAPT2 from Google Maven, but the official
distribution does not run directly on ARM64 Linux. This template bundles an
ARM64 `aapt2`; `setup_android_env.sh` replaces it into the SDK build-tools and
Gradle caches automatically.

**Bundled source**:
- Release: https://github.com/ReVanced/aapt2/releases/tag/v1.0.0
- ARM64 aapt2: https://github.com/ReVanced/aapt2/releases/download/v1.0.0/aapt2-arm64-v8a
- SHA-256: `e5b5ff7f0d4f6ecd7fa5d05d77fed3f09f6f1bf80f078b8aada82bc578848561`

**All you need to do**
```bash
chmod +x ./setup_android_env.sh
./setup_android_env.sh
```

The script automatically:
- Replaces `$ANDROID_SDK/build-tools/35.0.0/aapt2`
- Replaces the binary inside `~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/aapt2`
- Replaces the already-extracted `aapt2` under `~/.gradle/caches/transforms-*`

⚠️ **About signing**
- Debug builds use the debug signing key automatically
- Release builds require a configured signing key

## 🌐 Resources

- [Jetpack Compose documentation](https://developer.android.com/jetpack/compose)
- [Material Design 3](https://m3.material.io/)
- [Android developer guide](https://developer.android.com/)
- [Kotlin documentation](https://kotlinlang.org/)

## 💡 Tips

- Use `./gradlew --scan` for detailed build analysis
- Use `./gradlew build --info` for verbose build logs
- Tune `gradle.properties` to adjust build performance

## 📄 License

This project is licensed under the **GNU General Public License v3.0**. See the
[LICENSE](LICENSE) file for the full text.

Happy Coding! 🤖✨
