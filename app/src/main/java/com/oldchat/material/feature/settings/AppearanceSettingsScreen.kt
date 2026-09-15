package com.oldchat.material.feature.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.cache.DpiManager
import kotlinx.coroutines.launch

/**
 * 设置 → 外观（子界面）
 *
 * 内容：深色模式 / 动态取色 / DPI 缩放（字体与界面缩放预设）/ 首页是否显示新闻区。
 * 这些开关原来散落在设置页的长列表里，现在独立成页，改完即时生效（无需保存）。
 */
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val preferences = remember { OldChatApplication.instance.cacheManager.preferences }
    val dpiManager = remember { DpiManager(preferences) }

    val fontScale by dpiManager.fontScale.collectAsState(initial = 1.0f)
    val displayScale by dpiManager.displayScale.collectAsState(initial = 1.0f)
    val isDark by preferences.isDarkMode.collectAsState(initial = false)
    val useDynamicColor by preferences.useDynamicColor.collectAsState(initial = true)
    val showNews by preferences.showNewsSection.collectAsState(initial = true)
    var showDpiDialog by remember { mutableStateOf(false) }

    SettingsSubScaffold(title = "外观", onBack = onBack) {
        SectionHeader("主题")
        SettingsToggleRow(
            icon = Icons.Filled.DarkMode,
            title = "深色模式",
            checked = isDark,
            onToggle = { scope.launch { preferences.setDarkMode(it) } }
        )
        SettingsToggleRow(
            icon = Icons.Filled.Palette,
            title = "动态取色",
            checked = useDynamicColor,
            onToggle = { scope.launch { preferences.setDynamicColor(it) } }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "动态取色需要 Android 12（API 31）及以上；关闭后使用应用内置配色。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        SectionHeader("尺寸")
        SettingsRow(
            Icons.Filled.Language,
            "DPI 缩放",
            subtitle = "字体 ${(fontScale * 100).toInt()}% · 界面 ${(displayScale * 100).toInt()}%",
            onClick = { showDpiDialog = true }
        )

        SectionHeader("首页")
        SettingsToggleRow(
            icon = Icons.Filled.Article,
            title = "显示新闻区",
            checked = showNews,
            onToggle = { scope.launch { preferences.setShowNews(it) } }
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDpiDialog) {
        DpiAdjustDialog(
            fontScale = fontScale,
            displayScale = displayScale,
            onFontScaleChange = { scope.launch { dpiManager.setFontScale(it) } },
            onDisplayScaleChange = { scope.launch { dpiManager.setDisplayScale(it) } },
            onDismiss = { showDpiDialog = false }
        )
    }
}

// ---- DPI Adjust Dialog（自原 FullSettingsScreen 移入，行为不变） ----

@Composable
fun DpiAdjustDialog(
    fontScale: Float,
    displayScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onDisplayScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val fontPresets = listOf(0.75f, 0.875f, 1.0f, 1.15f, 1.25f, 1.5f)
    val displayPresets = listOf(0.75f, 0.875f, 1.0f, 1.15f, 1.25f, 1.5f)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("DPI 缩放") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
            ) {
                DpiScalePicker(
                    label = "字体缩放",
                    value = fontScale,
                    presets = fontPresets,
                    onChange = onFontScaleChange
                )
                DpiScalePicker(
                    label = "界面缩放",
                    value = displayScale,
                    presets = displayPresets,
                    onChange = onDisplayScaleChange
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("完成")
            }
        }
    )
}

@Composable
private fun DpiScalePicker(
    label: String,
    value: Float,
    presets: List<Float>,
    onChange: (Float) -> Unit
) {
    androidx.compose.foundation.layout.Column {
        androidx.compose.material3.Text(
            "$label：${(value * 100).toInt()}%",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { preset ->
                androidx.compose.material3.FilterChip(
                    selected = kotlin.math.abs(value - preset) < 0.01f,
                    onClick = { onChange(preset) },
                    label = { androidx.compose.material3.Text("${(preset * 100).toInt()}%") }
                )
            }
        }
    }
}
