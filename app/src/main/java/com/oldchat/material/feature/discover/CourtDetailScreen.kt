package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 公开法庭详情页：多卡片布局。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtDetailScreen(
    caseId: String,
    onBack: () -> Unit,
    viewModel: PublicCourtViewModel
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()

    LaunchedEffect(caseId) { viewModel.loadDetail(caseId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("案件详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        val d = detail
        if (d == null || d.case.id != caseId) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "overview") { CourtCaseCard(case = d.case, onDetail = {}) }
                item(key = "vote") {
                    VoteCard(case = d.case, myUid = myUid, onVote = { choice, reason, evidence -> viewModel.vote(caseId, choice, reason, evidence) })
                }
                item(key = "parties") { PartiesCard(case = d.case) }
                item(key = "statements") { StatementsCard(statements = d.statements) }
                item(key = "discussion") {
                    DiscussionCard(
                        discussions = d.discussions,
                        onPost = { text -> viewModel.postDiscussion(caseId, text) }
                    )
                }
                item(key = "admin_verdict") { AdminVerdictCard(case = d.case) }
                item(key = "merged") { MergedReportsCard(reports = d.mergedReports) }
            }
        }
    }
}

/** 卡片2：投票。点击封禁/不封禁后弹窗询问「观点(选填)+证据(必填)」。 */
@Composable
private fun VoteCard(case: CourtCase, myUid: String, onVote: (String, String, String) -> Unit) {
    val canVote = case.status.lowercase() != "withdrawn" && case.totalVoteCount < 10
    // 待提交的投票选择（封禁/不封禁）
    var pendingChoice by remember { mutableStateOf<String?>(null) }
    var reasonText by remember { mutableStateOf("") }
    var evidenceText by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("案件裁决投票", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (canVote) "请选择你的裁决（总票数满 10 后裁定）"
                else "本案件已无法投票（总票数满 10 或已撤销）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (case.myVote.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("我的投票：${verdictLabel(case.myVote)}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                if (case.myVoteReason.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("理由：${case.myVoteReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { pendingChoice = "ban"; reasonText = ""; evidenceText = "" },
                    enabled = canVote && case.myVote.isEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) { Text("封禁") }
                OutlinedButton(
                    onClick = { pendingChoice = "keep"; reasonText = ""; evidenceText = "" },
                    enabled = canVote && case.myVote.isEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("不封禁") }
            }
        }
    }

    // 投票确认对话框：观点（选填）+ 证据（必填）
    val choice = pendingChoice
    if (choice != null) {
        AlertDialog(
            onDismissRequest = { pendingChoice = null },
            title = { Text(if (choice == "ban") "投封禁票" else "投不封禁票") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        label = { Text("观点（选填）") },
                        placeholder = { Text("说说你的看法…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    OutlinedTextField(
                        value = evidenceText,
                        onValueChange = { evidenceText = it },
                        label = { Text("证据（必填）") },
                        placeholder = { Text("请填写证据") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        isError = evidenceText.isBlank()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (evidenceText.isNotBlank()) {
                            onVote(choice, reasonText.trim(), evidenceText.trim())
                            pendingChoice = null
                        }
                    },
                    enabled = evidenceText.isNotBlank()
                ) { Text("确认投票") }
            },
            dismissButton = {
                TextButton(onClick = { pendingChoice = null }) { Text("取消") }
            }
        )
    }
}

/** 卡片3：举报方/被举报方 理由/证据。 */
@Composable
private fun PartiesCard(case: CourtCase) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("双方陈述", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            PartySection("举报方", case.reporterName, case.reportReason, case.reportEvidence, MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            PartySection("被举报方", case.defendantName, case.defenseReason, case.defenseEvidence, MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PartySection(title: String, name: String, reason: String, evidence: String, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text("$title：$name", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        InfoRow("理由", reason)
        InfoRow("证据", evidence)
    }
}

/** 卡片4：全部观点与证据（statements）。 */
@Composable
private fun StatementsCard(statements: List<CourtStatement>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("全部观点与证据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (statements.isEmpty()) {
                Text("暂无观点", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    statements.forEach { StatementMiniCard(it) }
                }
            }
        }
    }
}

@Composable
private fun StatementMiniCard(st: CourtStatement) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            val roleLabel = if (st.role.lowercase() == "reporter") "举报人" else "陪审团"
            Text("${st.userName} · $roleLabel", style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            if (st.reason.isNotBlank()) {
                Text("观点：${st.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
            if (st.evidence.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("证据：${st.evidence}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 卡片5：案件讨论。 */
@Composable
private fun DiscussionCard(discussions: List<CourtDiscussion>, onPost: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("案件讨论", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (discussions.isEmpty()) {
                Text("暂无讨论", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discussions.forEach { d ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(d.userName, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                Text(d.content, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("补充你的观点…") },
                maxLines = 3
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { if (input.isNotBlank()) { onPost(input.trim()); input = "" } },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("提交观点") }
        }
    }
}

/** 卡片6：管理员裁决。 */
@Composable
private fun AdminVerdictCard(case: CourtCase) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("管理员裁决", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            InfoRow("裁决", verdictLabel(case.verdict))
            InfoRow("封禁时长", if (case.banHours > 0) "${case.banHours} 小时" else "无")
            InfoRow("备注", case.adminNote)
        }
    }
}

/** 卡片7：叠加举报记录。 */
@Composable
private fun MergedReportsCard(reports: List<CourtMergedReport>) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("叠加举报记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (reports.isEmpty()) {
                Text("无叠加举报", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reports.forEach { r ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.reporterName, style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(6.dp))
                                    Text("(${r.reporterUid})", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(r.reason, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(2.dp))
                                Text(formatCourtDate(r.createdAt), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }
        }
    }
}