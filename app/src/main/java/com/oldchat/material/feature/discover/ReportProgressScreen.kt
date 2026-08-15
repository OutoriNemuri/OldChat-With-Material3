package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 举报进度页（发现 → 举报进度）。
 *
 * 三个大标签页：BUG反馈 / 用户举报 / 资源举报。
 * 每个标签页带「全部 / 仅看自己」筛选，卡片形式展示每一条：
 *   - 反馈人（uid）
 *   - 状态（待处理 / 处理中 / 已修复）
 *   - 举报内容
 *   - 时间
 *
 * 数据源（实测）：
 *   BUG反馈   : GET /reports/bug（全局，含 user_uid） + GET /me/bug-reports（我的，content/status）
 *   用户举报  : GET /reports/user（全局，含 reporter_uid/target_uid）+ GET /me/user-reports（我的）
 *   资源举报  : GET /me/resource-reports（我的；服务端无全局 GET 列表，/reports/resource 404）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportProgressScreen(
    onBack: () -> Unit = {},
    viewModel: ReportProgressViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadAll() }

    // 三个标签页，每个标签页独立记住「全部/仅看自己」
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("BUG 反馈", "用户举报", "资源举报")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("举报进度") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 大标签页
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> ReportTab(
                    scope = ReportScope.BUG,
                    items = state.bugReports,
                    myUid = state.myUid,
                    isLoading = state.isLoadingBug,
                    error = state.errorBug,
                    onRefresh = { viewModel.loadBugReports() },
                    onRetry = { viewModel.loadBugReports() }
                )
                1 -> ReportTab(
                    scope = ReportScope.USER,
                    items = state.userReports,
                    myUid = state.myUid,
                    isLoading = state.isLoadingUser,
                    error = state.errorUser,
                    onRefresh = { viewModel.loadUserReports() },
                    onRetry = { viewModel.loadUserReports() }
                )
                2 -> ReportTab(
                    scope = ReportScope.RESOURCE,
                    items = state.resourceReports,
                    myUid = state.myUid,
                    isLoading = state.isLoadingResource,
                    error = state.errorResource,
                    onRefresh = { viewModel.loadResourceReports() },
                    onRetry = { viewModel.loadResourceReports() }
                )
            }
        }
    }
}

/** 标签页类别，决定卡片文案（举报人/被举报人等）。 */
private enum class ReportScope { BUG, USER, RESOURCE }

/**
 * 单个标签页：筛选条（全部 / 仅看自己）+ 卡片列表。
 */
@Composable
private fun ReportTab(
    scope: ReportScope,
    items: List<ReportItem>,
    myUid: String,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit
) {
    var onlyMine by remember { mutableStateOf(false) }

    // 按「仅看自己」筛选
    val shown = remember(items, onlyMine, myUid) {
        if (onlyMine) items.filter { it.ownerUid == myUid } else items
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选条：全部 / 仅看自己
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "筛选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            FilterChip(
                selected = !onlyMine,
                onClick = { onlyMine = false },
                label = { Text("全部") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = onlyMine,
                onClick = { onlyMine = true },
                label = { Text("仅看自己") }
            )
        }

        when {
            isLoading && items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null && items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
            shown.isEmpty() -> {
                PullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无相关内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(shown, key = { "${scope}_${it.id}" }) { item ->
                            ReportCard(item = item, scope = scope)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单条举报卡片。
 */
@Composable
private fun ReportCard(item: ReportItem, scope: ReportScope) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部：反馈人 uid + 状态徽标
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val personLabel = when (scope) {
                    ReportScope.BUG -> "反馈人"
                    ReportScope.USER -> "举报人"
                    ReportScope.RESOURCE -> "举报人"
                }
                Text(
                    "$personLabel：${item.ownerUid.ifEmpty { "未知" }}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(item.status)
            }

            // 被举报对象（用户举报/资源举报有 target）
            if (item.targetUid.isNotBlank() && scope == ReportScope.USER) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "被举报人：${item.targetUid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // 举报内容
            Text(
                item.content.ifEmpty { "（无内容）" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))

            // 时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Schedule,
                    null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    item.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 状态徽标：待处理 / 处理中 / 已修复。
 * 服务端 status 实测值：open / pending / resolved / withdrawn 等。
 */
@Composable
private fun StatusBadge(rawStatus: String) {
    val (label, color) = mapStatus(rawStatus)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun mapStatus(raw: String): Pair<String, androidx.compose.ui.graphics.Color> {
    return when (raw.lowercase()) {
        "resolved", "keep", "ban" -> "已修复" to androidx.compose.ui.graphics.Color(0xFF2E7D32)
        "open", "pending" -> "待处理" to androidx.compose.ui.graphics.Color(0xFFF57C00)
        else -> "处理中" to androidx.compose.ui.graphics.Color(0xFF1976D2)
    }
}

// ---- 数据模型 ----

data class ReportItem(
    val id: String,
    val ownerUid: String,   // 反馈人/举报人 uid
    val targetUid: String,  // 被举报人（用户举报时有值）
    val content: String,    // 举报/反馈内容
    val status: String,     // 原始 status
    val timeText: String
)

// ---- ReportProgressViewModel ----

data class ReportProgressUiState(
    val myUid: String = "",
    val bugReports: List<ReportItem> = emptyList(),
    val userReports: List<ReportItem> = emptyList(),
    val resourceReports: List<ReportItem> = emptyList(),
    val isLoadingBug: Boolean = false,
    val isLoadingUser: Boolean = false,
    val isLoadingResource: Boolean = false,
    val errorBug: String? = null,
    val errorUser: String? = null,
    val errorResource: String? = null
)

class ReportProgressViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ReportProgressUiState())
    val uiState: StateFlow<ReportProgressUiState> = _uiState.asStateFlow()

    private val app get() = OldChatApplication.instance
    private val myUid get() = app.authManager.myUid ?: ""

    init {
        _uiState.update { it.copy(myUid = myUid) }
    }

    fun loadAll() {
        loadBugReports()
        loadUserReports()
        loadResourceReports()
    }

    fun loadBugReports() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBug = true, errorBug = null) }
            val all = mutableListOf<ReportItem>()
            var err: String? = null

            // 全局 bug 列表（含 user_uid）
            app.apiClient.get("/reports/bug").fold(
                onSuccess = { json -> all += parseBugReports(json, includeOwner = true) },
                onFailure = { e -> err = e.message ?: "加载失败" }
            )
            // 我的 bug 反馈（无 user_uid，owner 即自己）
            app.apiClient.get("/me/bug-reports").fold(
                onSuccess = { json -> all += parseBugReports(json, includeOwner = false) },
                onFailure = { e -> if (err == null) err = e.message ?: "加载失败" }
            )

            _uiState.update {
                it.copy(
                    isLoadingBug = false,
                    bugReports = all.distinctBy { r -> r.id },
                    errorBug = err
                )
            }
        }
    }

    fun loadUserReports() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingUser = true, errorUser = null) }
            val all = mutableListOf<ReportItem>()
            var err: String? = null

            // 全局用户举报（含 reporter_uid）
            app.apiClient.get("/reports/user").fold(
                onSuccess = { json -> all += parseUserReports(json, includeOwner = true) },
                onFailure = { e -> err = e.message ?: "加载失败" }
            )
            // 我的用户举报（owner 即自己）
            app.apiClient.get("/me/user-reports").fold(
                onSuccess = { json -> all += parseUserReports(json, includeOwner = false) },
                onFailure = { e -> if (err == null) err = e.message ?: "加载失败" }
            )

            _uiState.update {
                it.copy(
                    isLoadingUser = false,
                    userReports = all.distinctBy { r -> r.id },
                    errorUser = err
                )
            }
        }
    }

    fun loadResourceReports() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingResource = true, errorResource = null) }
            // 服务端无全局资源举报 GET 列表，仅我的资源举报。
            app.apiClient.get("/me/resource-reports").fold(
                onSuccess = { json ->
                    _uiState.update {
                        it.copy(
                            isLoadingResource = false,
                            resourceReports = parseResourceReports(json),
                            errorResource = null
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoadingResource = false, errorResource = e.message ?: "加载失败")
                    }
                }
            )
        }
    }

    // ---- 解析：BUG 反馈 ----
    private fun parseBugReports(json: String, includeOwner: Boolean): List<ReportItem> {
        val map = try {
            app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        } catch (_: Exception) { null } ?: return emptyList()
        val list = map["reports"] as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<*, *>>().mapNotNull { item ->
            val id = item["id"]?.toString() ?: return@mapNotNull null
            val owner = if (includeOwner) {
                (item["user_uid"] ?: item["reporter_uid"])?.toString() ?: ""
            } else {
                myUid
            }
            ReportItem(
                id = id,
                ownerUid = owner,
                targetUid = "",
                content = item["content"]?.toString() ?: "",
                status = item["status"]?.toString() ?: "",
                timeText = formatTime(item["created_at"])
            )
        }
    }

    // ---- 解析：用户举报 ----
    private fun parseUserReports(json: String, includeOwner: Boolean): List<ReportItem> {
        val map = try {
            app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        } catch (_: Exception) { null } ?: return emptyList()
        val list = map["reports"] as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<*, *>>().mapNotNull { item ->
            val id = item["id"]?.toString() ?: return@mapNotNull null
            val owner = if (includeOwner) {
                (item["reporter_uid"] ?: item["user_uid"])?.toString() ?: ""
            } else {
                myUid
            }
            ReportItem(
                id = id,
                ownerUid = owner,
                targetUid = item["target_uid"]?.toString() ?: "",
                content = item["reason"]?.toString() ?: "",
                status = item["status"]?.toString() ?: "",
                timeText = formatTime(item["created_at"])
            )
        }
    }

    // ---- 解析：资源举报 ----
    private fun parseResourceReports(json: String): List<ReportItem> {
        val map = try {
            app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        } catch (_: Exception) { null } ?: return emptyList()
        val list = map["reports"] as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<*, *>>().mapNotNull { item ->
            val id = item["id"]?.toString() ?: return@mapNotNull null
            ReportItem(
                id = id,
                ownerUid = (item["reporter_uid"] ?: item["user_uid"])?.toString() ?: myUid,
                targetUid = (item["target_uid"] ?: item["resource_id"])?.toString() ?: "",
                content = (item["reason"] ?: item["content"])?.toString() ?: "",
                status = item["status"]?.toString() ?: "",
                timeText = formatTime(item["created_at"])
            )
        }
    }

    /** 时间戳（Unix 秒）→ "yyyy-MM-dd HH:mm"。 */
    private fun formatTime(raw: Any?): String {
        val sec = (raw as? Number)?.toLong() ?: return ""
        if (sec <= 0L) return ""
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.format(java.util.Date(sec * 1000))
        } catch (_: Exception) { "" }
    }
}
