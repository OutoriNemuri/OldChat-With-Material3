package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.oldchat.material.core.network.ServerConfig

/**
 * 服务器选择控件（登录页与设置页共用）：
 *
 *   ○ 官方（v1）    {OFFICIAL_HOST}/{API_V1}
 *   ○ 官方（v2）    {OFFICIAL_HOST}/{API_V2}
 *   ○ 自定义        [ 地址输入框 ]
 *
 * 官方地址**不在这里写死** —— 由 [ServerConfig.OFFICIAL_HOST] 与
 * [ServerConfig.API_V1] / [ServerConfig.API_V2] 拼接展示，保证全工程只有一处定义。
 */
@Composable
fun ServerModeSelector(
    mode: ServerConfig.Mode,
    customUrl: String,
    onModeChange: (ServerConfig.Mode) -> Unit,
    onCustomUrlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 是否显示「当前生效地址」提示行 */
    showEffectiveUrl: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        ServerConfig.Mode.entries.forEach { option ->
            val label = option.label
            val detail = when (option) {
                ServerConfig.Mode.OFFICIAL_V1 ->
                    "${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V1}　（业务接口走 v1）"
                ServerConfig.Mode.OFFICIAL_V2 ->
                    "${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V2}　（业务 v2，认证/媒体仍走 v1）"
                ServerConfig.Mode.CUSTOM -> "自建或私有服务器"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == option, onClick = { onModeChange(option) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == option, onClick = { onModeChange(option) })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (mode == ServerConfig.Mode.CUSTOM) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = customUrl,
                onValueChange = onCustomUrlChange,
                label = { Text("服务器地址") },
                placeholder = {
                    Text("${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V1}")
                },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                supportingText = {
                    Text(
                        "需包含版本段（以 /${ServerConfig.API_V1} 或 /${ServerConfig.API_V2} 结尾）；" +
                            "不填则默认补 /${ServerConfig.API_V1}",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }

        if (showEffectiveUrl) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.height(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        "业务接口：${effectiveBusinessUrl(mode, customUrl)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "认证 / WebSocket / 媒体：${effectiveInfraUrl(mode, customUrl)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 预览用：与运行时 [ServerConfig.businessBase] 同规则（未落盘，仅展示） */
private fun effectiveBusinessUrl(mode: ServerConfig.Mode, customUrl: String): String = when (mode) {
    ServerConfig.Mode.OFFICIAL_V1 -> "${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V1}"
    ServerConfig.Mode.OFFICIAL_V2 -> "${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V2}"
    ServerConfig.Mode.CUSTOM -> customUrl.trim().trimEnd('/').ifBlank {
        "${ServerConfig.OFFICIAL_HOST}/${ServerConfig.API_V1}"
    }
}

/** 预览用：认证 / WS / 媒体固定 v1（与 [ServerConfig.infraBase] 同规则） */
private fun effectiveInfraUrl(mode: ServerConfig.Mode, customUrl: String): String {
    val base = effectiveBusinessUrl(mode, customUrl)
    return if (base.endsWith("/${ServerConfig.API_V2}")) {
        base.removeSuffix("/${ServerConfig.API_V2}") + "/${ServerConfig.API_V1}"
    } else {
        base
    }
}
