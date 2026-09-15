package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication

/**
 * 设置 → 隐私与安全（子界面）
 *
 * 这里如实说明「什么被加密、什么没有」，而不是笼统写"已加密"：
 *   · 传输层：客户端↔服务器 ECDH + AES-CBC + HMAC（服务端可见内容）
 *   · 端到端：加密通话（PQC 握手 + AES-256-GCM），仅文本消息
 *   · 本地：消息缓存为明文（可在此页一键清理）
 */
@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    val serverConfig = OldChatApplication.instance.serverConfig

    SettingsSubScaffold(title = "隐私与安全", onBack = onBack) {
        SectionHeader("加密")
        PolicyBlock(
            icon = Icons.Filled.Lock,
            title = "加密通话（端到端）",
            body = "单聊里发起「加密通话」后，双方用 ML-KEM-768（不可用时降级 ECDH P-256）" +
                "协商 32 字节会话密钥，之后的消息以 AES-256-GCM 加密帧传输；" +
                "服务端只能看到密文。可在通话页核对双方密钥指纹是否一致。"
        )
        PolicyBlock(
            icon = Icons.Filled.Security,
            title = "普通消息（传输层）",
            body = "与服务器之间使用 ECDH(secp256r1) + AES-256-CBC + HMAC-SHA256 会话加密。" +
                "注意：这是传输层加密，消息在服务端是可见的 —— 它不是端到端加密。"
        )

        SectionHeader("本地数据")
        PolicyBlock(
            icon = Icons.Filled.Storage,
            title = "消息缓存",
            body = "为了秒开会话，最近若干条消息会以**明文**保存在本机（每个会话独立存储）。" +
                "可在「存储 → 缓存管理」里按分类或按会话清理。"
        )
        PolicyBlock(
            icon = Icons.Filled.Timer,
            title = "阅后即焚",
            body = "收到带 burn_after_seconds 的消息时，点击查看后开始倒计时，到期本地销毁正文，" +
                "并上报 /direct/burn/open 供服务端在双方都读过后删除。"
        )

        SectionHeader("服务器")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("当前连接", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(serverConfig.businessBase(), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "账号信息与消息内容由你连接的服务器处理；换服务器前请确认其可信度。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PolicyBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(
                icon, null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body.replace("**", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
