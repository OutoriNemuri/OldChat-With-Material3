# 未在本次批次内处理的项（说明与计划）

## BUG-24：ChatViewModel / GroupChatViewModel 大量重复 → 抽 `ChatCore`

**为什么没动**：这是一次跨 1500+ 行的结构性重构（两份 ViewModel 各 ~780 行），
本次修复批次没有编译与运行验证手段（无 Android SDK / 无模拟器），
一旦签名或状态语义对不上，破坏面是「整个聊天功能」而不是单个页面。
按 TODO-BUGFIX.md 的估算是 1.5 天，应当单独开分支、用真实构建验证后再合。

**已确认的现状（复核数据，修正名单里的旧数字）**：
- ChatViewModel.kt = 777 行，GroupChatViewModel.kt = 783 行（名单写的 850/700 不准）
- 连续 ≥4 行完全相同的代码块：25 块 / 165 行，占 ChatViewModel 非空代码行的 **21%**
  （名单写的 34% 偏高，但方向正确——重复确实集中在
   消息合并排序、回执刷新、解析、发送失败重试、连接状态处理这几段）

**建议的重构步骤（低风险顺序）**：
1. 先抽「纯函数 + 常量」到 `core/chat/ChatSupport.kt`：
   `parseMessages` / `findAnchorForSeq` / `extractHasMore` / `extractLong` /
   `sortMessages`（三级排序）/ `mergeById`。这一步不改任何状态，可逐步替换。
2. 抽 `abstract class ChatCoreViewModel<TMsg>`：把
   就绪状态、WS 订阅、回执刷新（ALIGN-02 的合并触发器）、
   发送失败重试、`destroy()` 生命周期收敛到基类，
   差异点用 `open` 方法（`loadHistory` / `sendBody` / `chatKey`）下放。
3. 最后把两个子类里的 UI 状态名对齐（`_messages` / `_groupMessages` 等），
   这一步会改动屏幕代码，务必单独提交。

## 其它已知未完成（清单里属「功能补齐」，不是缺陷）

| 项 | 现状 |
| --- | --- |
| ALIGN-12 的 News/Chats/Folded 分段 | 已做 220ms 合并刷新；分段 UI 未做（属 §7 的首屏信息架构） |
| ALIGN-19 `forward` 合并转发 | 解析层（extractDisplayText/extractPreviewText）已覆盖；「子消息列表 + 查看全部」卡片未做 |
| P3 功能补齐（CIP 包体系/红包详情/设备管理/改密码/聊天搜索/OldView/新闻页/音乐排行） | 未动，属新功能开发 |
