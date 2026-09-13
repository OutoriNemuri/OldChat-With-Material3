package com.oldchat.material.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * CIP MiniApp screen — app list + launch.
 *
 * Architecture mirrors §10.2:
 * - LuaAppStore.getApps for the app registry
 * - LuaAppSyncManager.sync for remote updates
 * - LuaMiniAppActivity for sandboxed execution
 *
 * This screen focuses on the app list/launcher UI.
 *
 * @see client-guide.md §10, §18.10 (Lua manifest API)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CipAppListScreen(
    onBack: () -> Unit = {},
    onLaunchApp: (appId: String) -> Unit = {},
    embedded: Boolean = false,
    cipViewModel: CipViewModel = viewModel()
) {
    val apps by cipViewModel.apps.collectAsStateWithLifecycle()
    val isLoading by cipViewModel.isLoading.collectAsStateWithLifecycle()
    // 执行脚本后得到的页面模型（真实渲染）
    val openedPage by cipViewModel.openedPage.collectAsStateWithLifecycle()
    val isOpening by cipViewModel.isOpening.collectAsStateWithLifecycle()
    val openError by cipViewModel.openError.collectAsStateWithLifecycle()
    val openedAppName by cipViewModel.openedAppName.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { cipViewModel.loadApps() }

    // 有打开的小程序页面时，进入小程序运行视图（代替列表）
    if (openedPage != null || isOpening || openError != null) {
        CipMiniAppViewer(
            appName = openedAppName,
            page = openedPage,
            isLoading = isOpening,
            error = openError,
            embedded = embedded,
            onClose = { cipViewModel.closeApp() },
            // BUG-11：把控件点击转成 Lua 回调调用，并把新页面写回
            onNodeClick = { nodeId -> cipViewModel.onNodeClick(nodeId) }
        )
        return
    }

    if (embedded) {
        // 内嵌模式：不显示自己的 Scaffold/TopBar（由 CipCenterScreen 提供顶栏 + Tab）
        Box(Modifier.fillMaxSize()) {
            CipAppListContent(
                apps = apps,
                isLoading = isLoading,
                onLaunchApp = onLaunchApp,
                onRefresh = { cipViewModel.loadApps() }
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CIP 小程序") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { cipViewModel.loadApps() }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            CipAppListContent(
                apps = apps,
                isLoading = isLoading,
                onLaunchApp = onLaunchApp,
                onRefresh = { cipViewModel.loadApps() }
            )
        }
    }
}

@Composable
private fun CipAppListContent(
    apps: List<CipAppInfo>,
    isLoading: Boolean,
    onLaunchApp: (String) -> Unit,
    onRefresh: () -> Unit
) {
    if (isLoading && apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂无小程序", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(apps, key = { it.id }) { app ->
                CipAppItem(
                    app = app,
                    onClick = { onLaunchApp(app.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

/**
 * 小程序运行视图：用 Lua 引擎执行脚本后，将 ui.page 的控件树渲染为 Compose UI。
 * embedded=true 时不显示自己的顶栏（由 CipCenterScreen 提供）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CipMiniAppViewer(
    appName: String,
    page: LuaMiniAppEngine.Page?,
    isLoading: Boolean,
    error: String?,
    embedded: Boolean,
    onClose: () -> Unit,
    onNodeClick: (String) -> Unit = {}
) {
    if (embedded) {
        // 内嵌模式：无自己的顶栏，直接渲染内容
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("打开失败：$error", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onClose) { Text("返回") }
                }
            }
            page != null -> renderPage(page, onNodeClick)
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(appName.ifEmpty { "小程序" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, "返回列表")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                error != null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("打开失败：$error", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onClose) { Text("返回") }
                }
                page != null -> renderPage(page, onNodeClick)
            }
        }
    }
}

/** 渲染 ui.page 控件树。 */
@Composable
private fun renderPage(page: LuaMiniAppEngine.Page, onNodeClick: (String) -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        if (page.title.isNotEmpty()) {
            Text(page.title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
        }
        page.children.forEach { node ->
            renderNode(node, onNodeClick)
        }
    }
}

@Composable
private fun renderNode(node: LuaMiniAppEngine.Node, onNodeClick: (String) -> Unit = {}) {
    when (node) {
        is LuaMiniAppEngine.Node.Text -> {
            Text(
                node.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = node.size.sp
                ),
                color = node.color?.let { parseHexColor(it) } ?: MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
        }
        is LuaMiniAppEngine.Node.Button -> {
            // BUG-11：on_click 已桥接 —— 点击后调用 Lua 回调并按新的 set_text 重解析页面
            Button(onClick = { node.id?.let(onNodeClick) }) {
                Text(node.text)
            }
            Spacer(Modifier.height(8.dp))
        }
        is LuaMiniAppEngine.Node.Spacer -> {
            Spacer(Modifier.height(node.height.dp))
        }
        is LuaMiniAppEngine.Node.Group -> {
            Column(Modifier.padding(start = 8.dp)) {
                node.children.forEach { renderNode(it, onNodeClick) }
            }
        }
    }
}

/** 解析 #RRGGBB 颜色字符串为 Compose Color（失败返回 null）。 */
@Composable
private fun parseHexColor(hex: String): androidx.compose.ui.graphics.Color? {
    val clean = hex.removePrefix("#")
    return try {
        val v = clean.toLong(16)
        androidx.compose.ui.graphics.Color(0xFF000000L or v)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun CipAppItem(
    app: CipAppInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon placeholder
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        app.name.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium)
                if (app.description.isNotEmpty()) {
                    Text(app.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("v${app.version}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Spacer(Modifier.width(8.dp))
                    if (app.permissions.isNotEmpty()) {
                        Text("${app.permissions.size} 权限", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            Icon(Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * CIP mini-app view model — loads app registry from server.
 * Mirrors LuaAppSyncManager.sync from §10.
 */
class CipViewModel : ViewModel() {

    private val _apps = MutableStateFlow<List<CipAppInfo>>(emptyList())
    val apps: StateFlow<List<CipAppInfo>> = _apps.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 打开小程序脚本的状态
    private val _openedScript = MutableStateFlow<String?>(null)
    val openedScript: StateFlow<String?> = _openedScript.asStateFlow()

    // 执行脚本后得到的页面模型（真实渲染）
    // BUG-11：单个小程序一个常驻引擎实例（on_click 回调必须与页面同寿命）
    private var engine: LuaMiniAppEngine? = null
    private var activeEngine: LuaMiniAppEngine? = null

    /** 控件点击 → 调用 Lua on_click → 用新的 set_text 值重绘。 */
    fun onNodeClick(nodeId: String) {
        val engineRef = activeEngine ?: return
        viewModelScope.launch {
            try {
                engineRef.invokeClick(nodeId)?.let { _openedPage.value = it }
            } catch (e: Exception) {
                _openError.value = "操作失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private val _openedPage = MutableStateFlow<LuaMiniAppEngine.Page?>(null)
    val openedPage: StateFlow<LuaMiniAppEngine.Page?> = _openedPage.asStateFlow()

    private val _openedAppName = MutableStateFlow("")
    val openedAppName: StateFlow<String> = _openedAppName.asStateFlow()

    private val _isOpening = MutableStateFlow(false)
    val isOpening: StateFlow<Boolean> = _isOpening.asStateFlow()

    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    fun loadApps() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val app = OldChatApplication.instance
                val result = app.apiClient.get("/discover/lua/manifest", emptyMap())
                result.fold(
                    onSuccess = { json ->
                        val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                        val apps = map?.get("apps") as? List<Map<*, *>> ?: emptyList()
                        _apps.value = apps.mapNotNull { CipAppInfo.fromMap(it) }
                    },
                    onFailure = { }
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 打开小程序：下载脚本（GET /v1/discover/lua/apps/{id}），打通「打开并使用」的数据链路。
     * 服务端返回格式可能是纯脚本文本，也可能是 {script/script_url/...} 包裹，
     * 这里做兼容解析。
     */
    fun openApp(appId: String) {
        val target = _apps.value.firstOrNull { it.id == appId }
        viewModelScope.launch {
            _openedAppName.value = target?.name ?: appId
            _openError.value = null
            _openedScript.value = null
            _openedPage.value = null
            _isOpening.value = true
            try {
                val app = OldChatApplication.instance
                val result = app.apiClient.get("/discover/lua/apps/$appId", emptyMap())
                result.fold(
                    onSuccess = { body ->
                        val script = extractLuaScript(app, body)
                        if (script.isNullOrBlank()) {
                            _openError.value = "未获取到脚本内容"
                        } else {
                            _openedScript.value = script
                            // BUG-11：保留同一个引擎实例（原来每次新建，
                            // on_click 回调注册在旧实例上 → 点击永远没反应），
                            // 并把同服务器 GET 桥接给 app.http_get。
                            engine?.destroy()
                            engine = LuaMiniAppEngine(
                                context = app,
                                appId = appId,
                                sameServerGet = { path ->
                                    // 在 Lua 线程上同步等待，宿主注入登录令牌
                                    runCatching {
                                        kotlinx.coroutines.runBlocking {
                                            app.apiClient.get(path, emptyMap()).getOrNull()
                                        }
                                    }.getOrNull()
                                }
                            ).also { activeEngine = it }
                            try {
                                _openedPage.value = activeEngine?.run(script)
                            } catch (e: Exception) {
                                _openError.value = "脚本执行失败：${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    },
                    onFailure = { e ->
                        _openError.value = e.message ?: "下载失败"
                    }
                )
            } finally {
                _isOpening.value = false
            }
        }
    }

    fun closeApp() {
        _openedScript.value = null
        _openedPage.value = null
        _openedAppName.value = ""
        _openError.value = null
        // BUG-11：释放引擎（含延时任务线程）
        activeEngine?.destroy()
        activeEngine = null
    }

    /**
     * 兼容解析脚本下载响应：优先纯文本脚本；其次 {script}、{script_url}+文本、{code}/{content}/{data}。
     */
    private fun extractLuaScript(app: OldChatApplication, body: String): String? {
        if (body.isBlank()) return null
        // 纯脚本文本（以 Lua 特征开头，或无 JSON 结构）
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return body
        }
        // JSON 包裹（防御性解析：非标准 JSON 时返回 null 而非抛异常崩溃）
        val map = try {
            app.gson.fromJson(body, Map::class.java) as? Map<*, *>
        } catch (_: Exception) {
            null
        } ?: return null
        for (key in listOf("script", "code", "content", "data", "main_lua")) {
            val v = map[key]
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }
}

data class CipAppInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val iconUrl: String,
    val permissions: List<String>,
    val enabled: Boolean
) {
    companion object {
        fun fromMap(map: Map<*, *>): CipAppInfo? {
            val id = map["id"]?.toString() ?: return null
            return CipAppInfo(
                id = id,
                name = map["name"]?.toString() ?: id,
                description = map["description"]?.toString() ?: "",
                version = map["version"]?.toString() ?: "1.0.0",
                iconUrl = map["icon_url"]?.toString() ?: "",
                permissions = (map["permissions"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                enabled = map["enabled"] as? Boolean ?: true
            )
        }
    }
}
