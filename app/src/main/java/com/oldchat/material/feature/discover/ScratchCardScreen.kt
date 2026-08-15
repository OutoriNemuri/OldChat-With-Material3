package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 刮刮乐 — 每日刮奖（发现页独立功能）。
 *
 * 服务端契约（实测）：
 *   GET  /me/scratch   -> { already_done, coin_balance, scratch_date, slots[], total_reward }
 *   POST /me/scratch   -> (body {}) 开奖，返回同上结构
 *
 * 开奖规则（服务端 §16.3 / api.md §4.7）：5 个独立槽位，各自按概率掷出
 *   谢谢惠顾 40% / 1旧币 30% / 5旧币 15% / 10旧币 10% / 20旧币 5%。
 *   每日仅限 1 次，重复调用返回已开奖结果。slots 为 int 数组（0=空/谢谢惠顾）。
 *
 * Note：客户端走 v1 明文接口（apiClient 的 base 为 /v1），字段名是 already_done
 * （v2 为 already_scratched，二者语义一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScratchCardScreen(
    onBack: () -> Unit = {},
    viewModel: ScratchCardViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("刮刮乐") },
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 当前旧币数
            BalanceHeader(coinBalance = state.coinBalance)

            Spacer(Modifier.height(28.dp))

            // 5 个刮奖槽位
            SlotsRow(
                slots = state.slots,
                scratched = state.alreadyDone,
                revealed = state.isRevealing
            )

            Spacer(Modifier.height(20.dp))

            // 今日获得
            if (state.alreadyDone) {
                Text(
                    "今日获得 ${state.totalReward} 旧币",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else {
                Text(
                    "今日尚未刮奖",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(28.dp))

            // 抽奖 / 明天再来 按钮
            ScratchButton(
                done = state.alreadyDone,
                loading = state.loading,
                enabled = !state.loading && !state.alreadyDone,
                onClick = { viewModel.scratch() }
            )

            Spacer(Modifier.weight(1f))

            // 概率公示
            ProbabilityNotice()
        }
    }
}

// ---- 区块组件 ----

@Composable
private fun BalanceHeader(coinBalance: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "当前旧币数",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$coinBalance",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SlotsRow(
    slots: List<Int>,
    scratched: Boolean,
    revealed: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // 固定 5 个槽位
        for (i in 0 until 5) {
            val value = slots.getOrNull(i) ?: 0
            val displayText = when {
                !scratched && !revealed -> "?"
                value > 0 -> "$value"
                else -> "0"
            }
            val bgColor = when {
                !scratched && !revealed -> MaterialTheme.colorScheme.surfaceVariant
                value > 0 -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            SlotBox(text = displayText, background = bgColor)
        }
    }
}

@Composable
private fun SlotBox(text: String, background: Color) {
    Surface(
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(14.dp),
        color = background
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ScratchButton(
    done: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
            Text("开奖中…")
        } else {
            Text(
                if (done) "明天再来" else "立即抽奖",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProbabilityNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                "概率公示",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "谢谢惠顾  40%",
                "1 旧币  30%",
                "5 旧币  15%",
                "10 旧币  10%",
                "20 旧币  5%"
            ).forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "每日限 1 次，5 个槽位独立开奖",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// ---- 数据模型 ----

data class ScratchUiState(
    val alreadyDone: Boolean = false,
    val scratchDate: String = "",
    val slots: List<Int> = emptyList(),
    val totalReward: Int = 0,
    val coinBalance: Int = 0,
    val loading: Boolean = false,
    val isRevealing: Boolean = false,
    val errorMessage: String? = null
)

class ScratchCardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ScratchUiState())
    val uiState: StateFlow<ScratchUiState> = _uiState.asStateFlow()

    private val app get() = OldChatApplication.instance

    /** 查询今日刮刮乐状态。 */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            try {
                val result = app.apiClient.get("/me/scratch")
                result.fold(
                    onSuccess = { json -> applyScratchResponse(json) },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(loading = false, errorMessage = e.message ?: "加载失败")
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(loading = false, errorMessage = "加载失败") }
            }
        }
    }

    /** 立即抽奖（POST /me/scratch，空 body）。 */
    fun scratch() {
        if (_uiState.value.loading || _uiState.value.alreadyDone) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, isRevealing = true) }
            try {
                val result = app.apiClient.post("/me/scratch", "{}")
                result.fold(
                    onSuccess = { json -> applyScratchResponse(json) },
                    onFailure = { e ->
                        _uiState.update {
                            it.copy(
                                loading = false,
                                isRevealing = false,
                                errorMessage = e.message ?: "抽奖失败"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(loading = false, isRevealing = false, errorMessage = "抽奖失败")
                }
            }
        }
    }

    private fun applyScratchResponse(json: String) {
        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        // 兼容 already_done（v1）与 already_scratched（v2）
        val done = (map?.get("already_done") as? Boolean)
            ?: (map?.get("already_scratched") as? Boolean)
            ?: false
        val slots = (map?.get("slots") as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            ?: emptyList()
        _uiState.update {
            it.copy(
                alreadyDone = done,
                scratchDate = map?.get("scratch_date")?.toString() ?: "",
                slots = slots,
                totalReward = (map?.get("total_reward") as? Number)?.toInt() ?: 0,
                coinBalance = (map?.get("coin_balance") as? Number)?.toInt() ?: 0,
                loading = false,
                isRevealing = false
            )
        }
    }

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}
