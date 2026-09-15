package com.oldchat.material.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缓存管理子页面（比设置页原来的单对话框更细分、更精准）。
 *
 * 结构：
 *   · 顶部：总占用 + 一键清空全部
 *   · 分类：消息历史（可展开 → **按会话**逐个清理）/ 会话列表 / 好友·群 / 图片 /
 *          语音·临时文件 / 页面数据 / 我的表情
 *   · 每个分类：占用大小 + 条目数 + 单独清理按钮
 *
 * 只清理可再生数据；登录态与用户配置不在此页范围内。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagerScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val app = OldChatApplication.instance
    val cacheManager = app.cacheManager
    val context = app.applicationContext

    var sections by remember { mutableStateOf<List<CacheManager.CacheSection>>(emptyList()) }
    var conversations by remember { mutableStateOf<List<CacheManager.ConversationCache>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var expandedMessages by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmSection by remember { mutableStateOf<CacheManager.CacheSection?>(null) }

    suspend fun reload() {
        loading = true
        val (s, c) = withContext(Dispatchers.IO) {
            cacheManager.listSections(context) to cacheManager.listConversationCaches()
        }
        sections = s
        conversations = c
        total = s.sumOf { it.bytes }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { confirmClearAll = true }) { Text("清空全部") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item(key = "summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "当前占用 ${formatBytes(total)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "缓存都可再生：清理后再次进入会话/页面会重新拉取，不影响登录态与设置。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (loading) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            items(sections, key = { it.key }) { section ->
                Column {
                    CacheSectionRow(
                        section = section,
                        expanded = section.key == "messages" && expandedMessages,
                        onToggleExpand = if (section.key == "messages") {
                            { expandedMessages = !expandedMessages }
                        } else null,
                        onClear = { confirmSection = section }
                    )
                    // 消息历史：展开后按会话逐个清理
                    if (section.key == "messages" && expandedMessages) {
                        if (conversations.isEmpty()) {
                            Text(
                                "（暂无会话级消息缓存）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp)
                            )
                        } else {
                            conversations.forEach { conv ->
                                ConversationRow(
                                    conv = conv,
                                    onClear = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                cacheManager.clearConversation(conv.key)
                                            }
                                            reload()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "tip") {
                Text(
                    "提示：「消息历史」是按会话存的最近若干条（用于秒开会话）；" +
                        "「图片缓存」可能较大，清理后图片会重新下载。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    // 单个分类清理确认
    confirmSection?.let { section ->
        AlertDialog(
            onDismissRequest = { confirmSection = null },
            title = { Text("清理「${section.title}」") },
            text = { Text("将释放约 ${formatBytes(section.bytes)}。清理后相关内容会重新拉取或重建。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSection = null
                    scope.launch {
                        withContext(Dispatchers.IO) { cacheManager.clearSection(context, section.key) }
                        reload()
                    }
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmSection = null }) { Text("取消") } }
        )
    }

    // 全部清空确认
    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("清空全部缓存") },
            text = {
                Text(
                    "将清理消息历史、会话列表、好友/群缓存、图片缓存、语音临时文件、页面数据与表情。" +
                        "登录状态与设置不受影响；清理后首次进入会稍慢。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) { cacheManager.clearAppCache(context) }
                        reload()
                    }
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CacheSectionRow(
    section: CacheManager.CacheSection,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(section.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(section.description)
                        append(" · ")
                        append(formatBytes(section.bytes))
                        if (section.itemCount >= 0) append(" · ${section.itemCount} 项")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onToggleExpand != null) {
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开按会话"
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "清理${section.title}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: CacheManager.ConversationCache,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 32.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    "${if (conv.type == "group") "群" else "单聊"} · ${conv.messages} 条 · ${formatBytes(conv.bytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "清理该会话消息缓存",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// formatBytes 由 SettingsCommon.kt 提供（避免同包重名）
