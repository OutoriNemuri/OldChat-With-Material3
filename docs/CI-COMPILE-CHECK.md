# 编译验证：GitHub Actions（本机无法编译时的替代方案）

## 为什么需要

开发机（iSH / iOS）上装了 OpenJDK 17/21，但 **JVM 起不来**：

```
Error occurred during initialization of VM
getcpu(2) system call not supported by kernel
```

实测该环境 `getcpu(168)` 与 `rseq(293)` 都返回 `ENOSYS(38)` —— iSH 的
用户态 syscall 模拟层没有实现这两个调用（不是 seccomp 拦截：那会返回 EPERM；
也不是容器：没有 `/.dockerenv`、`/proc/1/cgroup` 为空；内核自报
`Linux version 4.20.69-ish SUPER AWESOME` 是 iSH 的标识）。
HotSpot 在 VM 初始化阶段就要 CPU/NUMA 信息，拿不到即 `vm_exit`，
且没有开关可以跳过。于是 Gradle / AGP / kotlinc / sdkmanager 全部不可用。

## 方案

`.github/workflows/compile-check.yml`：在 GitHub 的 runner（JDK 21 + Android SDK）上跑

1. `:app:compileDebugKotlin` —— Kotlin 类型检查（最快暴露类型错误）
2. `:app:assembleDebug` —— 资源与 AndroidManifest 打包
3. `:app:assembleRelease` —— R8 混淆 + 未签名 release（顺带检验 ProGuard 规则）

触发方式：`workflow_dispatch`、推送到 `fix/**`、以及 PR。

产物：`apks`（`app-debug.apk` 用 CI 生成的 debug 签名，可直接装真机；
`app-release-unsigned.apk` 已过 R8）。

## 首次运行发现并修掉的问题

| # | 问题 | 修法 |
|---|---|---|
| 1 | `app/build.gradle.kts:28` `Unresolved reference 'util'` | Kotlin DSL 里 `java` 被 Gradle 的 java 扩展遮蔽 → 顶部 `import java.util.Properties` |
| 2 | 44 个 Kotlin 编译错误（见下） | 逐条修（提交 `5dbb705`） |
| 3 | `:app:minifyReleaseWithR8` 报 `Missing class org.slf4j.impl.StaticLoggerBinder` | `-dontwarn org.slf4j.**` / `-dontwarn org.slf4j.impl.**`（slf4j 的可选静态绑定探测） |

44 个错误的类型分布（都是"没编译过就提交"的典型症状）：

- **重复声明**：`ChatViewModel` 里我的 `isLoadingMore` 与既有旧分页字段同名
- **嵌套类型没限定**：`ConnectionState` 是 `WebSocketManager` 的内部枚举
- **漏 import**：`BufferOverflow`、`sync.withPermit`、`AppForeground`、`OldChatApplication`
- **Compose 调用参数写错**：`onBurnOpen` 少逗号且插在 `modifier` 之后
- **局部变量声明顺序**：`showHangUpConfirm` 在使用点之后才声明
- **API 名/类型记错**：`setShowOldViewEntry` → 实际是 `setShowOldView`；
  `lastReadNotificationId` 实际是 `Long` 时间戳而不是通知 id `String`
- **可空性**：`watermarkAnchorId` 是 `String` 却赋了 `null`

## 结果（2026-09-13）

```
> Task :app:compileDebugKotlin      BUILD SUCCESSFUL
> Task :app:assembleDebug            → app-debug.apk            28 MB（debug 签名，可安装）
> Task :app:assembleRelease          → app-release-unsigned.apk  4.3 MB（R8 生效：28MB→4.3MB）
```

R8 后体积从 28 MB 降到 4.3 MB，说明「收窄 `-keep feature.**`」确实生效了
（此前整个 UI 层都被 keep，R8 形同虚设）。
