package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication

/** 公开法庭列表页。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicCourtScreen(
    onBack: () -> Unit = {},
    viewModel: PublicCourtViewModel = viewModel()
) {
    val cases by viewModel.cases.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedCaseId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadCases() }

    val selected = selectedCaseId
    if (selected != null) {
        CourtDetailScreen(
            caseId = selected,
            onBack = { selectedCaseId = null },
            viewModel = viewModel
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("公开法庭") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        when {
            isLoading && cases.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null && cases.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.loadCases() }) { Text("重试") }
                    }
                }
            }
            cases.isEmpty() -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.loadCases() },
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无案件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.loadCases() },
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(cases, key = { it.id }) { case ->
                            CourtCaseCard(case = case, onDetail = { selectedCaseId = case.id })
                        }
                    }
                }
            }
        }
    }
}

/** 案件卡片（列表 + 详情卡片1共用）。 */
@Composable
fun CourtCaseCard(case: CourtCase, onDetail: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "案件 ${case.id}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                CourtStatusBadge(case)
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoteStat("最终票数", case.totalVoteCount, MaterialTheme.colorScheme.primary)
                VoteStat("封禁", case.banVoteCount, Color(0xFFE53935))
                VoteStat("不封禁", case.keepVoteCount, Color(0xFF2E7D32))
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    CourtAvatar(case.reporterAvatar, case.reporterName)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${case.reporterName} (${case.reporterUid})",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "对质",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    CourtAvatar(case.defendantAvatar, case.defendantName)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${case.defendantName} (${case.defendantUid})",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoRow("举报理由", case.reportReason)
            InfoRow("证据摘要", summarizeEvidence(case.reportEvidence))
            InfoRow("阶段结果", verdictLabel(case.verdict))

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                Spacer(Modifier.width(4.dp))
                Text(
                    "开庭时间：${formatCourtDate(case.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(onClick = onDetail, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("查看详情")
            }
        }
    }
}

@Composable
internal fun CourtAvatar(avatarUrl: String, name: String) {
    val resolved = remember(avatarUrl) {
        if (avatarUrl.isNotEmpty()) OldChatApplication.instance.serverConfig.resolveMediaUrl(avatarUrl) ?: "" else ""
    }
    if (resolved.isNotEmpty()) {
        AsyncImage(
            model = resolved, contentDescription = name,
            modifier = Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(1).ifEmpty { "?" }, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoteStat(label: String, value: Int, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.12f)) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(value.ifEmpty { "（无）" }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CourtStatusBadge(case: CourtCase) {
    val (label, color) = when {
        case.status.lowercase() == "withdrawn" -> "已撤销" to Color(0xFF9E9E9E)
        case.totalVoteCount >= 10 -> "待审核" to Color(0xFF1976D2)
        else -> "投票中" to Color(0xFFF57C00)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

internal fun verdictLabel(verdict: String): String = when (verdict.lowercase()) {
    "ban" -> "封禁（ban）"
    "keep" -> "不封禁（keep）"
    else -> verdict.ifEmpty { "未决" }
}

internal fun summarizeEvidence(evidence: String): String {
    if (evidence.isBlank()) return ""
    val firstLine = evidence.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
}

internal fun formatCourtDate(raw: Long): String {
    if (raw <= 0L) return "未知"
    return try {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(raw * 1000))
    } catch (_: Exception) { "未知" }
}