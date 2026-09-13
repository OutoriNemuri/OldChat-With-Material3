# OldChat With Material3

> **OldChat** 的第三方非官方客户端，使用 **Kotlin** + **Jetpack Compose** 从零重写，遵循 **Material Design 3 (Material You)**。

[🌐 English](README.en.md) | [中文](README.md)

---

## ⚠️ 免责声明

本项目是 OldChat 的**第三方非官方客户端**，与 OldChat 官方团队无关。使用本客户端可能违反官方服务条款，请自行承担风险。

---

## ✨ 特性

### 消息

- 💬 **私聊 / 群聊** —— 文本、引用回复、图片、文件、语音、表情、红包、阅后即焚（点击查看 → 倒计时销毁 → 上报 `/direct/burn/open`）
- 📡 **双通道接收** —— WebSocket 实时推送为主，断线时走**增量游标**（`/updates/difference`）兜底；不做固定间隔全量刷新
- 🔔 **系统通知** —— 新消息通知、按会话抑制（正在看该会话时不打扰）、声音/震动开关联动
- ✅ **回执** —— 送达/已读对号，事件驱动刷新 + 3 秒防抖（不做轮询）
- ⌨️ **输入中** —— `typing` 上报节流 2.5s，对端输入时标题显示「正在输入…」

### 加密通话（端到端）

单聊里点「加密通话」即可与对端建立**真正的端到端加密会话**（不是传输层加密）：

- 🤝 **PQC 握手** —— 优先 **ML-KEM-768**（FIPS 203，BouncyCastle）；依赖不可用时自动降级 **ECDH P-256 + SHA-256**，帧格式不变
- 🔐 **AES-256-GCM** —— 载荷 `nonce(12) ‖ ciphertext ‖ tag(16)`，帧前缀 `PQC_BEGIN` / `PQC_REPLY` / `ENC`
- 🔒 **通话内文本消息也加密** —— 与普通消息走**同一个** `POST /v1/direct/send`，只是 body 换成 `ENC` 帧；对方解出明文后照常渲染成气泡
- 🧾 **可验证** —— 通话页展示算法、**共享密钥指纹**（两端一致即握手成功）、密钥来源、本次角色（发起方/响应方）
- 🛡️ **密钥持久化** —— 每对端一条 32 字节共享密钥，可跨通话复用
- ⏱️ **保活与超时** —— 15s 加密心跳、45s 无帧判掉线、30s 握手超时；挂断需二次确认，双方对称退出
- 🎙️ **不含音频管线** —— 本版是"加密会话通道"，不假装在传语音（界面上也没有麦克风/扬声器按钮）

> 原理、帧格式与和参考实现（enigmaj）的逐项对照见 [`docs/ENCRYPTED-CALL.md`](docs/ENCRYPTED-CALL.md)。

### 传输层加密

客户端与服务器之间的会话加密：**ECDH (secp256r1) → SHA-256 派生 → AES-256-CBC + HMAC-SHA256**（信封 `{iv, data, mac}`，请求头 `X-Enc` / `X-Session`）。

> 注意：这是**传输层**加密，消息在服务端可见；**真正的端到端加密只有「加密通话」这条链路**。

### 其它

- 🎨 **Material You** —— 动态取色（可关）、深浅色、DPI 缩放
- 🕐 **打卡墙** —— 每日签到、Like / 评论互动
- 🎵 **音乐广场** —— 上传、播放（Media3）、下载（分区存储适配）、点赞
- 💃 **表情广场** —— 表情包浏览与管理
- 📱 **CIP 小程序** —— LuaJ 沙箱运行 `main.lua`：`ui.*` 页面描述 + `app.*` 能力（storage 落盘、http_get 同服务器/外网、json 编解码、delay ≤60s、on_click 桥接），不加载 io/os/debug
- 🧠 **VibeCoding** —— OldChat AI / 自定义 OpenAI 兼容接口的 AI 助手
- ⚖️ **公开法庭** —— 社区举报与评议

---

## 🏗️ 技术栈

| 类别 | 技术 |
|---|---|
| 语言 | Kotlin（100%，78 个源文件 / 约 2.4 万行） |
| UI | Jetpack Compose + Material 3（Compose BOM 2026.01.01） |
| 构建 | AGP 9.0.0 / Kotlin 2.3.10 / Gradle 9.1.0 / Version Catalog |
| SDK | minSdk 24，compileSdk & targetSdk 35 |
| 网络 | Ktor Client 2.3.7 + OkHttp（WebSocket） |
| 序列化 | Gson + kotlinx.serialization |
| 传输加密 | ECDH (secp256r1) + AES-256-CBC + HMAC-SHA256 |
| 端到端加密 | ML-KEM-768（BouncyCastle 1.78.1）/ ECDH P-256 + AES-256-GCM |
| 本地存储 | SharedPreferences + JSON 缓存（DataStore Preferences 用于设置项） |
| 图片 | Coil 2.5（含候选线路回退拦截器） |
| 音视频 | Media3 ExoPlayer 1.2.0 |
| 小程序 | LuaJ 3.0.1 |

---

## 📁 项目结构

```
app/src/main/java/com/oldchat/material/
├── core/
│   ├── network/     ApiClient / WebSocketManager / MessageReceiver（增量兜底轮询）/ HttpClientProvider
│   ├── auth/        AuthManager（令牌刷新单飞 + 账号切换清理）
│   ├── crypto/      ECDH / AES-CBC / HMAC（传输层信封）
│   ├── e2e/         E2eCrypto（帧 + KEM + AEAD）/ E2eKeyStore / EncryptedCallManager（加密通话状态机）
│   ├── notify/      NotificationHelper（通知通道 / 前台会话抑制）
│   ├── model/       Message / GroupMessage / User / Group（Gson 实体 + payload v2）
│   ├── cache/       好友 / 群 / 会话 / 消息历史 / 页面缓存（含容量上限）
│   └── media/       上传（流式）/ 语音录制 / 候选线路拦截器
├── feature/
│   ├── auth/        登录（含隐私协议弹窗）
│   ├── home/        会话列表 / 联系人 / 通知中心
│   ├── chat/        单聊 / 群聊 / 红包 / 阅后即焚 / 加密通话界面
│   ├── discover/    打卡墙 / 音乐 / 表情 / CIP / VibeCoding / 法庭 / 发现设置
│   └── settings/    设置（服务器地址 / 通知 / 外观 / 缓存）
├── service/         前台服务（消息保活）
├── ui/              主题 / 通用组件 / 主框架
├── MainActivity.kt
└── OldChatApplication.kt
```

`docs/` 下另有：[加密通话说明](docs/ENCRYPTED-CALL.md)、[CI 编译验证](docs/CI-COMPILE-CHECK.md)、[遗留重构计划](docs/REMAINING-BUG24.md)。

---

## 🛠️ 环境要求

- **JDK 17+**（CI 使用 Temurin 21）
- **Android SDK**（compileSdk 35，build-tools 35.0.0）
- Gradle 由 Wrapper 提供（9.1.0），无需本机安装

## 🚀 构建

```bash
./gradlew assembleDebug            # Debug（debug 签名，可直接安装）
./gradlew :app:compileDebugKotlin  # 只做 Kotlin 类型检查（最快）
./gradlew assembleRelease          # Release（R8 混淆；未签名，除非配置了签名）
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

### 在 CI 上构建

`.github/workflows/compile-check.yml` 会依次执行 `:app:compileDebugKotlin` → `:app:assembleDebug` → `:app:assembleRelease`，并把 APK 作为产物（`apks`）上传。

这条流水线最初是为了应付**无法在本地跑 JVM 的环境**（例如 iOS 上的 iSH：其用户态 syscall 层没有实现 `getcpu`，HotSpot 在 VM 初始化阶段即 `ENOSYS` 退出，导致 Gradle / AGP / kotlinc / sdkmanager 全部不可用）—— 详见 [`docs/CI-COMPILE-CHECK.md`](docs/CI-COMPILE-CHECK.md)。

## 🔑 Release 签名

把密钥信息写进项目根目录的 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=release.keystore
storePassword=你的store密码
keyAlias=你的key别名
keyPassword=你的key密码
```

- 该文件存在时，`assembleRelease` 自动使用它签名；
- 不存在时，**保持未签名**并在构建日志中提示（不会静默产出一个装不上的包）。

> ⚠️ 切勿把 `.jks` / `.keystore` / `keystore.properties` 提交到仓库。

---

## 🐛 已知限制

- **BUG-24 重构未做**：单聊 / 群聊两份 ViewModel 仍有较多重复代码，计划见 [`docs/REMAINING-BUG24.md`](docs/REMAINING-BUG24.md)
- **加密通话无音频**：只建立并维持加密会话通道（握手 / 心跳 / 挂断），不传语音
- **加密范围**：加密通话目前只加密**文本消息**（含引用）；图片 / 文件 / 语音 / 表情 / 红包仍走各自接口
- **传输层信封未接线**：业务请求走 `/v1` 明文 HTTP（内容保护依赖体层加密，见上）
- **通知点击**只打开 App，未做会话级 deep link
- 部分端点（`/favorites`、`/direct/burn/open`、`before_msg_id` 分页）尚未在真实服务端上验证

---

## 🤝 贡献

- **反馈问题** → [GitHub Issues](https://github.com/OutoriNemuri/OldChat-With-Material3/issues)
- **提交代码** → 请先 Fork 再发起 Pull Request

## 📄 许可证

基于 **GNU General Public License v3.0** 开源，详见 [LICENSE](LICENSE)。

## 🙏 致谢

感谢 OldChat 官方与所有贡献者。本项目为第三方重写实现，仅用于学习与交流。
