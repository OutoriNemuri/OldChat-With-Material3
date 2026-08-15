# OldChat With Material3

> A third-party client for the **OldChat** instant messenger, rewritten from scratch in **Kotlin** + **Jetpack Compose** with **Material Design 3 (Material You)**.

[🌐 English](README.en.md) | [中文](README.md)

## ⚠️ Disclaimer

This project is a **third-party, unofficial client** for OldChat and is not affiliated with the official OldChat team. Using this client may violate the official Terms of Service; use it at your own risk.

## ✨ Features

- 🎨 **Material You** — dynamic color and adaptive theming based on Material Design 3
- 💬 **Instant messaging** — direct messages, group chats, self-destructing messages, red packets, and more
- 🔒 **End-to-end encryption** — ECDH + AES-CBC + HMAC encrypted session protocol for WebSocket real-time messages
- 📡 **Dual-channel message reception** — WebSocket real-time push + HTTP polling fallback (5s interval)
- 🕐 **Check-in wall** — daily check-ins with like/comment interactions
- 🎵 **Music plaza** — upload, play, download, and like music
- 💃 **Emoji plaza** — browse and manage stickers
- 📱 **CIP mini-apps** — a lightweight mini-app runtime built on the LuaJ sandbox
- 🧠 **VibeCoding** — an AI coding assistant powered by OldChat AI or a custom OpenAI-compatible API
- ⚖️ **Public court** — community reporting and review

## 🏗️ Tech Stack

| Category | Technology |
|----------|------------|
| Language | Kotlin 100% |
| UI | Jetpack Compose + Material 3 |
| Networking | Ktor Client + OkHttp (WebSocket) |
| Serialization | Gson + kotlinx.serialization |
| Cryptography | ECDH (secp256r1) + AES-256-CBC + HMAC-SHA256 |
| Local storage | DataStore Preferences |
| Image loading | Coil |
| Audio/Video | Media3 (ExoPlayer) |
| Mini-apps | LuaJ (Lua sandbox) |
| Build | Gradle Version Catalog |

## 📁 Project Structure

```
app/src/main/java/com/oldchat/material/
├── core/
│   ├── network/        # ApiClient / WebSocketManager / MessageReceiver
│   ├── auth/           # Authentication & session management
│   ├── crypto/         # ECDH / AES / HMAC crypto utilities
│   ├── model/          # Data models (Gson entities)
│   ├── cache/          # Local caches (friends / groups / chat history, etc.)
│   └── media/          # Media upload
├── feature/
│   ├── auth/           # Login / registration
│   ├── home/           # Conversation list / notifications
│   ├── chat/           # Direct / group chat / red packets
│   ├── discover/       # Check-in wall / music plaza / emoji / CIP / VibeCoding / court
│   ├── cip/            # CIP mini-apps
│   └── settings/       # Settings / feedback
├── service/            # Foreground service (message keep-alive)
├── ui/                 # Theme / shared components
├── MainActivity.kt
└── OldChatApplication.kt
```

## 🛠️ Requirements

- **JDK 17+**
- **Android SDK** (compileSdk 35)
- **Gradle** (Wrapper included)

## 🚀 Build

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration, see below)
./gradlew assembleRelease
```

Output APKs are located at:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 🔑 Release Signing

Release builds require a signing key. Fill in your key info in `keystore.properties` (this file is excluded by `.gitignore` and never committed):

```properties
storeFile=../release/your-key.jks
storePassword=yourStorePassword
keyAlias=yourKeyAlias
keyPassword=yourKeyPassword
```

Then uncomment the `signingConfig` line in `app/build.gradle.kts`.

> ⚠️ **Never** commit `.jks` / `.keystore` files to the repository — leaking your key would prevent you from signing future version upgrades.

## 📦 Dependency Management

This project uses the **Gradle Version Catalog**, defined in `gradle/libs.versions.toml`.

To add a new dependency:

```toml
# libs.versions.toml
[libraries]
your-lib = { group = "com.example", name = "your-lib", version = "1.0.0" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.your.lib)
}
```

## 🤝 Contributing

Issues and pull requests are welcome.

- **Report bugs** → [GitHub Issues](https://github.com/OutoriNemuri/OldChat-With-Material3/issues)
- **Submit code** → fork the repo and open a pull request

## 📄 License

This project is open-sourced under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

## 🙏 Acknowledgments

- [OldChat-For-Windows](https://github.com/Coloryi-MIAO/OldChat-For-Windows) (MIT License) — reference implementation for the WebSocket encrypted session protocol