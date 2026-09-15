package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.ServerConfig

/**
 * 设置 → 服务器（子界面）
 *
 * 内容：
 *   · 服务器线路单选（官方 v1 / 官方 v2 / 自定义）—— 官方切换立即生效并清会话
 *   · 自定义地址保存（未填版本段自动补 /v1）
 *   · 文件服务器（可选媒体主机根，留空=跟随登录服务器）
 *   · 当前生效地址一览（业务 / 认证·媒体）
 */
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val serverConfig = OldChatApplication.instance.serverConfig
    val authManager = OldChatApplication.instance.authManager

    var serverMode by remember { mutableStateOf(serverConfig.mode) }
    var customServerUrl by remember { mutableStateOf(serverConfig.customBaseUrl) }
    var filesServerUrl by remember { mutableStateOf(serverConfig.filesBaseUrl) }
    var showFilesServerDialog by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "服务器", onBack = onBack) {
        SectionHeader("服务器线路")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ServerModeSelector(
                mode = serverMode,
                customUrl = customServerUrl,
                onModeChange = { newMode ->
                    serverMode = newMode
                    if (newMode != ServerConfig.Mode.CUSTOM) {
                        serverConfig.saveSelection(newMode, customServerUrl)
                        // 令牌/会话绑定服务器，切换后立即失效
                        authManager.clearSession()
                    }
                },
                onCustomUrlChange = { customServerUrl = it }
            )
            if (serverMode == ServerConfig.Mode.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val normalized = serverConfig.normalizeCustomInput(customServerUrl)
                        customServerUrl = normalized
                        serverConfig.saveSelection(ServerConfig.Mode.CUSTOM, normalized)
                        authManager.clearSession()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存自定义地址")
                }
            }
        }

        SectionHeader("文件服务器")
        SettingsRow(
            Icons.Filled.FolderOpen,
            "媒体服务器",
            subtitle = filesServerUrl.ifBlank {
                "跟随登录服务器：${serverConfig.mediaHostBase()}"
            },
            onClick = { showFilesServerDialog = true }
        )

        SectionHeader("当前生效地址")
        CurrentEndpointRow("业务接口", serverConfig.businessBase())
        CurrentEndpointRow("认证 / WebSocket / 媒体", serverConfig.infraBase())
        CurrentEndpointRow("媒体下载根", serverConfig.resolveMediaBase())
        Spacer(Modifier.height(16.dp))
        Text(
            "说明：业务接口随所选版本（v1/v2）变化；认证、WebSocket 与媒体上传固定在 v1" +
                "（依据 2026-09-15 的 v2 全量测试结论）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(24.dp))
    }

    // 文件服务器编辑对话框
    if (showFilesServerDialog) {
        var url by remember { mutableStateOf(serverConfig.filesBaseUrl) }
        val normalizedPreview = serverConfig.normalizeFilesServerInput(url)
        AlertDialog(
            onDismissRequest = { showFilesServerDialog = false },
            title = { Text("文件服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "媒体/头像/音乐的下载线路。留空 = 跟随登录服务器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        label = { Text("媒体服务器根地址") },
                        placeholder = { Text("files.example.com") },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        supportingText = {
                            Text(
                                if (normalizedPreview.isEmpty()) "留空：媒体走登录服务器"
                                else "实际使用：$normalizedPreview",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "会自动补 https://、去掉尾部斜杠与多余的 /v1（媒体路径由客户端拼）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = serverConfig.normalizeFilesServerInput(url)
                    serverConfig.filesBaseUrl = normalized
                    filesServerUrl = normalized
                    showFilesServerDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        serverConfig.filesBaseUrl = ""
                        filesServerUrl = ""
                        showFilesServerDialog = false
                    }) { Text("清除") }
                    TextButton(onClick = { showFilesServerDialog = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun CurrentEndpointRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}
