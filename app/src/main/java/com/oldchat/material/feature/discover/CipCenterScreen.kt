package com.oldchat.material.feature.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * CIP 小程序统一入口 —— 整合原「小程序」「CIP 开发」「VibeCoding」三个入口。
 *
 * 顶部 Tab 切换三个子功能：
 * 1. 小程序：CipAppListScreen（应用列表 + 打开脚本预览）
 * 2. 开发：CipDeveloperScreen（CIP IDE）
 * 3. VibeCoding：VibeCodingScreen（AI 辅助开发）
 *
 * 对应 client-guide.md §10（CIP 小程序与 VibeCoding）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CipCenterScreen(
    onBack: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        CipTab("小程序", Icons.Outlined.Apps),
        CipTab("开发", Icons.Outlined.Code),
        CipTab("VibeCoding", Icons.Outlined.AutoAwesome)
    )

    // 复用同一个 ViewModel，使得 tab 切换后小程序列表状态不丢失
    val cipViewModel: CipViewModel = viewModel()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("CIP 小程序") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(tab.label) },
                            icon = { Icon(tab.icon, null) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> CipAppListScreen(
                    onBack = onBack,
                    onLaunchApp = { appId -> cipViewModel.openApp(appId) },
                    embedded = true,
                    cipViewModel = cipViewModel
                )
                1 -> CipDeveloperScreen(onBack = onBack, embedded = true)
                2 -> VibeCodingScreen(onBack = onBack, embedded = true)
            }
        }
    }
}

private data class CipTab(val label: String, val icon: ImageVector)
