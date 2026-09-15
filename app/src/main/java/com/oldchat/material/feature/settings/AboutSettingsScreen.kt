package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.BuildConfig
import com.oldchat.material.OldChatApplication

/**
 * 设置 → 关于（子界面）
 *
 * 内容：版本信息（取自 BuildConfig，单一来源）、开源许可、检查更新、问题反馈。
 */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showFeedbackDialog by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "关于", onBack = onBack) {
        SectionHeader("应用")
        SettingsRow(
            Icons.Filled.Info,
            "关于 OldChat Material",
            subtitle = "版本 ${BuildConfig.VERSION_NAME} · Material You",
            onClick = { showAboutDialog = true }
        )
        SettingsRow(
            Icons.Filled.Description,
            "开源许可",
            subtitle = "查看使用的开源库",
            onClick = { showLicensesDialog = true }
        )
        SettingsRow(
            Icons.Filled.SystemUpdate,
            "检查更新",
            subtitle = "24 小时间隔自动检查",
            onClick = { /* 已自动检查；此处为状态说明入口 */ }
        )
        SettingsRow(
            Icons.Filled.Feedback,
            "问题反馈",
            subtitle = "反馈使用中遇到的问题",
            onClick = { showFeedbackDialog = true }
        )

        SectionHeader("当前线路")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                OldChatApplication.instance.serverConfig.displayUrl(),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "API 版本 ${OldChatApplication.instance.serverConfig.apiVersionLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于 OldChat Material") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OldChat Material", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Material You 设计的第三方 OldChat 客户端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "传输层：ECDH + AES-CBC + HMAC；端到端：加密通话（ML-KEM-768 + AES-256-GCM）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("确定") }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "本项目使用以下开源库：\n\n• Jetpack Compose / Material 3\n• Ktor / OkHttp\n" +
                        "• Coil\n• Gson / kotlinx.serialization\n• Media3\n• BouncyCastle（ML-KEM）\n" +
                        "• LuaJ\n\n均遵循各自的开源许可证。\n\n特别致谢：\n" +
                        "• OldChat-For-Windows（MIT License）\n" +
                        "  https://github.com/Coloryi-MIAO/OldChat-For-Windows\n  加密会话协议参考实现。",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("确定") }
            }
        )
    }

    if (showFeedbackDialog) {
        AlertDialog(
            onDismissRequest = { showFeedbackDialog = false },
            title = { Text("问题反馈") },
            text = {
                Text(
                    "如遇问题，请在项目仓库提交 Issue，并附上复现步骤与设备/版本信息；" +
                        "崩溃类问题请一并附上 logcat（含 OldChat / MlKem768Kem 等 TAG 的日志会更快定位）。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showFeedbackDialog = false }) { Text("知道了") }
            }
        )
    }
}
