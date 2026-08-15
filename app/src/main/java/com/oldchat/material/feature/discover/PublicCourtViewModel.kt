package com.oldchat.material.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---- 数据模型 ----

data class CourtCase(
    val id: String,
    val reporterUid: String,
    val reporterName: String,
    val reporterAvatar: String,
    val defendantUid: String,
    val defendantName: String,
    val defendantAvatar: String,
    val reportReason: String,
    val reportEvidence: String,
    val defenseReason: String,
    val defenseEvidence: String,
    val status: String,
    val verdict: String,
    val adminNote: String,
    val banHours: Int,
    val banVoteCount: Int,
    val keepVoteCount: Int,
    val totalVoteCount: Int,
    val myVote: String,
    val myVoteReason: String,
    val createdAt: Long
) {
    companion object {
        fun fromMap(m: Map<*, *>): CourtCase? {
            val id = m["id"]?.toString() ?: return null
            fun str(k: String) = m[k]?.toString() ?: ""
            fun int(k: String) = (m[k] as? Number)?.toInt() ?: 0
            fun long(k: String) = (m[k] as? Number)?.toLong() ?: 0L
            return CourtCase(
                id = id,
                reporterUid = str("reporter_uid"),
                reporterName = str("reporter_name"),
                reporterAvatar = str("reporter_avatar"),
                defendantUid = str("defendant_uid"),
                defendantName = str("defendant_name"),
                defendantAvatar = str("defendant_avatar"),
                reportReason = str("report_reason"),
                reportEvidence = str("report_evidence"),
                defenseReason = str("defense_reason"),
                defenseEvidence = str("defense_evidence"),
                status = str("status"),
                verdict = str("verdict"),
                adminNote = str("admin_note"),
                banHours = int("ban_hours"),
                banVoteCount = int("ban_vote_count"),
                keepVoteCount = int("keep_vote_count"),
                totalVoteCount = int("total_vote_count"),
                myVote = str("my_vote"),
                myVoteReason = str("my_vote_reason"),
                createdAt = long("created_at")
            )
        }
    }
}

data class CourtStatement(
    val userName: String,
    val role: String,
    val reason: String,
    val evidence: String
)

data class CourtDiscussion(
    val userName: String,
    val content: String
)

data class CourtMergedReport(
    val reporterUid: String,
    val reporterName: String,
    val reason: String,
    val createdAt: Long
)

data class CourtDetail(
    val case: CourtCase,
    val statements: List<CourtStatement>,
    val discussions: List<CourtDiscussion>,
    val mergedReports: List<CourtMergedReport>
)

// ---- ViewModel ----

class PublicCourtViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance

    private val _cases = MutableStateFlow<List<CourtCase>>(emptyList())
    val cases: StateFlow<List<CourtCase>> = _cases.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 下拉刷新中（区别于首次全屏 loading）
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _myUid = MutableStateFlow(app.authManager.myUid ?: "")
    val myUid: StateFlow<String> = _myUid.asStateFlow()

    private val _detail = MutableStateFlow<CourtDetail?>(null)
    val detail: StateFlow<CourtDetail?> = _detail.asStateFlow()

    fun loadCases() {
        viewModelScope.launch {
            val isFirst = _cases.value.isEmpty()
            if (isFirst) {
                // 本地缓存秒开：先读本地 JSON，有缓存立即渲染，不阻塞网络
                cachedCases()?.let { _cases.value = it }
                _isLoading.value = _cases.value.isEmpty()
            } else {
                // 下拉刷新：非首次，显示下拉刷新圈
                _isRefreshing.value = true
            }
            _error.value = null
            app.apiClient.get("/public-court/cases", mapOf("status" to "all", "limit" to "100")).fold(
                onSuccess = { json ->
                    val cases = parseCases(json)
                    _cases.value = cases
                    // 写本地缓存，供下次秒开（后台增量刷新，不每次全量）
                    cacheCases(json)
                    _isLoading.value = false
                    _isRefreshing.value = false
                },
                onFailure = { e ->
                    _error.value = e.message ?: "加载失败"
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
            )
        }
    }

    private val CACHE_KEY = "public_court_cases"

    /** 读本地缓存的案件列表。 */
    private fun cachedCases(): List<CourtCase>? {
        return try {
            val json = app.cacheManager.pageCache.read(CACHE_KEY) ?: return null
            val list = parseCases(json)
            list.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    /** 写案件列表到本地缓存（原始 JSON，含时间戳）。 */
    private fun cacheCases(json: String) {
        try {
            app.cacheManager.pageCache.write(CACHE_KEY, json)
        } catch (_: Exception) { /* ignore */ }
    }

    fun loadDetail(caseId: String) {
        viewModelScope.launch {
            app.apiClient.get("/public-court/cases/$caseId").fold(
                onSuccess = { json -> _detail.value = parseDetail(json) },
                onFailure = { /* ignore */ }
            )
        }
    }

    fun vote(caseId: String, choice: String, reason: String = "", evidence: String = "") {
        viewModelScope.launch {
            val body = app.gson.toJson(
                mapOf(
                    "vote" to choice,
                    "reason" to reason,
                    "evidence" to evidence
                )
            )
            app.apiClient.post("/public-court/cases/$caseId/vote", body).fold(
                onSuccess = { loadDetail(caseId) },
                onFailure = { /* ignore */ }
            )
        }
    }

    fun postDiscussion(caseId: String, text: String) {
        viewModelScope.launch {
            val body = app.gson.toJson(mapOf("body" to text))
            app.apiClient.post("/public-court/cases/$caseId/discussion", body).fold(
                onSuccess = { loadDetail(caseId) },
                onFailure = { /* ignore */ }
            )
        }
    }

    private fun parseCases(json: String): List<CourtCase> {
        val map = try {
            app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        } catch (_: Exception) { null } ?: return emptyList()
        val list = map["cases"] as? List<*> ?: return emptyList()
        return list.filterIsInstance<Map<String, Any?>>().mapNotNull { CourtCase.fromMap(it) }
    }

    private fun parseDetail(json: String): CourtDetail {
        val placeholder = CourtCase(
            id = "", reporterUid = "", reporterName = "", reporterAvatar = "",
            defendantUid = "", defendantName = "", defendantAvatar = "",
            reportReason = "", reportEvidence = "", defenseReason = "", defenseEvidence = "",
            status = "", verdict = "", adminNote = "", banHours = 0,
            banVoteCount = 0, keepVoteCount = 0, totalVoteCount = 0,
            myVote = "", myVoteReason = "", createdAt = 0L
        )
        val map = try {
            app.gson.fromJson(json, Map::class.java) as? Map<*, *>
        } catch (_: Exception) { null } ?: return CourtDetail(placeholder, emptyList(), emptyList(), emptyList())

        val caseMap = map["case"] as? Map<*, *>
        val case = caseMap?.let { CourtCase.fromMap(it) } ?: placeholder

        val statements = (map["statements"] as? List<*> ?: emptyList<Any?>())
            .filterIsInstance<Map<String, Any?>>().map { st ->
                CourtStatement(
                    userName = st["user_name"]?.toString() ?: "",
                    role = st["role"]?.toString() ?: "jury",
                    reason = st["reason"]?.toString() ?: "",
                    evidence = st["evidence"]?.toString() ?: ""
                )
            }

        val discussions = (map["discussions"] as? List<*> ?: emptyList<Any?>())
            .filterIsInstance<Map<String, Any?>>().map { d ->
                CourtDiscussion(
                    userName = d["user_name"]?.toString() ?: "",
                    content = (d["body"] ?: d["content"])?.toString() ?: ""
                )
            }

        val merged = (map["merged_reports"] as? List<*> ?: emptyList<Any?>())
            .filterIsInstance<Map<String, Any?>>().map { r ->
                CourtMergedReport(
                    reporterUid = r["reporter_uid"]?.toString() ?: "",
                    reporterName = r["reporter_name"]?.toString() ?: "",
                    reason = r["reason"]?.toString() ?: "",
                    createdAt = (r["created_at"] as? Number)?.toLong() ?: 0L
                )
            }

        return CourtDetail(case, statements, discussions, merged)
    }
}
