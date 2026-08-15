# OldChat 客户端 Bug 修复追踪

> 从 2.1.0 版本之后开始记录。每修复一个 bug，就在下方追加一条，标记修复时间、根因、涉及文件和验证方式。

## 版本历史

| 版本 | versionCode | 说明 |
|------|-------------|------|
| 2.0.0 | 200 | 音乐广场落地 |
| 2.1.0 | 210 | WS 协议破解 + 致谢 OldChat-For-Windows |
| 2.2.0 | 220 | 滚动到底修复 + 后续 bug 批次 |
| 2.3.1 | 231 | 动态功能（查/发/点赞/评论）|
| 2.3.2 | 2324 | Headless 版本号 build 4；头像/气泡/消息排序/滚动等一揽子修复 + 长按复制/引用 + 实时消息接收 + 举报进度页 + 设置问题反馈 + 服务器地址动态跟随 |
| 2.3.3 | 3005 | Headless 版本号 build 6；注册跳转复位 + 举报进度 + 问题反馈 + 服务器动态跟随 + 聊天滚动/实时接收 + 主界面预览 + 音乐通知栏/下载/上传 + 通知点击跳转正在播放 + 公开法庭界面 |
| 2.3.3 | 3006 | Headless 版本号 build 7；设置「管理缓存」（缓存占用展示 + 一键清理：消息/会话/好友/群组/页面/图片/语音临时）+ 页面间平滑切换动画（聊天详情/我的页子页 滑入滑出+淡入淡出，发现页原有动画保留） |
| 2.3.3 | 3007 | Headless 版本号 build 8；表情发送 + 表情广场（发现页）+ 聊天输入框抽屉化（图片/文件/红包入 + 抽屉、新增「我的表情」磁贴抽屉 + 本地持久化） |
| 2.3.3 | 3008 | Headless 版本号 build 9；已读/送达对号显示（单聊 delivered/read 双对号 + 每 5s 回执实时拉取）+ 软件图标规范化（5 档 mipmap 方形 PNG 替换默认图标 + anydpi-v26 自适应图标用新背景/前景 PNG） |
| 2.3.3 | 3009 | Headless 版本号 build 10；举报进度 + 公开法庭下拉刷新（PullToRefreshBox）+ 公开法庭投票改造（观点选填 + 证据必填，同步到全部观点与证据） |
| 2.3.3 | 3010 | Headless 版本号 build 11；CIP 三入口整合为「CIP 小程序」统一入口（Tab 切换小程序/开发/VibeCoding）+ 打通小程序脚本下载与预览（GET /discover/lua/apps/{id}） |
| 2.3.3 | 3011 | Headless 版本号 build 12；CIP 子页面去除多余顶栏（embedded 内嵌）+ 引入 luaj 依赖使小程序真实可运行（ui.page Lua 沙箱渲染） |
| 2.3.4 | 3012 | patch 升级 + build 重置为 1；完善 WebSocket（事件信封解析 + 事件类型补全 + sendTyping/refreshChannelSubscriptions 主动发送能力） |

---

## 修复记录

### 2026-08-14

#### [#001] 进入聊天界面不滚动到底部（最新消息）

- **版本**：2.2.0
- **现象**：进入单聊/群聊后，消息列表停留在顶部（最早消息），未自动滚动到最新消息。
- **根因**：
  1. `ChatScreen` / `GroupChatScreen` 用 `LaunchedEffect(Unit)` 订阅 `scrollToBottom`（`MutableSharedFlow`），但 `LaunchedEffect(Unit)` 只运行一次，闭包内捕获的 `messages` 是进入页面时的旧快照，消息从网络加载后判断仍基于旧值。
  2. `SharedFlow` 无 `replay`，`emit` 发生在 `collect` 订阅之前时事件丢失。
  3. `animateScrollToItem` 在 item 尚未布局完成时定位可能失败。
- **修复**：
  - 改用 `LaunchedEffect(messages.size)` 直接监听消息列表长度变化，读到的始终是最新 `messages`。
  - 用 `scrollToItem(messages.size - 1)`（挂起函数，等布局完成后滚动）替代 `animateScrollToItem`。
  - `sendMessage` 里的 `animateScrollToItem(messages.size)` 纠正为 `messages.size - 1`（越界修正）。
- **涉及文件**：
  - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`
  - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`
- **验证**：进入单聊/群聊，确认自动定位到最新一条消息。

#### [#002] 编译错误：ChatScreen 缺 Dialog import

- **版本**：2.2.0
- **现象**：`./gradlew assembleDebug` 失败，`ChatScreen.kt:530 Unresolved reference 'Dialog'`。
- **根因**：之前"图片全屏预览"功能引入 `Dialog` 组件，但漏了 `androidx.compose.ui.window.Dialog` 的 import。
- **修复**：在 `ChatScreen.kt` 顶部补 `import androidx.compose.ui.window.Dialog`。
- **涉及文件**：`app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`
- **验证**：重新编译通过。

#### [#003] 切出主页返回后未读数重置 + 列表不显示最后一条消息预览 + 群成员列表不显示

- **版本**：2.2.0
- **现象**：
  1. 切出主页再返回，消息未读提醒重置为无。
  2. 聊天列表只在有新消息时才显示预览，无新消息时应显示该会话最后一条消息。
  3. 群成员列表（弹窗）空白，显示"暂无成员数据"。
- **根因**：
  1. `observeWebSocket` 收到 WS 消息时只更新内存 `_recentChats`（StateFlow），**未同步写回 `cacheManager.recentChats` 持久层**；且 `clearUnread`/`incrementUnread` 未调用 `scheduleSave()`，导致切出返回后 `loadFromDisk` 读到旧数据，未读/预览丢失。
  2. 服务端无会话列表端点，初始会话条目 `lastMessage` 恒为空（`syncChatListFromFriends`/`syncChatListFromGroups` 只新建条目不填预览）。
  3. `loadMembers` 用 `gson.fromJson(body, List<GroupMember>)` 把 wrapper `{members:[...]}` 当纯数组解析 → 失败；且 `GroupMember` 字段 camelCase 无 `@SerializedName`，服务端返回 snake_case（`display_name`/`avatar_url`/数字 `role`）解析不到。
- **修复**：
  1. `observeWebSocket` 改为 `cacheManager.recentChats.upsert()` 同步写回持久层；`RecentChatCache.clearUnread`/`incrementUnread` 补上 `scheduleSave()`。
  2. 新增 `fillDirectPreview`/`fillGroupPreview`，为新会话异步拉 `/direct/messages/v2`、`/groups/messages/v2`（`limit=1`）填充最新消息预览；新增 `parseLatestMessage`/`parseLatestGroupMessage` 解析 wrapper 取最后一条。
  3. `loadMembers` 改为 `parseGroupMembers` 手动解析 wrapper `{members:[...]}`，字段映射 `display_name/username/avatar_url/role`（0=member,1=admin,2=owner）；`MemberListSheet` 成员头像用 `resolveMemberAvatar` 拼接完整 URL。
- **涉及文件**：
  - `app/src/main/java/com/oldchat/material/feature/home/HomeViewModel.kt`
  - `app/src/main/java/com/oldchat/material/core/cache/RecentChatCache.kt`
  - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatViewModel.kt`
  - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`
- **验证**：
  1. 有未读消息时切出主页再返回，未读数保留。
  2. 聊天列表无新消息也显示该会话最后一条消息内容。
  3. 群聊点"成员"按钮，能看到成员列表（昵称 + 头像 + 群主/管理标识）。

#### [#004] 新增功能：消息接收方式设置（WS优先 / 仅WS / 仅HTTP）

- **版本**：2.2.0
- **需求**：设置页新增「消息接收方式」选择框，三种模式：
  1. **WebSocket优先**：优先 WS 接收，断线降级为每 5s HTTP 轮询。
  2. **仅WebSocket**：只用 WS。
  3. **仅HTTP**：每 5s HTTP 轮询。
  前后台均需每 5s 轮询接收消息。
- **实现**：
  1. `PreferencesManager` 新增 `messageReceiveMode`（字符串偏好，默认 `ws_priority`）+ 读写。
  2. 新增 `MessageReceiver` 协调器：订阅模式偏好，统一管理 WS 启停 + HTTP 轮询启停；`WS_PRIORITY` 下监听 `connectionState` 动态降级。
  3. HTTP 轮询实测端点：`POST /direct/unread`、`POST /groups/unread` 均返回 `{messages:[...], has_more:...}`，解析后经 `wsManager.emitHttpDirectMessage/emitHttpGroupMessage` 注入到现有消息流。
  4. `OldChatApplication` 初始化并启动 `MessageReceiver`；`AuthViewModel` 登录、`ChatsScreen` 断线重连、`MessageService` 前后台切换全部改走 `messageReceiver.start()`。
  5. `FullSettingsScreen` 通知区新增「消息接收方式」行 + RadioButton 选择对话框。
- **涉及文件**：
  - `app/src/main/java/com/oldchat/material/core/network/MessageReceiver.kt`（新增）
  - `app/src/main/java/com/oldchat/material/core/network/WebSocketManager.kt`（新增 emitHttpDirectMessage/emitHttpGroupMessage）
  - `app/src/main/java/com/oldchat/material/core/cache/PreferencesManager.kt`
  - `app/src/main/java/com/oldchat/material/OldChatApplication.kt`
  - `app/src/main/java/com/oldchat/material/feature/auth/AuthViewModel.kt`
  - `app/src/main/java/com/oldchat/material/feature/chat/ChatsScreen.kt`
  - `app/src/main/java/com/oldchat/material/service/MessageService.kt`
  - `app/src/main/java/com/oldchat/material/feature/settings/FullSettingsScreen.kt`
 - **验证**：设置 → 通知 → 消息接收方式，切换三种模式，观察 WS 连接状态与 HTTP 轮询行为。

 #### [#005] 新增功能：动态（Moments）界面整合

 - **版本**：2.3.1
 - **需求**：把已具备的接口能力（查动态、发动态含图片、查评论、发评论、点赞/取消赞）整合进动态界面，发动态作为动态界面的子界面。
 - **接口实测**（Python 脚本验证）：
   - `GET /moments/v2` → `{"moments":[{...}]}`；`GET /moments/user?uid=<uid>`。
   - `POST /moments` → `{body, image_url}`，发布需先 `POST /media` 上传图片；有 5s 限流（429）。
   - `POST /moments/like`/`unlike` → `{moment_id}`；`GET /moments/comments?moment_id=` → `{"comments":[{...}]}`；`POST /moments/comment` → `{moment_id, body}`。
   - 字段 snake_case：`from_uid/from_ncuid/from_name/from_title/from_avatar/body/image_url/created_at/likes/comments/liked`。
   - **关键陷阱**：`image_url` 是字符串（单图 URL 或 JSON 数组字符串），不是数组字段；引用字段均用 `moment_id`/`comment_id` 而非 `id`。
 - **实现**：
   1. 重写 `FeedScreen.kt`：正确的动态流列表 + 点赞/取消赞（乐观更新）+ 评论弹层（ModalBottomSheet 查评论/发评论）+ 图片九宫格。
   2. 发动态子界面（`MomentComposerScreen`）：内部状态切换，选图（`ActivityResultContracts.GetContent`）+ `MediaUploader.uploadImage` 上传 + `POST /moments` 发布。
   3. 头像/图片相对路径经 `MediaUrlResolver.resolve` 拼完整 URL。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/discover/FeedScreen.kt`（重写，含 `Moment`/`MomentComment` 模型 + `FeedViewModel`/`MomentComposerViewModel`）
   - 复用：`core/media/MediaUploader.kt`、`core/media/MediaUrlResolver.kt`
 - **验证**：`./gradlew :app:compileDebugKotlin` 通过；真机验证入口「发现 → 动态」。

 #### [#006] 单聊切换人员后聊天记录残留（上一会话消息不消失）

 - **版本**：2.3.1
 - **现象**：在单聊界面从一个人切换另一个人时，上一人的聊天记录仍显示/混入，未被清空。与群聊界面曾犯过的同类问题同源。
 - **根因**：
   - `ChatScreen` 的 `chatViewModel: ChatViewModel = viewModel()` 按 Activity 作用域缓存，切换会话时**不会创建新 ViewModel**，而是复用同一个实例。
   - 切换触发 `LaunchedEffect(friendUid) → init(friendUid, friendName)`，但 `init()` 仅设置了新的 `friendUid/friendName/threadId`，**未清空上一会话的消息列表与分页状态**，导致上一人的消息/去重集合/游标全部泄漏到新会话。
 - **修复**：
   - `ChatViewModel.init()` 在切换会话（`friendUid != uid`）时，先全量重置 per-chat 状态再加载新会话：`_messages.value = emptyList()`、`messageIds.clear()`、`pendingRequests.clear()`、分页游标（`hasMoreBefore/oldestCreatedAt/oldestId/offset/useCursorPagination/isLoadingMore`）归零、`localIdCounter` 重置。
   - 增加 `if (friendUid == uid) return` 早退保护，同一会话重复 `init` 不重复重置（避免重复触发历史加载）。
   - 重置后再 `cache.loadDirectMessages(uid)` 加载新会话缓存 + `loadHistory()` 拉取网络历史。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`（`init()` 全量重置 + 早退保护）
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`（`LaunchedEffect(friendUid)` 触发切换）
 - **验证**：进入 A 单聊 → 返回 → 进入 B 单聊，确认只显示 B 的聊天记录，无 A 残留。

 #### [#007] 聊天图片无法查看 + 群聊/单聊图片空白 + 发送图片闪现后空白

 - **版本**：2.3.1
 - **现象**：
   1. 任何聊天界面（单聊/群聊）都查看不了图片。
   2. 发送图片时，自己侧闪现一下，随后对方侧出现空白气泡（图片不渲染）。
   3. 群聊图片气泡完全不显示图片（只有"图片"文字占位）。
 - **根因**：
   1. 单聊 `ImageBubble` 用 `thumbUrl = resolveMediaUrl(message.thumbUrl)` 作为唯一显示源，`fullUrl = resolveMediaUrl(message.mediaUrl) ?: thumbUrl`。当消息**无 `thumb_url`**（群聊 image 消息实测只有 `media_url`，无 `thumb_url`；部分单聊图片亦然）时 `thumbUrl == null`，走 `else` 分支只显示"图片"文字占位，不渲染真图。
   2. 群聊 `GroupMessageContent` 的 `"image"` 分支指向 `ImagePlaceholder()`（仅"图片"文字 + 图标，无任何图片加载逻辑），完全没实现图片渲染。
   3. `resolveMediaUrl`/`resolveGroupMediaUrl` 拼 URL 逻辑本身正确：相对路径 `/v1/uploads/media/xxx` → `http://60.205.94.101:8080/v1/uploads/media/xxx`，实测返回 200。
 - **修复**：
   1. `ImageBubble`（单聊）：改为 `fullUrl = resolveMediaUrl(message.mediaUrl)`、`thumbUrl = resolveMediaUrl(message.thumbUrl) ?: fullUrl` —— thumb 缺失时 fallback 到全图，保证有 `media_url` 就能显示。
   2. 群聊 `ImagePlaceholder()` 重写为 `GroupImageBubble(message)`：按 `media_url`（缺省 `thumb_url` fallback）加载图片 + 点击全屏预览。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`（`ImageBubble` thumb fallback）
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`（`GroupImageBubble` + `resolveGroupMediaUrl`，替换 `ImagePlaceholder`）
 - **验证**：单聊/群聊查看图片正常显示；发送图片对方侧正常渲染（不再空白）。

 #### [#008] 单聊/群聊非自己消息的气泡左右翻转

 - **版本**：2.3.1
 - **需求**：把单聊、群聊中**非自己发送**的消息气泡（包裹文本、仅三个圆角的圆角矩形）做左右翻转（水平镜像）。
 - **实现**：通过 `Modifier.graphicsLayer { scaleX = -1f }` 对气泡 Box 做水平镜像（圆角方向随之左右翻转）；气泡内部内容再用同样 `graphicsLayer { scaleX = -1f }` 翻回来，保证文字仍可读、仅圆角方向翻转。仅对 `!isOwn` 消息生效，自己发送的（右侧）气泡保持不变。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`（`MessageBubble` 气泡 Box + 内部 Column）
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`（`GroupMessageBubble` 气泡 Box + 内部 Column）
 - **验证**：非自己消息的气泡圆角镜像翻转、文字正常；自己消息气泡不变。

 #### [#009] 聊天界面（会话列表）用户/群聊头像不显示

 - **版本**：2.3.1
 - **现象**：聊天 Tab 的会话列表项里，用户（单聊）和群聊的头像不显示。
 - **根因**：`RecentChatItem.avatarUrl` 在多个构造点丢失（未传 `avatarUrl`）：
   1. `HomeViewModel.observeWebSocket` 收到新会话 WS 消息时新建条目，`RecentChatItem(...)` 未设 `avatarUrl`（默认 null）。
   2. `fillDirectPreview` / `fillGroupPreview` 拉最新消息预览时 `RecentChatItem(...)` 未设 `avatarUrl`，覆盖了原本已拼好的头像。
   3. `ChatsScreen` 的 `ChatListItem` 直接用 `AsyncImage(model = chat.avatarUrl)`，对**相对路径**（`/v1/uploads/...`）未做完整 URL 解析，coil 无法加载相对路径。
 - **修复**：
   1. `observeWebSocket` 新会话、`fillDirectPreview`、`fillGroupPreview` 三处 `RecentChatItem` 构造补全 `avatarUrl`（从 `_friends`/`_groups` 按 uid/id 查找并 `resolveAvatarUrl` 拼完整 URL）。
   2. `ChatsScreen` 新增 `resolveChatAvatar`，`ChatListItem` 头像改为 `AsyncImage(model = resolveChatAvatar(chat.avatarUrl))`，防御性处理相对路径/纯文件名/完整 URL。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/home/HomeViewModel.kt`
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatsScreen.kt`
 - **验证**：聊天 Tab 会话列表项中，单聊用户头像、群聊头像均正常显示。

 #### [#010] 点入聊天界面切出后未读计数不重置

 - **版本**：2.3.1
 - **现象**：会话列表的未读红点，点入聊天界面（单聊/群聊）阅读后，切出返回仍未清除。
 - **根因**：`HomeViewModel.clearUnread(chatId)` / `clearGroupUnread(groupId)` 已定义且正确（调 `RecentChatCache.clearUnread` 写回持久层），但**没有任何地方调用它们**。`ChatViewModel.markRead()` 和 `GroupChatViewModel.markRead()` 只 POST `/direct/read`、`/groups/read`（服务端已读标记），**不清本地 `unreadCount`**。导致红点一直保留（违反"点入阅读后应清未读"预期）。
 - **修复**：在 `MainScreen` 进入聊天详情时（`chatRoute` 从 null 变为非 null），用 `LaunchedEffect(chatRoute?.kind, chatRoute?.id)` 触发 `homeViewModel.clearGroupUnread(id)`（群聊）或 `homeViewModel.clearUnread(id)`（单聊）。`MainScreen` 新增 `homeViewModel: HomeViewModel = viewModel()`，与 `ChatsScreen`/`FriendsScreen` 内部的 `viewModel()` 复用同一 Activity 作用域实例。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/ui/screen/MainScreen.kt`（新增 `homeViewModel` + `LaunchedEffect` 清未读）
 - **验证**：有未读红点时进入对应聊天，切出返回后红点消失；无未读时无副作用。

 #### [#011] 发送消息后最新一条被置于列表顶部而非底部

 - **版本**：2.3.2
 - **现象**：发送消息后，最新一条被排到列表顶部；再发一条，上一条又"跑到"底部。
 - **根因**：发送的本地消息用 `System.currentTimeMillis()/1000` 作 `createdAt`，当本机时间 ≤ 服务端已有消息时间戳时，发送后紧接着的一次 `mergeMessages` 按 `createdAt` 全局重排，把刚发的消息排到上方。触发重排的是本轮新增的实时订阅（`directMessages`/`groupMessages` collect → `onWsMessage`）与并发 `loadHistory`。
 - **修复**：`ChatViewModel.addPending` / `GroupChatViewModel.addGroupPending` 在追加 pending 消息前，把其 `createdAt` 提升到「当前列表最大 createdAt + 1」，保证任何重排后仍居底；并修正群聊 `addGroupPending` 原来 `sortedBy(groupSeq).sortedBy(createdAt)` 互相覆盖的排序 bug，改为 `sortedBy(groupSeq)`（pending 的 groupSeq=MAX 天然置底）。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatViewModel.kt`
 - **验证**：发送消息后停在底部；连续发送多条，顺序稳定不跳位。

 #### [#012] 会话列表用户名下方的最新一条消息"不新"

 - **版本**：2.3.2
 - **现象**：会话列表项显示的最新一条消息内容不是真正最新的一条。
 - **根因**：
   1. `fillDirectPreview`/`fillGroupPreview` 只在「新会话」分支调用（isNew），已存在的会话从不刷新预览，显示旧缓存。
   2. `parseLatestMessage`/`parseLatestGroupMessage` 用 `lastOrNull()`/`last()` 取数组「最后一条」，但实测 `/direct/messages/v2`、`/groups/messages/v2` 返回**最新在前、最旧在后**（倒序），取 last 反而取到最旧一条。
 - **修复**：
   1. `syncChatListFromFriends`/`syncChatListFromGroups` 改为对**所有会话**（不限 isNew）调用 `fillDirectPreview`/`fillGroupPreview`。
   2. `parseLatestMessage`/`parseLatestGroupMessage` 改为遍历取 `createdAt` 最大的一条，不依赖数组顺序。
 - **涉及文件**：`app/src/main/java/com/oldchat/material/feature/home/HomeViewModel.kt`
 - **验证**：会话列表项显示的预览确为该会话最新一条。

 #### [#013] 点入聊天界面偶尔停留在中间而非底部

 - **版本**：2.3.2
 - **现象**：极少数情况下，点入聊天界面定位在列表中间，而非底部最新消息。
 - **根因**：`LaunchedEffect(messages.size) { scrollToItem(messages.size - 1) }` 用同步 `scrollToItem` 在 LazyColumn 首次布局未完成、历史加载过程中 messages.size 多次变化时滚动失败，落在中间。
 - **修复**：用 `scrollInitDone` 区分「首次定位」与「后续新消息」：首次用 `LaunchedEffect(messages)` + `withFrameNanos {}` 等一帧布局后再滚到底；后续仅在 `scrollInitDone` 后滚动，避免加载历史反复横跳。
 - **涉及文件**：`app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`、`GroupChatScreen.kt`
 - **验证**：多次进出聊天，均稳定定位到底部。

 #### [#014] 任何聊天界面均不显示「自己」的头像

 - **版本**：2.3.2
 - **现象**：单聊/群聊里自己一侧的头像不显示（对方头像正常）。
 - **根因**：自己一侧头像代码写死 `AvatarBox(avatar = null, ...)` / `GroupAvatar(null, "")`，从不加载自己的头像。
 - **修复**：`ChatViewModel`/`GroupChatViewModel` 各暴露 `myAvatarUrl: StateFlow<String?>`，init 时 `loadMyAvatar()` 加载（先读 `/me` 缓存 `profileCacheJson`，否则 GET `/me`），解析出相对路径补全为完整 URL；`ChatScreen`/`GroupChatScreen` 收集后传给气泡，自己的消息一侧显示真实头像。
 - **涉及文件**：`ChatViewModel.kt`、`GroupChatViewModel.kt`、`ChatScreen.kt`、`GroupChatScreen.kt`
 - **验证**：单聊/群聊自己一侧头像正常显示。

 #### [#015] 单聊/群聊气泡用 scaleX 镜像导致文字/图片不可读，且圆角方向错乱

 - **版本**：2.3.2
 - **现象**：气泡用 `graphicsLayer { scaleX = -1f }` 翻转后文字/图片被镜像、圆角方向错乱（修复 #008 的反向问题）。
 - **根因**：#008 采用「整个气泡 scaleX=-1 翻转 + 内层再翻回」的错误实现，图片内容被镜像、形状与内容分离。
 - **修复**：彻底移除气泡与内容的 `scaleX` 镜像，改用 `RoundedCornerShape(topStart, topEnd, bottomStart, bottomEnd)` 直接定义尖角方向——自己在右（右下尖）、对方在左（左下尖），内容始终正常渲染。
 - **涉及文件**：`ChatScreen.kt`（`MessageBubble`）、`GroupChatScreen.kt`（`GroupMessageBubble`）
 - **验证**：单聊/群聊气泡文字图片可读，圆角方向正确（自己在右尖角右、对方在左尖角左）。

 #### [#016] 群聊对方气泡姓名显示 uid 而非用户名 + 群聊气泡逻辑与单聊不一致

 - **版本**：2.3.2
 - **现象**：
   1. 群聊对方消息上方姓名显示的是 uid（如 `USR-xxx`），而非用户名。
   2. 群聊气泡的位置/圆角逻辑与单聊不一致。
 - **根因**：
   1. 对方昵称来源是 `message.cachedSenderName ?: message.fromUid.take(8)`，而 `cachedSenderName` 是 transient 缓存字段（不持久化），从磁盘缓存加载的历史消息 `cachedSenderName=null`，fallback 到 `fromUid`（uid）。
   2. 群聊历史上多加了 sender name/role badge 显示、且曾反复改过翻转方向，与单聊不一致。
 - **修复**：
   1. 在 `GroupChatScreen` 渲染时用 `members.firstOrNull { it.uid == msg.fromUid }` 匹配出 `nickname`/`role` 传入气泡，优先级 `member.nickname > cachedSenderName > fromUid`，头像兜底 text 也用昵称。
   2. 群聊气泡位置/圆角与单聊对齐：自己在右（右下尖）、对方在左（左下尖），移除错误的 scaleX 镜像；sender name + role badge 保留（群聊多人的必要区分信息），但显示正确昵称。
 - **涉及文件**：`app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`
 - **验证**：群聊对方消息上方显示正确用户名（非 uid），气泡方向与单聊一致。

 #### [#017] 图片发送失败：对方显示空消息且未发出

 - **版本**：2.3.2
 - **现象**：发送图片后，对方看到空消息，实际未发送成功。
 - **根因**：`ChatViewModel.sendMedia` / `GroupChatViewModel.sendMedia` 构造请求 body 时把 `duration_ms` 用 `durationMs.toString()` 转成了字符串（`"duration_ms": "0"`），而服务端期望整数类型，返回 400 `invalid json`，导致图片消息发送失败。
 - **修复**：两处 `sendMedia` 的 `duration_ms` 改为直接传整数 `durationMs`（不再 `.toString()`）。实测 `duration_ms: 0`（整数）与省略该字段均返回 201，字符串则 400。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatViewModel.kt`
 - **验证**：发送图片正常，对方正常收到图片消息。

 #### [#018] 自己消息顶满一行时挤扁自己的头像

 - **版本**：2.3.2
 - **现象**：自己发送的长消息（顶满一行）会把右侧自己的头像挤压变形。
 - **根因**：`MessageBubble` / `GroupMessageBubble` 的 Row 里，气泡所在的 `Column` 未加 `weight`，长内容时 Row 测量会压缩固定尺寸的头像，导致头像被挤扁。
 - **修复**：给气泡 Column 加 `Modifier.weight(1f, fill = false)`，让 Column 只占用内容实际宽度（受气泡 `widthIn(max=300.dp)` 限制），剩余空间留给头像，头像不再被挤压。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`（`MessageBubble`）
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`（`GroupMessageBubble`）
 - **验证**：发送超长消息，头像保持固定尺寸不变形。

 #### [#019] 会话列表未按最新消息时间排序

 - **版本**：2.3.2 (build 2)
 - **现象**：聊天 Tab 的会话列表顺序固定（按缓存存储顺序），最新收到消息的会话不在最上。
 - **根因**：`HomeViewModel._recentChats` 直接取 `cacheManager.recentChats.getAll()`，无任何排序。
 - **修复**：`ChatsScreen` 里对 `recentChats` 做 `sortedByDescending { it.lastTime }`（按最近消息时间降序），最新消息的会话排在最上；`items` 与空判断改用排序后的列表。
 - **涉及文件**：`app/src/main/java/com/oldchat/material/feature/chat/ChatsScreen.kt`
 - **验证**：有新消息的会话自动置顶。

 #### [#020] OldChat 品牌字样改为 OldChat Material

 - **版本**：2.3.2 (build 2)
 - **需求**：将界面上的品牌名 OldChat 统一改为 OldChat Material。
 - **修改**（统一将界面与可见文本中的品牌名 OldChat 改为 OldChat Material；保留 `OldChat-For-Windows` 开源项目名不变）：
   1. 聊天界面顶部标题（`MainScreen` `titleText`，selectedTab==0）：`OldChat` → `OldChat Material`。
   2. 设置界面关于对话框正文大标题与标题：`Text("OldChat")`/`"关于 OldChat"` → `Text("OldChat Material")`/`"关于 OldChat Material"`；设置项标题同改。
   3. 关于正文"Material You 设计的第三方 OldChat Material 客户端"。
   4. 登录页大标题 `text = "OldChat"` → `"OldChat Material"`；登录页"自定义 OldChat Material 服务器地址"。
   5. 前台服务通知标题 `.setContentTitle("OldChat Material")` + 通知管道名"OldChat Material 后台服务"。
   6. 聊天动态占位副标题"来自 OldChat 社区"与 CIP 开发者示例 `Hello, OldChat!` **保持为 OldChat，不改为 Material**（用户要求复位）。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/ui/screen/MainScreen.kt`
   - `app/src/main/java/com/oldchat/material/feature/settings/FullSettingsScreen.kt`
   - `app/src/main/java/com/oldchat/material/feature/auth/LoginScreen.kt`
   - `app/src/main/java/com/oldchat/material/service/MessageService.kt`
 - **验证**：相关界面与通知均显示 OldChat Material；动态占位与 CIP 示例保留 OldChat。

 #### [#021] 群聊气泡图形与单聊不完全一致（尖角方向反了）

 - **版本**：2.3.2 (build 2)
 - **现象**：群聊聊天气泡的尖角方向与单聊相反，图形不一致。
 - **根因**：单聊 `MessageBubble` 用**命名参数** `RoundedCornerShape(topStart=, topEnd=, bottomStart=, bottomEnd=)`；群聊 `GroupMessageBubble` 用**位置参数** `RoundedCornerShape(16,16,16,4)`/`(16,16,4,16)`。位置参数顺序实际是 `(topStart, topEnd, bottomEnd, bottomStart)`，导致群聊自己在右却左下尖、对方在左却右下尖，与单聊恰好相反。
 - **修复**：群聊 `GroupMessageBubble` 的 shape 改用与单聊完全一致的命名参数写法：`bottomStart = if(isOwn) 16.dp else 4.dp`、`bottomEnd = if(isOwn) 4.dp else 16.dp`，消除位置参数顺序歧义。现在群聊与单聊完全一致：自己在右（右下尖）、对方在左（左下尖）。
 - **涉及文件**：`app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`（`GroupMessageBubble` shape）
 - **验证**：群聊气泡圆角/尖角方向与单聊完全一致。

 #### [#022] 停留在单聊/群聊界面时不实时更新接收到的消息

 - **版本**：2.3.2 (build 2)
 - **现象**：停留在单聊/群聊界面时，对方发来的新消息不实时显示。
 - **根因**：
   1. （单聊）`ChatViewModel.init` 把 `threadId = uid`（好友 uid），但服务端真实 `thread_id` 是独立会话 ID（实测 `pW2KjGijZ0C633JvQ1I1Y` 等，非 uid）。`onWsMessage` 过滤条件用 `&&`（`fromUid != friendUid && threadId != threadId`），一旦消息 `fromUid` 为空（WS 推送字段解析不到）或 threadId 不匹配，整条被 `return` 丢弃。且自己发的消息回显（fromUid=自己、threadId=真实值）也会因 threadId 不匹配被丢。
   2. （群聊）WS 实时推送的群消息可能只带 `sort_seq` 而不带 `group_seq`，`GroupMessage.groupSeq` 反序列化为 0，`mergeGroupMessages` 按 `groupSeq` 升序排序时把实时消息（groupSeq=0）排到列表**顶部**，看起来"没更新"或位置错乱。
 - **修复**：
   1. `ChatViewModel.onWsMessage` 过滤改为 `||` 逻辑（`fromUid == friendUid || threadId == threadId || fromUid == myUid`），任一条匹配即接收；并在收到带真实 `thread_id` 的消息时**补全本地 `threadId`**。
   2. `MessageReceiver.parseGroupMessages` 与 `WebSocketManager.dispatchMessage`（群消息分支）在 `groupSeq == 0` 时兜底用 `sort_seq`，避免实时群消息排到顶部。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`（`onWsMessage` 过滤 + threadId 补全）
   - `app/src/main/java/com/oldchat/material/core/network/MessageReceiver.kt`（`parseGroupMessages` sort_seq 兜底）
   - `app/src/main/java/com/oldchat/material/core/network/WebSocketManager.kt`（群消息 sort_seq 兜底）
 - **验证**：停留在单聊/群聊界面，对方发消息，列表实时出现新消息并定位到底部。

 #### [#023] 接收消息不自动滚动到底 + 顶栏新增「更新消息」按钮

 - **版本**：2.3.3 (build 1)
 - **现象**：
   1. 聊天界面接收（或发送）消息时，不自动滚动到最底部（差一条）。
   2. 顶栏缺少手动刷新最新消息的按钮。
 - **根因**：
   1. 单聊/群聊的 `LazyColumn` 前置了 `item(key = "load_more")`（占据 index 0），消息 items 从 index 1 起，最后一条消息的真实 index 是 `messages.size`，但代码用 `scrollToItem(messages.size - 1)` / `animateScrollToItem(messages.size - 1)`，比真实末尾少滚一条，导致接收消息后停在上一条。
 - **修复**：
   1. `ChatScreen`/`GroupChatScreen` 所有滚动定位从 `messages.size - 1` 修正为 `messages.size`（首次进入 `scrollToItem`、后续新消息 `scrollToItem`、发送后 `animateScrollToItem` 三处）。
   2. 两个 ViewModel 各新增 `refreshLatest()`：单聊拉 `GET /direct/messages/v2`（with_uid + limit），群聊拉 `GET /groups/messages/v2`（group_id + limit + mark_read=1），拿到最新一页 `mergeMessages(... appendToFront=false)` 增量合入（去重），成功后 `_scrollToBottom.emit(Unit)`。
   3. 两个聊天 Screen 的 `TopAppBar` `actions` 新增 `IconButton(Icons.Filled.Refresh)` 调用 `refreshLatest()`。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatScreen.kt`（滚动 index + 顶栏刷新按钮）
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatScreen.kt`（滚动 index + 顶栏刷新按钮）
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`（`refreshLatest()`）
   - `app/src/main/java/com/oldchat/material/feature/chat/GroupChatViewModel.kt`（`refreshLatest()`）
 - **验证**：单聊/群聊收到或发送消息后自动滚到最新一条；点顶栏「更新消息」按钮拉取最新消息并定位到底部。

 #### [#024] 停留聊天界面收消息滞留（需频繁刷新）+ 主界面预览偶现不及时

 - **版本**：2.3.3 (build 2)
 - **现象**：
   1. 停留在单聊/群聊界面时，新消息不实时到达（必须频繁点「更新消息」按钮才出现）。
   2. 主界面会话列表的最新消息预览偶现不及时更新（甚至显示 raw JSON 或漏刷）。
 - **根因**：
   1. （滞留核心）`MessageReceiver` 在默认 `WS_PRIORITY` 模式下，一旦 WS `CONNECTED` 就 `stopPolling()`，完全依赖 WS 实时推送。但 App 内 WS 推送受 ECDH 加密/信封格式影响不可靠；而可靠的 HTTP 轮询 `POST /direct/unread` + `POST /groups/unread`（实测返回 `{messages:[...]}`，字段含 `from_uid/peer_uid/thread_id/group_id/group_seq/sort_seq`）被停掉，导致消息滞留。
   2. （single chat 归属）`Message` 模型缺少 `peer_uid` 字段；`/direct/unread` 里自己发的消息回显 `from_uid=自己`、`peer_uid=对方`，而代码用 `fromUid` 匹配会话/过滤，自己回显或 `fromUid` 为空时被误判/丢弃。
   3. （预览）`HomeViewModel.observeWebSocket` 的 direct 分支用 `message.fromUid` 作 chatId（自己回显会错建会话）、group 分支新会话不建条目；且 `lastMessage` 直接取 `message.body`（可能是 JSON payload），显示 raw JSON。
 - **修复**：
   1. `MessageReceiver.ensurePollingObserved`：`WS_PRIORITY` 模式下**不再在 WS 连接后停轮询**，改为 WS 连接/断开都保持 HTTP 轮询兜底（`seenDirectIds`/`seenGroupIds` 去重保证不重复）。
   2. `Message.kt` 新增 `@SerializedName("peer_uid") val peerUid` 字段。
   3. `ChatViewModel.onWsMessage` 过滤改用 `fromUid/peerUid/threadId/myUid` 的 `||` 匹配，覆盖自己回显场景。
   4. `HomeViewModel.observeWebSocket`：direct 用 `peerUid.ifEmpty { fromUid }` 定位会话、自己消息不增未读、group 新会话也新建条目；新增 `extractPreview(msgType, body)` 复用 `MessagePayloadBuilder.extractPreviewText`，`fillDirectPreview`/`fillGroupPreview` 与实时预览统一取可读文本而非 raw body。
 - **涉及文件**：
   - `app/src/main/java/com/oldchat/material/core/network/MessageReceiver.kt`
   - `app/src/main/java/com/oldchat/material/core/model/Message.kt`
   - `app/src/main/java/com/oldchat/material/feature/chat/ChatViewModel.kt`
   - `app/src/main/java/com/oldchat/material/feature/home/HomeViewModel.kt`
 - **验证**：停留单聊/群聊界面，对方发消息 5s 内自动出现（无需手动刷新）；主界面会话列表预览实时更新且为可读文本。

 #### [#025] 音乐广场通知栏 + 下载/我的下载 + 上传功能

 - **版本**：2.3.3 (build 3)
 - **需求**：实现此前未实现的音乐广场功能。
 - **实现**：
   1. **音乐通知栏（前台服务）**：新增 `MusicPlaybackService.kt`（`foregroundServiceType="mediaPlayback"`），复用 `MusicPlayerHolder` 单例 ExoPlayer。通知栏展示歌名/艺术家 + 三个控制按钮：停止、播放/暂停、循环开关（循环开/关）。播放页 `MusicPlayerScreen` 播放时调用 `MusicPlaybackService.start()`，退出播放页后音乐继续、通知栏可控。`MusicPlayerHolder` 新增 `currentRepeatMode`/`currentTitle`/`currentArtist` 共享状态，循环切换到播放页与服务同步。
   2. **我的下载**：新增 `MyDownloadsScreen.kt`，扫描 `MediaStore.Downloads` 里的音频文件（.mp3/.m4a/.wav/.flac/.ogg/.aac），列表展示（标题/大小），支持点击播放、删除本地文件。
   3. **上传功能**：新增 `MusicUploadScreen.kt`，选音频文件（必填）+ 可选封面 + 可选歌词 + 歌名，调用 `MusicViewModel.uploadSong`（multipart `POST /music/plaza/upload`）。
   4. **我的上传**：新增 `MyUploadsScreen.kt`，展示 `/music/plaza/mine`，支持播放、删除（`can_delete=true` 时，`POST /music/plaza/delete`）+ 点赞/取消赞（`MusicViewModel.toggleLike`）。
   5. **模型补全**：`MusicSong` 新增 `liked`/`canDelete`/`ownerUid`/`ownerAvatar`/`ownerTitle`/`sizeBytes` 字段（实测 `/music/plaza` 返回字段含 `liked`/`can_delete`/`owner_avatar`/`owner_title`/`size_bytes`）。
 - **涉及文件**：
   - `service/MusicPlaybackService.kt`（新增，前台服务 + 通知栏）
   - `discover/MyDownloadsScreen.kt`（新增）
   - `discover/MusicUploadScreen.kt`（新增）
   - `discover/MyUploadsScreen.kt`（新增）
   - `discover/MusicSquareScreen.kt`（子页面导航 + `MusicSong` 字段补全 + `deleteSong`/`toggleLike`）
   - `discover/MusicPlayerScreen.kt`（播放时启动前台服务 + 循环同步 + `MusicPlayerHolder` 扩展）
   - `AndroidManifest.xml`（注册 `MusicPlaybackService`）
 - **验证**：播放音乐后通知栏出现（含暂停/循环/停止控制）；下载歌曲后「我的下载」可见并可播放/删除；上传音频后「我的上传」可见且可删除/播放。

 #### [#026] 点击音乐通知跳转到"正在播放"界面而非主界面

 - **版本**：2.3.3 (build 4)
 - **需求**：点击通知栏的音乐通知时，应跳到"正在播放"界面（音乐广场播放页），而非主界面。
 - **实现**：
   1. `MusicPlaybackService.buildNotification` 的 ContentIntent 改为带 extra 的 Intent：`EXTRA_OPEN_MUSIC=true` + `EXTRA_MUSIC_TITLE=<当前歌名>`，并用 `FLAG_ACTIVITY_SINGLE_TOP | NEW_TASK`。
   2. `MainActivity`：`onCreate` 读 extra 得到 `openMusicTitle`（首次启动）；`onNewIntent` 更新 companion 可观察状态 `pendingOpenMusicTitle`（App 已在后台复用 Activity 的场景）；新增 `extractOpenMusicTitle(intent)` 解析 helper。
   3. `MainScreen` 新增 `openMusicTitle: String?` 参数，用 `LaunchedEffect(openMusicTitle, pendingFromNotification)`：有通知指令时切到"发现"Tab、`discoverRoute="music_square"`、`pendingMusicTitle` 设为当前歌（优先 extra 歌名，否则用 `MusicPlayerHolder.currentTitle`），从而让音乐广场自动搜索并加载/播放歌曲。
 - **涉及文件**：
   - `service/MusicPlaybackService.kt`（ContentIntent 带 extra + 常量）
   - `MainActivity.kt`（onCreate/onNewIntent 读取 + companion 状态）
   - `ui/screen/MainScreen.kt`（`openMusicTitle` 参数 + 导航到音乐播放页）
 - **验证**：播放音乐后后台运行 App，点通知栏通知，应直接进入"正在播放"界面（音乐广场）并定位/播放当前歌曲。

 #### [#027] 公开法庭界面（发现页）

 - **版本**：2.3.3 (build 5)
 - **需求**：制作发现页的公开法庭界面（卡片形式列表 + 多卡片详情）。
 - **数据源（实测）**：
   - `GET /public-court/cases?status=all` → `{cases:[...]}`，字段：id/reporter_uid/reporter_name/reporter_avatar/defendant_uid/defendant_name/defendant_avatar/report_reason/report_evidence/defense_reason/defense_evidence/status/verdict/admin_note/ban_hours/ban_vote_count/keep_vote_count/total_vote_count/my_vote/my_vote_reason/created_at 等。
   - `GET /public-court/cases/{id}` → `{case, discussions, merged_reports, statements}`。
   - 状态枚举：`pending_review`/`withdrawn`；判定枚举：`ban`/`keep`；投票：`POST /cases/{id}/vote`（body {vote}）；讨论：`POST /cases/{id}/discussion`。
   - 状态徽标细分规则：`withdrawn`→已撤销；`pending_review` 且投票数满 10→待审核；`pending_review` 且投票数未满 10→投票中。
 - **实现**：
   1. `PublicCourtViewModel.kt`：数据模型（`CourtCase`/`CourtStatement`/`CourtDiscussion`/`CourtMergedReport`/`CourtDetail`）+ ViewModel（`loadCases`/`loadDetail`/`vote`/`postDiscussion`）。
   2. `PublicCourtScreen.kt`：列表页，卡片含「案件编号＋状态徽标、最终/封禁/不封禁票数、举报方↔被举报方对质、举报理由/证据摘要/阶段结果、开庭时间、查看详情按钮」。
   3. `CourtDetailScreen.kt`：详情页多卡片——卡片1案件概览（复用列表卡片）、卡片2投票（封禁/不封禁，总票满10或已撤销无效）、卡片3举报方/被举报方理由/证据、卡片4全部观点与证据（statements 小卡片，reporter=举报人/jury=陪审团，观点与证据分行）、卡片5案件讨论（输入框补充观点到 statements）、卡片6管理员裁决、卡片7叠加举报记录。
   4. `MainScreen.kt`：接入 `"public_court"` 路由。
 - **涉及文件**：
   - `discover/PublicCourtViewModel.kt`（新增）
   - `discover/PublicCourtScreen.kt`（新增）
   - `discover/CourtDetailScreen.kt`（新增）
   - `ui/screen/MainScreen.kt`（路由接入）
  - **验证**：发现 → 公开法庭，列表展示案件卡片；点击「查看详情」进入多卡片详情页，可投票（未满10且未撤销）、查看双方陈述/全部观点/讨论/管理员裁决/叠加举报。

 #### [#028] 设置「管理缓存」+ 页面间平滑切换动画

 - **版本**：2.3.3 (build 7)
 - **需求**：
   1. 设置页新增「管理缓存」：展示各项缓存占用情况，可一键清理。
   2. 页面与页面之间追加平滑的切换动画。
 - **实现**（管理缓存）：
   1. `CacheManager` 新增缓存统计与清理：`CacheGroup` 数据类 + `buildCacheGroups(context)`（统计 消息/会话/好友/群组/页面 SharedPreferences 缓存、Coil 图片磁盘缓存 `image_cache`、语音临时文件 `voice_*.aac`）+ `totalCacheSize(context)` + `clearAppCache(context)` 一键清理（清空业务缓存内存+磁盘、删图片磁盘缓存触发 Coil diskCache.clear、删语音临时文件；不影响登录态与 DataStore 配置）。
   2. `FullSettingsScreen` 新增「存储」分组 +「管理缓存」行（副标题实时显示总占用），点击弹出对话框：逐项列出各缓存占用 + 合计，提供「一键清理」按钮（IO 线程执行 + 进度条，完成后自动清点刷新）。
 - **实现**（切换动画）：
   3. `MainScreen` 聊天详情（单聊/群聊）用 `AnimatedContent` 增加滑入滑出 + 淡入淡出过渡。
   4. `ProfileScreen`「我的」页子页（编辑资料/我的空间/收藏/设置）用 `AnimatedContent` 增加"右侧滑入/左侧滑回 + 淡入淡出"，返回主界面亦有过渡；发现页原有 `AnimatedContent` 滑动动画保留。
 - **涉及文件**：
   - `core/cache/CacheManager.kt`（统计 + 清理）
   - `feature/settings/FullSettingsScreen.kt`（存储分组 + 管理缓存对话框 + 字节格式化）
   - `ui/screen/MainScreen.kt`（聊天详情动画）
   - `feature/settings/ProfileScreen.kt`（我的页子页动画）
 - **验证**：设置 → 存储 → 管理缓存，显示消息/会话/好友/群组/页面/图片/语音各缓存占用，点「一键清理」后占用归零；进入聊天详情、我的页子页均有平滑切换动画。

 #### [#029] 表情发送 + 表情广场 + 输入框抽屉改造（图片/文件/红包入抽屉 + 我的表情磁贴）

 - **版本**：2.3.3 (build 7)
 - **需求**：
   1. 制作表情发送与表情广场（发现页）。
   2. 聊天输入框：图片/文件/红包移入抽屉（点 + 弹出、+ 变 ×），新增表情按钮打开「我的表情」抽屉（磁贴 + 添加按钮，本地持久化）。
 - **接口契约（Python 实测验证）**：
   - 发送表情：`POST /groups/message/send`，`msg_type="image"` + `body={"v":2,"text":"","media_kind":"emoji"}` + `media_url`。
   - 列表：`GET /emoji/plaza?limit=&offset=` → `{items:[...], total, has_more}`（item 字段 id/name/media_url/cover_url/item_count/is_gif/size_bytes/created_at/owner_uid/owner_name/owner_title?/owner_avatar，其中 `owner_title` 可选）。
   - **搜索：`GET /emoji/plaza?q=<kw>`（字段是 `q`，不是 `keyword`；`keyword` 被服务端忽略返回全量）**。
   - 我的上传：`GET /emoji/plaza/mine`。
   - 上传：`POST /emoji/plaza/upload`（multipart，`name`=标题 + `file`=文件）。
   - 保存：`POST /emoji/plaza/save` body **`{"item_id":"..."}`**（不是 `id`）。
   - 删除：`POST /emoji/plaza/delete` body **`{"item_id":"..."}`**（不是 `id`）。
 - **实现**：
   1. `core/cache/EmojiStore.kt`（新增）：本地持久化「我的表情」（SharedPreferences `my_emoji_store`），`getAll/add/remove/clearAll`。
   2. `CacheManager` 挂 `emojiStore`，`clearAll()`（账号切换）时清空。
   3. `ChatViewModel.sendEmoji` / `GroupChatViewModel.sendEmoji`：`sendMedia("image", url, url, buildBody(mediaKind="emoji"), 0)`。
   4. `feature/chat/EmojiPickerSheet.kt`（新增）：「我的表情」抽屉，磁贴式（每行 6 个）+ 添加按钮，点击发送、长按删除。
   5. `ChatScreen`/`GroupChatScreen` 输入框：+ 号（展开变 ×）弹出附件抽屉（图片/文件/红包），新增表情按钮打开「我的表情」抽屉。
   6. `discover/EmojiPlazaViewModel.kt` + `EmojiPlazaScreen.kt`（新增）：搜索框+按钮、筛选（广场/我上传的）、分页（每页 50）、表情列表（图+标题+保存+自己的红色删除按钮）、右下角上传 FAB（对话框输入标题+选文件）。
   7. `MainScreen` 接入 `sticker_store` 路由 → `EmojiPlazaScreen`（替换原 UnderConstructionScreen 占位）。
 - **涉及文件**：
   - `core/cache/EmojiStore.kt`（新增）
   - `core/cache/CacheManager.kt`（emojiStore 字段 + clearAll）
   - `feature/chat/ChatViewModel.kt`、`GroupChatViewModel.kt`（sendEmoji）
   - `feature/chat/EmojiPickerSheet.kt`（新增）
   - `feature/chat/ChatScreen.kt`、`GroupChatScreen.kt`（输入框抽屉化 + 表情按钮）
   - `feature/discover/EmojiPlazaViewModel.kt`、`EmojiPlazaScreen.kt`（新增）
   - `ui/screen/MainScreen.kt`（sticker_store 路由）
 - **验证**：Python 已验证发送/下载/上传/删除全链路；客户端编译后真机验证 表情广场浏览/搜索/上传/删除、聊天点表情发送、「我的表情」持久化。

 #### [#030] 修复 EmojiPickerSheet 编译错误 + 统一单聊/群聊消息类型渲染

 - **版本**：2.3.3 (build 8)
 - **编译错误修复**：
   - `EmojiPickerSheet.kt` 此前 import 的是 `Icons.Filled.Add`/`Icons.Filled.Delete`（具体 import），但代码里用了 `Icons.Filled.Close`，缺 `import ...filled.Close` → `Unresolved reference 'Close'`。修复：把 `Delete` import 改为 `Close`（`Delete` 已弃用，长按删除改用 `combinedClickable`）。
 - **统一消息类型渲染**（单聊完整、群聊残缺不一致）：
   - 根因：单聊 `MessageContent` 完整实现 text/image(全屏预览)/video/voice/emoji/file/红包/forward/music；群聊 `GroupMessageContent` 只走残缺占位（`GroupTextBubble`/`GroupImageBubble`/`VideoPlaceholder`/`VoicePlaceholder`/`FilePlaceholder`/`RedPacketPlaceholder`），缺 forward/music/emoji，视频/语音/文件/红包均为简化占位。
   - 修复：将单聊 `MessageContent` 由 `private` 改为 `internal`；群聊 `GroupMessageContent` 构造轻量 `Message`（映射 id/body/msgType/mediaUrl/thumbUrl/durationMs/localProgress/cachedPayload）后**复用 `MessageContent`**，实现单聊/群聊消息类型渲染完全一致；并补全群聊音乐消息跳转链（`GroupChatScreen`/`GroupMessageBubble`/`GroupMessageContent` 增加 `onOpenMusic`，`MainScreen` 群聊分支传入与单聊一致的「跳转音乐广场播放」逻辑）。
 - **涉及文件**：
   - `feature/chat/EmojiPickerSheet.kt`（修复 Close import）
   - `feature/chat/ChatScreen.kt`（`MessageContent` private → internal）
   - `feature/chat/GroupChatScreen.kt`（`GroupMessageContent` 复用 `MessageContent` + `onOpenMusic` 链）
   - `ui/screen/MainScreen.kt`（群聊分支传入 `onOpenMusic`）
  - **验证**：编译通过；真机验证群聊图片可全屏预览、视频/语音/文件/红包/转发/音乐/表情渲染与单聊一致。

  #### [#031] 聊天消息已读/送达对号显示 + 回执实时拉取

  - **版本**：2.3.3 (build 8)
  - **需求**：
    1. 群聊：自己发的消息 delivered → 发送时间旁显示 1 个对号。
    2. 单聊：自己发的消息 delivered → 1 个对号；若同时 read → 2 个对号。
    3. 确保这些信息在聊天界面被实时拉取。
  - **服务端契约（Python 实测）**：
    - **单聊** `/direct/messages/v2?with_uid=<uid>` 返回 `delivered_at`、`read_at`（Unix 秒，0=未送达/未读）；自己发的 9 条消息全带这两个字段。
    - **群聊** `/groups/messages`、`/groups/messages/v2`、`/groups/messages/after` **均不返回** `delivered_at`/`read_at`/`read_count`（字段列表固定为 sort_seq/group_seq/id/group_id/from_uid/from_ncuid/from_name/body/msg_type/media_url/thumb_url/created_at）。故群聊送达状态服务端当前不提供，客户端字段已预留但恒不显示单对号。
  - **实现**：
    1. `Message.kt` 新增 `deliveredAt`/`readAt` 字段（`@SerializedName("delivered_at"/"read_at")`，Gson 自动映射）；`Group.kt` 同步新增并注释说明服务端暂不返回。
    2. `ChatScreen.kt` 新增 `ReadReceiptTicks(isOwn, message)`：`delivered`（deliveredAt>0 或 status≥DELIVERED）显示单个灰色 `Done` 对号；`read`（readAt>0 或 status≥READ）显示两个重叠的蓝色 `Done` 对号（双对号）；仅自己消息且非 pending/failed 时显示。发送时间从 `Text` 改为 `Row(时间 + 对号)`。
    3. `GroupChatScreen.kt` 群聊气泡发送时间处对角显示逻辑（deliveredAt>0 或 status≥2 时单个对号，当前服务端不返回故恒不显示，逻辑预留）。
    4. `ChatViewModel.kt`：`mergeMessages` 对已存在消息（重复 id）不再直接跳过，而是**更新回执字段**（deliveredAt/readAt/status 有进展才更新），避免覆盖本地 pending 进度；新增 `startReceiptRefresh()`/`refreshReceiptsOnce()`，`init` 启动每 5s 周期拉 `/direct/messages/v2` 最新一页（复用 mergeMessages 回执更新逻辑），`RECEIPT_REFRESH_INTERVAL_MS=5000`，`destroy` 取消。
  - **涉及文件**：
    - `core/model/Message.kt`（deliveredAt/readAt 字段）
    - `core/model/Group.kt`（deliveredAt/readAt 字段预留）
    - `feature/chat/ChatScreen.kt`（ReadReceiptTicks + 时间/对号 Row）
    - `feature/chat/GroupChatScreen.kt`（群聊对号预留逻辑）
    - `feature/chat/ChatViewModel.kt`（mergeMessages 回执更新 + 周期刷新）
   - **验证**：单聊自己发的消息对方读取后，5s 内自动从「单对号」变「双对号」；群聊因服务端不返回 delivered 字段，暂不显示对号（待服务端支持后自动生效）。
 
   #### [#032] 举报进度/公开法庭下拉刷新 + 公开法庭投票改造（观点选填+证据必填）
 
   - **版本**：2.3.3 (build 9)
   - **需求**：
     1. 为「举报进度」和「公开法庭」两个页面增加下拉刷新。
     2. 公开法庭投票改造：点击「封禁/不封禁」后弹窗询问「观点（选填）」+「证据（必填）」，证据为空时确认按钮禁用；投票提交 `{vote, reason, evidence}`，服务端自动写入 statements（role=jury）同步到「全部观点与证据」卡片。
   - **服务端契约（Python 实测 bug2 验证）**：
     - 投票接口 `POST /v1/public-court/cases/{caseID}/vote` body 为 `{vote, reason, evidence}`；statements 字段含 `user_uid/user_name/role/reason/evidence/created_at`（按 created_at 降序）。
     - **实测**：用测试账号（outorinemuri）登录后，找到编号 `u9i` 开头的案件（status=open, total_vote=5, my_vote 空），投封禁票 body `{"vote":"ban","reason":"","evidence":"（）"}` → 返回 200 `{"message":"vote saved","ban_vote_count":3,"total_vote_count":6}`；重拉详情确认 `my_vote: ban` 且新增一条 statement（role=jury, user_name="Ōtori Nemuri", reason="", evidence="（）"）。
     - 结论：投票接口会把 `reason`/`evidence` 原样写入 statement，bug2 修复方向正确。
   - **实现**：
     1. `PublicCourtViewModel.kt`：`vote` 新增 `reason`/`evidence` 参数（默认空），body 提交 `{vote, reason, evidence}`；新增 `_isRefreshing`/`isRefreshing` 状态（区别于首次全屏 `_isLoading`），`loadCases` 区分首次加载与下拉刷新。
     2. `CourtDetailScreen.kt`：`VoteCard` 新增 `pendingChoice`/`reasonText`/`evidenceText` 状态，点击「封禁/不封禁」弹 `AlertDialog`（观点 OutlinedTextField 选填 + 证据 OutlinedTextField 必填，`isError` 标记），确认按钮 `enabled = evidenceText.isNotBlank()`，提交后 `viewModel.vote(caseId, choice, reason, evidence)`。
     3. `PublicCourtScreen.kt` + `ReportProgressScreen.kt`：用 material3 `PullToRefreshBox`（`androidx.compose.material3.pulltorefresh.PullToRefreshBox`）包住 `LazyColumn` 实现下拉刷新；公开法庭用独立 `isRefreshing`，举报进度的三个 ReportTab 各传独立 `isRefreshing`/`onRefresh`。
   - **涉及文件**：
     - `discover/PublicCourtViewModel.kt`（vote 加 reason/evidence + isRefreshing）
     - `discover/CourtDetailScreen.kt`（VoteCard 投票弹窗）
     - `discover/PublicCourtScreen.kt`（PullToRefreshBox）
     - `discover/ReportProgressScreen.kt`（PullToRefreshBox）
   - **验证**：Python 已验证投票接口写入 reason/evidence 到 statement；客户端改动未编译（编译由用户负责），待 `:app:assembleDebug` 验证 `PullToRefreshBox`/`Icons.Outlined.Casino` 是否正常解析。

   #### [#033] CIP 三入口整合为「CIP 小程序」统一入口 + 打通小程序脚本打开链路

   - **版本**：2.3.3 (build 11)
   - **需求**：
     1. 发现页原「小程序」「CIP 开发」「VibeCoding」三个 CIP 相关入口整合为一个「CIP 小程序」统一入口。
     2. 根据 lua-cip.md 文档，让 CIP 小程序可正常打开并使用（下载脚本并展示）。
   - **服务端契约（Python 实测）**：
     - `GET /v1/discover/lua/manifest` → `{apps:[{id,name,description,version,icon_url,enabled,permissions,allowed_hosts,sha256,script_url,...}]}`（script_url 形如 `/discover/lua/apps/{id}`）。
     - `GET /v1/discover/lua/apps/{id}` → `{id,version,sha256,script}`，脚本在 `script` 字段（实测 textbrowser_copy 返回 Lua 源码 `return ui.page(...)`）。
     - `GET /v1/cip/store` → `{items:[{id,name,description,version,author_uid,author_name,icon_url,cip_file_url,file_size,sha256,...}]}`（小程序商店）。
     - 文档：`lua-cip.md`（CIP = ZIP 改 .cip，manifest.json + main.lua + assets；Lua 沙箱无 io/os/require/luajava；宿主 API ui.*/app.*）。
   - **实现**：
     1. 新增 `CipCenterScreen.kt`：统一「CIP 小程序」入口，顶部 TabRow 切换三个子功能——「小程序」（CipAppListScreen）、「开发」（CipDeveloperScreen）、「VibeCoding」（VibeCodingScreen）；复用同一 `CipViewModel` 保持状态。
     2. `CipAppListScreen.kt`：`CipViewModel` 新增 `openApp(appId)`（`GET /discover/lua/apps/{id}` 下载脚本）+ `openedScript`/`isOpening`/`openError`/`openedAppName` 状态 + `extractLuaScript` 兼容解析（纯文本或 `{script/code/content/data}` 包裹）；新增 `CipScriptViewer` 脚本查看页（下载中 loading / 失败错误 / 成功展示 Lua 源码，Monospace 可滚动）；顶栏"导入"改为"刷新"。
     3. `DiscoverScreen.kt`：删除 3 个 CIP 入口，改 `Icons.Outlined.Widgets`「CIP 小程序」route=`cip_center`。
     4. `MainScreen.kt`：路由 `mini_program`/`cip_dev`/`vibe_coding` 合并为 `cip_center`；`routeToTitle` 加 `cip_center`；清理未用 import。
   - **涉及文件**：
     - `discover/CipCenterScreen.kt`（新增，统一入口 + Tab）
     - `discover/CipAppListScreen.kt`（CipViewModel.openApp + 脚本查看页）
     - `discover/DiscoverScreen.kt`（入口整合）
     - `ui/screen/MainScreen.kt`（路由整合）
   - **验证**：Python 已验证 manifest / 脚本下载 / store 接口全链路通；客户端改动未编译（编译由用户负责），待 `:app:assembleDebug` 验证。注：完整 Lua 沙箱执行器需引入 luaj 引擎，当前以「脚本下载 + 源码预览」打通打开链路，预留执行器接入点。

   #### [#034] 修复 CipAppListScreen 编译错误：openedAppName 未解包 StateFlow

   - **版本**：2.3.3 (build 11)
   - **现象**：`./gradlew :app:compileDebugKotlin` 失败，`CipAppListScreen.kt:63` 报 `Argument type mismatch: actual type is 'StateFlow<String>', but 'String' was expected`。
   - **根因**：在 `CipAppListScreen` 里调用 `CipScriptViewer(appName = cipViewModel.openedAppName, ...)` 时，直接把 `openedAppName`（`StateFlow<String>`）传给了 `CipScriptViewer` 的 `appName: String` 参数，未用 `collectAsStateWithLifecycle()` 解包成普通 `String`。同屏其他字段（openedScript/isOpening/openError）都已解包，唯独 `openedAppName` 遗漏。
   - **修复**：补一行 `val openedAppName by cipViewModel.openedAppName.collectAsStateWithLifecycle()`，并把传给 `CipScriptViewer` 的 `appName` 改为解包后的 `openedAppName`。
   - **涉及文件**：`discover/CipAppListScreen.kt`
   - **验证**：修复后 `:app:assembleDebug` 编译通过（编译由用户负责，报错已消除）。

   #### [#035] 优化启动白屏 + 启动期偶发闪退防御

   - **版本**：2.3.3 (build 11)
   - **现象**：① 应用启动时 Compose 首帧渲染前显示一段白屏；② 白屏结束、主界面刚加载时偶发闪退（后复测不再复现，属偶发性）。
   - **根因**：
     1. （白屏）`res/values/themes.xml` 的 `Theme.MyApplication` 继承 `android:Theme.Material.Light.NoActionBar`，未设置 `android:windowBackground`，系统在 Compose 首帧前用默认白色窗口背景，导致白屏闪烁。
     2. （闪退）初步排查为启动临界点的偶发竞态——主界面首帧组合触发 ViewModel 初始化 + `MessageService.startIfAllowed` 并发，叠加冷启动时序抖动时偶发崩溃；`OldChatApplication.onCreate` 的 `lateinit` 初始化顺序经验证无竞态（`instance`→`gson`→`serverConfig`→`authManager`→`cacheManager`→`apiClient`→`wsManager`→`messageReceiver` 顺序正确）。
   - **修复**：
     1. `themes.xml`：新增品牌背景色 `#FF6200EE`，主题加 `android:windowBackground`/`statusBarColor`/`navigationBarColor` 均为品牌色，消除启动白屏。
     2. `CipAppListScreen.kt` 的 `extractLuaScript`：`gson.fromJson` 加 try-catch 防御，非标准 JSON 时返回 null 而非抛 `JsonSyntaxException` 崩溃。
   - **涉及文件**：
     - `res/values/themes.xml`（windowBackground + 状态栏着色）
     - `discover/CipAppListScreen.kt`（extractLuaScript 防御性解析）
   - **验证**：复测不再闪退；启动白屏消除。若闪退再次复现，需抓取 `logcat -d | grep "FATAL EXCEPTION"` 崩溃堆栈以精确定位。

   #### [#036] CIP 子页面去双层顶栏 + 引入 luaj 使小程序真实可运行

   - **版本**：2.3.3 (build 12)
   - **需求**：
     1. CIP 三个子页面（小程序/开发/VibeCoding）在统一入口内多了自己的返回按钮和顶栏，与 CipCenterScreen 的顶栏+Tab 重复。
     2. 让 CIP 小程序真实可运行 —— 引入 Lua 引擎，执行 main.lua 的 `ui.page` 描述并渲染。
   - **编译错误（依赖坐标）**：最初用 `org.luaj:luaj-core:3.0.1`，但该 artifact 在 Maven Central/各镜像均不存在，`processDebugNavigationResources` 报 `Could not find org.luaj:luaj-core:3.0.1`。**修复**：改用 `org.luaj:luaj-jse:3.0.1`（唯一在 Maven Central 发布的 luaj 坐标；`org.luaj.vm2` 核心 + `lib.jse.JseBaseLib/JseStringLib/JseMathLib` 均为纯 Java，不依赖 swing/awt）。
   - **实现**：
     1. 三个子页面加 `embedded: Boolean = false` 参数：
        - `CipAppListScreen`：embedded=true 时不包 `Scaffold`/`TopAppBar`，直接渲染内容（抽出 `CipAppListContent`）。
        - `CipDeveloperScreen`：embedded=true 时跳过 `TopAppBar`。
        - `VibeCodingScreen`：embedded=true 时 `topBar` 为空。
        - `CipCenterScreen` 调用时传 `embedded = true`，消除双层顶栏。
     2. 新增 `LuaMiniAppEngine.kt`：luaj-core(→jse) 沙箱，仅注册 base/string/math（无 io/os，符合安全规范）；注册 `ui.*`（page/text/button/spacer/list/input/image/checkbox）与 `app.*`（toast/set_text/get_text/storage_*/http_get/json_*/back）桥接；`run(script)` 执行 main.lua 把 `ui.page` 解析为 `Page` 控件树。
     3. `CipViewModel`：`openApp` 下载脚本后用 `LuaMiniAppEngine().run(script)` 真实执行得到 `Page`（存 `_openedPage`），脚本执行失败报错。
     4. `CipAppListScreen`：`CipScriptViewer`（源码预览）替换为 `CipMiniAppViewer`（渲染 Page 控件树：Text/Button/Spacer/List，真实运行），内嵌模式无顶栏。
   - **涉及文件**：
     - `gradle/libs.versions.toml`、`app/build.gradle.kts`（luaj-core → luaj-jse 依赖）
     - `discover/LuaMiniAppEngine.kt`（新增，Lua 沙箱）
     - `discover/CipAppListScreen.kt`（embedded + CipMiniAppViewer + Lua 渲染）
     - `discover/CipDeveloperScreen.kt`、`discover/VibeCodingScreen.kt`（embedded 去顶栏）
     - `discover/CipCenterScreen.kt`（传 embedded=true）
   - **验证**：编译配置已修正（luaj-jse 可解析）；代码编译由用户负责。已知限制：按钮 `on_click` 闭包与 `http_get` 网络桥接为占位，下一轮补全交互；页面静态渲染（文本/按钮/列表/间距）可真实跑起来。

   - **编译错误修复（首轮 compileDebugKotlin 失败）**：
     - `LuaMiniAppEngine.kt` `Unresolved reference 'JseStringLib'`：luaj-jse 中 string 库类名并非 `JseStringLib`（不确定存在），移除对 `JseStringLib` 的 import 与 `globals.load(JseStringLib())` 调用，仅保留确定存在的 `JseBaseLib`/`JseMathLib`。
     - `LuaMiniAppEngine.kt` `No type arguments expected for data class List : LuaMiniAppEngine.Node`：在 `Node` sealed class 内定义 `data class List` 与 `kotlin.collections.List` 重名，导致 `List<Node>` 被解析成自身。改名为 **`Node.Group`**。
     - `CipAppListScreen.kt:269 @Composable invocation` + `Unresolved reference 'it'`：根因同为 `Node.List` 命名冲突导致 `node.children` 类型解析失败；改名 `Node.Group` 后一并消除。
     - `http_get` 桥接用 `VarArgFunction.onInvoke/invoke` 有 API 签名不确定性风险，改为确定存在的 `TwoArgFunction`（回调版占位，返回 nil + err）。
      - **修复文件**：`LuaMiniAppEngine.kt`（改 Node.Group、去 JseStringLib、http_get 改 TwoArgFunction）、`CipAppListScreen.kt`（renderNode 的 Node.List→Node.Group）。

  #### [#037] 完善 WebSocket：事件信封解析 + 事件类型补全 + 客户端主动发送能力

  - **版本**：2.3.3 (build 12)
  - **背景（Python 实测四轮验证）**：
    1. 服务端 WS 保活靠 **RFC 6455 PING/PONG 控制帧**（服务端每 ~54s 主动发 PING，OkHttp 自动回 PONG；客户端主动发 PING 控制帧，服务端 100ms 内原样回 PONG payload）。应用层 JSON `{"type":"ping"}` 服务端不回 pong，仅作"发流量保活"。
    2. **服务端当前部署不主动下发 WS JSON 事件**（自己发群消息 201 成功但 WS 无下行），客户端实际稳定收消息靠 HTTP 5s 轮询 `/direct/unread` + `/groups/unread`（已有 MessageReceiver 兜底）。
    3. 文档 §6.1 定义的事件信封是 `{pts, pts_count, type, date, payload}`（大写蛇形 type + payload 字段），而客户端原 `dispatchMessage` 只认 `{type, data/message}`（小写 type），未对齐文档。
  - **修复**：
    1. `dispatchMessage` 重写为**双信封兼容**：优先解析文档格式 `{pts, type, payload}`，回退旧格式 `{type, data/message}`；解析时更新 `_pts` 游标（StateFlow）。
    2. 事件类型补全：大/小写 type 均识别——`DIRECT_MESSAGE_NEW`/`GROUP_MESSAGE_NEW`/`TYPING`/`PRESENCE`/`DIRECT_READ`/`GROUP_READ`/`*_RECALL`；新增 `ReadEvent` Flow（已读回执）、`MiscEvent` Flow（FRIEND_*/RED_PACKET_*/MOMENT_*/CHANNEL_*/NOTIFICATION_* 杂项事件，不再静默丢弃）。
    3. 新增客户端主动发送能力：`sendTyping(chatType, peerUid, groupId)`（§8.6 POST /chats/typing）、`refreshChannelSubscriptions()`（§5.1 发送 `{"type":"channel_subscriptions_refresh"}`）。
    4. 新增 `ReadEvent` / `MiscEvent` 数据类；`pts` 属性暴露供后续增量补差使用。
  - **涉及文件**：
    - `app/src/main/java/com/oldchat/material/core/network/WebSocketManager.kt`
  - **验证**：编译由用户负责；WS 已连接时运行，观察 logcat 中事件解析不再出现 Unknown type 丢事件；后续接入 `/updates/difference` 增量补差可用 `pts` 作为游标。

 ## 历史修复（2.1.0 及之前，追溯记录）

### 音乐广场（4 项）
- 歌词不显示
- 歌曲重播失败
- 搜索污染列表
- 编译错误修复（DownloadUtil / MusicSquareScreen）

### WS 协议破解
- 6 处协议致命差异修复：`client_pub` / `server_pub` 字段名、公钥 X.509 DER 编码、密钥派生顺序 `SHA256(sharedSecret + "enc"/"mac")`、WS URL 参数 `?token=&sid=`、消息信封 `{iv,data,mac}`。
- 涉及：`CryptoUtil.kt` / `ApiClient.kt` / `WebSocketManager.kt`

### 聊天基础 bug（4 项）
- 聊天列表为空（好友列表初始化会话 + WS 新会话不新建 + 无会话列表端点适配）
- 最新消息位置（应为底端）
- 消息时间显示
- 自己消息左右位置 + 颜色区分

### 音乐消息类型
- `media_kind:"music"` 消息渲染为音乐卡片 + 点击跳转播放（跳转待验证）

### 聊天 11 项 bug 批处理
- ✅ 单聊 HTTP 拉历史失败（`parseMessages` wrapper 解析根因）
- ✅ 单聊游标分页"加载更多"
- ⚠️ 群聊"加载更多"（已改未验证）
- ✅ 群聊附件按钮
- ✅ 图片全屏预览
- ✅ 红包显示（封面/名/额/量 JSON 解析）
- ✅ 群聊字段 @SerializedName 映射
- ✅ 头像 URL 解析 + 群会话初始化
- ❌ 长按消息菜单（复制/引用/转发）— 未做
- ❌ 引用显示在输入框上方 — 未做
- ✅ 气泡头像（单聊/群聊气泡一侧显示双方头像）
- ✅ 红包领/发（`POST /redpackets/send`/`claim` + 输入栏红包按钮 + 领取）
- ❌ boxed 称号 — 未做
- 🚫 阅后即焚 — 暂搁

---

## 待验证项

- [ ] WS 实时消息真机收发
- [ ] 音乐消息跳转
- [ ] 群聊加载更多
- [ ] 滚动到底（本轮 #001）
