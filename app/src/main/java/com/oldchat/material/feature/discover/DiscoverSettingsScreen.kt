package com.oldchat.material.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.launch

/**
 * 发现页设置（BUG-08）。
 *
 * 这些偏好（新闻区 / OldView / 公开法庭开关）原本只存在于 DataStore 里、
 * 没有任何界面能改，也没有任何代码去读 —— 属于「写了不读」的死设置。
 * 现在这里提供入口，并由 DiscoverScreen / ChatsScreen 真正消费。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSettingsScreen(onBack: () -> Unit) {
    val preferences = OldChatApplication.instance.cacheManager.preferences
    val scope = rememberCoroutineScope()

    val showNews by preferences.showNewsSection.collectAsStateWithLifecycle(initialValue = true)
    val showOldView by preferences.showOldViewEntry.collectAsStateWithLifecycle(initialValue = true)
    val showPublicCourt by preferences.showPublicCourtEntry.collectAsStateWithLifecycle(initialValue = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发现页设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingSwitch(
                title = "首页/发现页新闻区",
                subtitle = "关闭后隐藏极简新闻入口",
                checked = showNews,
                onToggle = { scope.launch { preferences.setShowNews(it) } }
            )
            SettingSwitch(
                title = "OldView 入口",
                subtitle = "旧版时间线视图",
                checked = showOldView,
                onToggle = { scope.launch { preferences.setShowOldViewEntry(it) } }
            )
            SettingSwitch(
                title = "公开法庭入口",
                subtitle = "社区仲裁与公示",
                checked = showPublicCourt,
                onToggle = { scope.launch { preferences.setShowPublicCourtEntry(it) } }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "以上开关只影响发现页的入口显示，不会删除任何数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onToggle)
        }
    }
}
