package com.oldchat.material.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.MessageReceiver
import kotlinx.coroutines.launch

/**
 * 设置 → 通知（子界面）
 *
 * 内容：消息通知总开关 / 通知声音 / 通知震动 / 消息接收方式（WS 优先、仅 WS、仅 HTTP）。
 * 声音与震动在总开关关闭时置灰不可调（与通知层的实际行为一致）。
 */
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val preferences = remember { OldChatApplication.instance.cacheManager.preferences }

    val notifEnabled by preferences.notificationsEnabled.collectAsState(initial = true)
    val notifSound by preferences.notificationSound.collectAsState(initial = true)
    val notifVibration by preferences.notificationVibration.collectAsState(initial = true)
    val receiveMode by preferences.messageReceiveMode.collectAsState(initial = "ws_priority")

    var showReceiveModeDialog by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "通知", onBack = onBack) {
        SectionHeader("新消息通知")
        SettingsToggleRow(
            icon = Icons.Filled.Notifications,
            title = "消息通知",
            checked = notifEnabled,
            onToggle = { scope.launch { preferences.setNotificationsEnabled(it) } }
        )
        SettingsToggleRow(
            icon = Icons.Filled.Notifications,
            title = "通知声音",
            checked = notifSound,
            enabled = notifEnabled,
            onToggle = { scope.launch { preferences.setNotificationSound(it) } }
        )
        SettingsToggleRow(
            icon = Icons.Filled.Notifications,
            title = "通知震动",
            checked = notifVibration,
            enabled = notifEnabled,
            onToggle = { scope.launch { preferences.setNotificationVibration(it) } }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "正在查看的会话不会弹通知；已结束的加密通话不会重复提醒。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionHeader("接收方式")
        SettingsRow(
            Icons.Filled.Wifi,
            "消息接收方式",
            subtitle = MessageReceiver.Mode.fromKey(receiveMode).label,
            onClick = { showReceiveModeDialog = true }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "默认「WebSocket 优先」：WS 实时推送为主，断线时用增量轮询兜底（连接时 15s、" +
                "断开时 5s，进入会话/回前台会立即补拉一次）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showReceiveModeDialog) {
        val modes = MessageReceiver.Mode.entries
        var selected by remember { mutableStateOf(receiveMode) }
        AlertDialog(
            onDismissRequest = { showReceiveModeDialog = false },
            title = { Text("消息接收方式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "选择接收消息的方式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    modes.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = mode.key }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected == mode.key,
                                onClick = { selected = mode.key }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    modeDescription(mode),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { preferences.setMessageReceiveMode(selected) }
                    showReceiveModeDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showReceiveModeDialog = false }) { Text("取消") }
            }
        )
    }
}
