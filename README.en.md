# OldChat With Material3

> A third-party, unofficial **OldChat** client, rewritten from scratch in **Kotlin** + **Jetpack Compose**, following **Material Design 3 (Material You)**.

[🌐 English](README.en.md) | [中文](README.md)

---

## ⚠️ Disclaimer

This is an **unofficial third-party client** for OldChat and is not affiliated with the OldChat team. Using it may violate the official terms of service — use at your own risk.

---

## ✨ Features

### Messaging

- 💬 **Direct & group chat** — text, quote replies, images, files, voice, emoji, red packets, and burn-after-reading (tap to view → countdown → report to `/direct/burn/open`)
- 📡 **Dual-channel delivery** — WebSocket push as the primary path, with an **incremental cursor** fallback (`/updates/difference`) when disconnected; no fixed-interval full refreshes
- 🔔 **System notifications** — new-message notifications, suppressed for the chat you are currently viewing, honouring the sound/vibration toggles
- ✅ **Receipts** — delivered/read ticks, refreshed by events with a 3-second debounce (no polling)
- ⌨️ **Typing indicator** — throttled `typing` reports (2.5s); the title shows "typing…" for the peer

### Encrypted call (end-to-end)

Tap **Encrypted call** in a direct chat to establish a **genuine end-to-end encrypted session** (not merely transport encryption):

- 🤝 **PQC handshake** — **ML-KEM-768** (FIPS 203, BouncyCastle) preferred; falls back to **ECDH P-256 + SHA-256** when unavailable, keeping the frame format identical
- 🔐 **AES-256-GCM** — payload `nonce(12) ‖ ciphertext ‖ tag(16)`, frame prefixes `PQC_BEGIN` / `PQC_REPLY` / `ENC`
- 🔒 **In-call text is encrypted too** — sent through the **same** `POST /v1/direct/send`, only the body becomes an `ENC` frame; the peer decrypts it and renders an ordinary bubble
- 🧾 **Verifiable** — the call screen shows the algorithm, the **shared-key fingerprint** (identical on both ends once the handshake succeeds), key origin, and this session's role (initiator/responder)
- 🛡️ **Key persistence** — one 32-byte shared key per peer, reusable across calls
- ⏱️ **Keep-alive & timeouts** — 15s encrypted heartbeat, 45s no-frame disconnection, 30s handshake timeout; hanging up requires confirmation and both sides end symmetrically
- 🎙️ **No audio pipeline** — this version is an encrypted *session channel*; it does not pretend to carry voice (there are deliberately no mic/speaker buttons)

> Protocol details and a point-by-point comparison with the reference implementation (enigmaj): [`docs/ENCRYPTED-CALL.md`](docs/ENCRYPTED-CALL.md).

### Transport encryption

Session encryption between client and server: **ECDH (secp256r1) → SHA-256 derivation → AES-256-CBC + HMAC-SHA256** (envelope `{iv, data, mac}`, headers `X-Enc` / `X-Session`).

> Note: this is **transport-layer** encryption — the server can see message contents. The **only true end-to-end path is the encrypted call**.

### Others

- 🎨 **Material You** — dynamic color (can be disabled), dark/light theme, DPI scaling
- 🕐 **Check-in wall** — daily check-in, likes and comments
- 🎵 **Music square** — upload, playback (Media3), download (scoped storage aware), likes
- 💃 **Emoji square** — browse and manage emoji packs
- 📱 **CIP mini-apps** — LuaJ sandbox running `main.lua`: `ui.*` page descriptors + `app.*` capabilities (persistent storage, same-server/external `http_get`, JSON encode/decode, `delay` ≤60s, `on_click` bridge); `io`/`os`/`debug` are not loaded
- 🧠 **VibeCoding** — AI assistant backed by OldChat AI or a custom OpenAI-compatible endpoint
- ⚖️ **Public court** — community reports and reviews

---

## 🏗️ Tech stack

| Area | Technology |
|---|---|
| Language | Kotlin (100%, 78 source files, ~24k lines) |
| UI | Jetpack Compose + Material 3 (Compose BOM 2026.01.01) |
| Build | AGP 9.0.0 / Kotlin 2.3.10 / Gradle 9.1.0 / Version Catalog |
| SDK | minSdk 24, compileSdk & targetSdk 35 |
| Networking | Ktor Client 2.3.7 + OkHttp (WebSocket) |
| Serialization | Gson + kotlinx.serialization |
| Transport crypto | ECDH (secp256r1) + AES-256-CBC + HMAC-SHA256 |
| End-to-end crypto | ML-KEM-768 (BouncyCastle 1.78.1) / ECDH P-256 + AES-256-GCM |
| Local storage | SharedPreferences + JSON caches (DataStore Preferences for settings) |
| Images | Coil 2.5 (with a candidate-route fallback interceptor) |
| Media | Media3 ExoPlayer 1.2.0 |
| Mini-apps | LuaJ 3.0.1 |

---

## 📁 Project layout

```
app/src/main/java/com/oldchat/material/
├── core/
│   ├── network/     ApiClient / WebSocketManager / MessageReceiver / HttpClientProvider
│   ├── auth/        AuthManager (single-flight token refresh, cache cleanup on account switch)
│   ├── crypto/      ECDH / AES-CBC / HMAC (transport envelope)
│   ├── e2e/         E2eCrypto (frames + KEM + AEAD) / E2eKeyStore / EncryptedCallManager
│   ├── notify/      NotificationHelper (channels, foreground-chat suppression)
│   ├── model/       Message / GroupMessage / User / Group (Gson entities + payload v2)
│   ├── cache/       friends / groups / recent chats / message history / page cache (with caps)
│   └── media/       streaming uploads / voice recording / candidate interceptor
├── feature/
│   ├── auth/        login (privacy-policy dialog included)
│   ├── home/        chat list / contacts / notification center
│   ├── chat/        direct & group chat / red packets / burn-after-reading / call UI
│   ├── discover/    check-in / music / emoji / CIP / VibeCoding / court / discover settings
│   └── settings/    server address / notifications / appearance / cache
├── service/         foreground service (message keep-alive)
├── ui/              theme / shared components / app shell
├── MainActivity.kt
└── OldChatApplication.kt
```

More in `docs/`: [encrypted call](docs/ENCRYPTED-CALL.md), [CI compile check](docs/CI-COMPILE-CHECK.md), [remaining refactor plan](docs/REMAINING-BUG24.md).

---

## 🛠️ Requirements

- **JDK 17+** (CI uses Temurin 21)
- **Android SDK** (compileSdk 35, build-tools 35.0.0)
- Gradle is provided by the wrapper (9.1.0) — no local install needed

## 🚀 Build

```bash
./gradlew assembleDebug            # Debug (debug-signed, installable)
./gradlew :app:compileDebugKotlin  # Kotlin type check only (fastest)
./gradlew assembleRelease          # Release (R8; unsigned unless signing is configured)
```

Outputs:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

### Building in CI

`.github/workflows/compile-check.yml` runs `:app:compileDebugKotlin` → `:app:assembleDebug` → `:app:assembleRelease` and uploads the APKs as the `apks` artifact.

This pipeline exists because some environments cannot run a JVM at all (e.g. iSH on iOS: its user-space syscall layer lacks `getcpu`, so HotSpot aborts during VM initialization with `ENOSYS`, taking Gradle / AGP / kotlinc / sdkmanager down with it) — see [`docs/CI-COMPILE-CHECK.md`](docs/CI-COMPILE-CHECK.md).

## 🔑 Release signing

Put your key material in `keystore.properties` at the project root (git-ignored):

```properties
storeFile=release.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

- If the file exists, `assembleRelease` signs with it;
- If not, the release stays **unsigned** and the build log says so (it never silently produces an uninstallable APK).

> ⚠️ Never commit `.jks` / `.keystore` / `keystore.properties`.

---

## 🐛 Known limitations

- **BUG-24 refactor pending**: the direct-chat and group-chat ViewModels still share a lot of duplicated code — see [`docs/REMAINING-BUG24.md`](docs/REMAINING-BUG24.md)
- **No audio in the encrypted call**: it establishes and maintains an encrypted session channel (handshake / heartbeat / hang-up) only
- **Encryption scope**: only **text** messages (quotes included) are encrypted in a call; images / files / voice / emoji / red packets use their own endpoints
- **Transport envelope not wired**: business requests go over plain `/v1` HTTP (content protection relies on the payload layer described above)
- **Notification tap** only opens the app; no per-chat deep link yet
- Some endpoints (`/favorites`, `/direct/burn/open`, `before_msg_id` pagination) have not been verified against a live server

---

## 🤝 Contributing

- **Bug reports** → [GitHub Issues](https://github.com/OutoriNemuri/OldChat-With-Material3/issues)
- **Code** → please fork the repo and open a Pull Request

## 📄 License

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

## 🙏 Credits

Thanks to the OldChat team and all contributors. This is a third-party reimplementation, intended for learning and exchange.
