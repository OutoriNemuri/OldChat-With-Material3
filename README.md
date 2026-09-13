# OldChat With Material3

> **OldChat** 即时通讯软件的第三方客户端，使用 **Kotlin** + **Jetpack Compose** 从零重写，采用 **Material Design 3 (Material You)** 设计规范。

[🌐 English](README.en.md) | [中文](README.md)

## ⚠️ 免责声明

本项目是 OldChat 的**第三方非官方客户端**，与 OldChat 官方团队无关。使用本客户端可能违反官方服务条款，请自行承担风险。

## ✨ 特性

- 🎨 **Material You** —— 基于 Material Design 3 的动态取色与自适应外观
- 💬 **即时消息** —— 私聊、群聊、阅后即焚、红包等完整消息能力
- 🔒 **传输层加密** —— 与服务器之间的 ECDH(secp256r1) + AES-256-CBC + HMAC-SHA256 会话协议
  （注意：这是**传输层**加密，不是端到端加密；消息在服务端是可见的。客户端侧的
  E2E 消息层（ML-KEM + AES-GCM）尚未实现。）
- 📡 **双通道消息接收** —— WebSocket 实时推送 + HTTP 轮询兜底（5s 间隔）
- 🕐 **打卡墙** —— 每日签到、Like/评论互动
- 🎵 **音乐广场** —— 音乐上传、播放、下载、点赞
- 💃 **表情广场** —— 表情包浏览与管理
- 📱 **CIP 小程序** —— 基于 LuaJ 沙箱的轻量小程序运行环境
- 🧠 **VibeCoding** —— OldChat AI / 自定义 OpenAI 兼容接口驱动的 AI 编程助手
- ⚖️ **公开法庭** —— 社区举报与评议

## 🏗️ 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 100% |
| UI | Jetpack Compose + Material 3 |
| 网络 | Ktor Client + OkHttp（WebSocket） |
| 序列化 | Gson + kotlinx.serialization |
| 加密 | 传输层：ECDH (secp256r1) + AES-256-CBC + HMAC-SHA256（非端到端）|
| 本地存储 | DataStore Preferences |
| 图片加载 | Coil |
| 音视频 | Media3 (ExoPlayer) |
| 小程序 | LuaJ (Lua 沙箱) |
| 构建 | Gradle Version Catalog |

## 📁 项目结构

```
app/src/main/java/com/oldchat/material/
├── core/
│   ├── network/        # ApiClient / WebSocketManager / MessageReceiver
│   ├── auth/           # 认证与会话管理
│   ├── crypto/         # ECDH / AES / HMAC 加密工具
│   ├── model/          # 数据模型（Gson 实体）
│   ├── cache/          # 本地缓存（好友/群/聊天记录等）
│   └── media/          # 媒体上传
├── feature/
│   ├── auth/           # 登录 / 注册
│   ├── home/           # 会话列表 / 通知
│   ├── chat/           # 私聊 / 群聊 / 红包
│   ├── discover/       # 打卡墙 / 音乐广场 / 表情 / CIP / VibeCoding / 法庭
│   ├── cip/            # CIP 小程序
│   └── settings/       # 设置 / 反馈
├── service/            # 前台服务（消息保活）
├── ui/                 # 主题 / 通用组件
├── MainActivity.kt
└── OldChatApplication.kt
```

## 🛠️ 环境要求

- **JDK 17+**
- **Android SDK**（compileSdk 35）
- **Gradle**（已附带 Wrapper）

## 🚀 构建

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需先配置签名，见下文）
./gradlew assembleRelease
```

生成的 APK 位于：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 🔑 Release 签名

Release 构建需要配置签名密钥。在 `keystore.properties` 中填入密钥信息（该文件已被 `.gitignore` 排除，不会入库）：

```properties
storeFile=../release/your-key.jks
storePassword=你的store密码
keyAlias=你的key别名
keyPassword=你的key密码
```

然后在 `app/build.gradle.kts` 中取消 `signingConfig` 的注释即可。

> ⚠️ **切勿**将 `.jks` / `.keystore` 文件提交到仓库，密钥泄露会导致无法给后续版本升级签名。

## 📦 依赖管理

项目使用 **Gradle Version Catalog** 统一管理依赖，定义在 `gradle/libs.versions.toml`。

添加新依赖：

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

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。

- **反馈问题** → [GitHub Issues](https://github.com/OutoriNemuri/OldChat-With-Material3/issues)
- **提交代码** → 请先 Fork 再发起 Pull Request

## 📄 许可证

本项目基于 **GNU General Public License v3.0** 开源，详见 [LICENSE](LICENSE)。

## 🙏 致谢

- [OldChat-For-Windows](https://github.com/Coloryi-MIAO/OldChat-For-Windows)（MIT License）—— WebSocket 加密会话协议参考实现