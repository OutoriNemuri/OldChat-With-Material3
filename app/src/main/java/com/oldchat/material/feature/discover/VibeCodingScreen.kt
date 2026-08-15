package com.oldchat.material.feature.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oldchat.material.OldChatApplication
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * VibeCoding AI pair-programming screen.
 *
 * Mirrors CipVibeCodingActivity from §10.4:
 * - OpenAI compatible POST /v1/chat/completions (via server proxy)
 * - Tool whitelist: read/write/edit/grep/run/test/run_test/tasklist/ask
 * - No shell, no Java reflection, no chat data
 * - Foreground service CipVibeBackgroundService for background execution
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeCodingScreen(
    onBack: () -> Unit = {},
    embedded: Boolean = false,
    vibeViewModel: VibeCodingViewModel = viewModel()
) {
    val messages by vibeViewModel.messages.collectAsStateWithLifecycle()
    val isProcessing: Boolean by vibeViewModel.isProcessing.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showConfigDialog by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("VibeCoding") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showConfigDialog = true }) {
                            Icon(Icons.Filled.Settings, "API 配置")
                        }
                        IconButton(onClick = { vibeViewModel.stopGeneration() }) {
                            Icon(Icons.Filled.Stop, "停止")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("描述你想要构建的功能…") },
                        maxLines = 3,
                        enabled = (!isProcessing),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                vibeViewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && (!isProcessing),
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Send, "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "VibeCoding AI 辅助开发",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "描述你的想法，AI 将生成 CIP 代码",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(24.dp))
                            // Tools chip display
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                vibeViewModel.tools.forEach { tool ->
                                    AssistChip(
                                        onClick = { },
                                        label = { Text(tool, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            items(items = messages, key = { it.id }) { msg ->
                VibeMessageBubble(msg)
            }
        }
    }

    // API configuration dialog
    if (showConfigDialog) {
        var selectedProvider by remember { mutableStateOf(vibeViewModel.aiProvider) }
        var keyText by remember { mutableStateOf(vibeViewModel.apiKey) }
        var baseUrlText by remember { mutableStateOf(vibeViewModel.apiBaseUrl) }
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("AI 服务配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "选择 VibeCoding 使用的 AI 服务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 单选框 1：OldChat AI（服务端内置）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedProvider = VibeAiProvider.OLDCHAT }
                    ) {
                        RadioButton(
                            selected = selectedProvider == VibeAiProvider.OLDCHAT,
                            onClick = { selectedProvider = VibeAiProvider.OLDCHAT }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("使用 OldChat AI", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "服务端内置 AI，无需配置 key",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 单选框 2：自定义 OpenAI 兼容 key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedProvider = VibeAiProvider.CUSTOM }
                    ) {
                        RadioButton(
                            selected = selectedProvider == VibeAiProvider.CUSTOM,
                            onClick = { selectedProvider = VibeAiProvider.CUSTOM }
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text("自己填入 OpenAI 兼容 API", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "使用第三方 OpenAI 兼容端点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 仅在选择自定义时显示输入框
                    if (selectedProvider == VibeAiProvider.CUSTOM) {
                        OutlinedTextField(
                            value = baseUrlText,
                            onValueChange = { baseUrlText = it },
                            label = { Text("API Base URL") },
                            placeholder = { Text("https://api.openai.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyText,
                            onValueChange = { keyText = it },
                            label = { Text("API Key") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vibeViewModel.saveApiConfig(keyText.trim(), baseUrlText.trim(), selectedProvider)
                    showConfigDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun VibeMessageBubble(message: VibeMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            ),
            color = if (message.isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Role label
                Text(
                    if (message.isUser) "你" else "AI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (message.isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = if (message.isCode) FontFamily.Monospace else FontFamily.Default
                    ),
                    color = if (message.isUser)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Text(
            if (message.isProcessing) "生成中…" else message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

// ---- VibeCoding ViewModel ----

/** AI 提供方选择：OldChat 内置 AI 或用户自填 OpenAI 兼容 key。 */
enum class VibeAiProvider { OLDCHAT, CUSTOM }

class VibeCodingViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<VibeMessage>>(emptyList())
    val messages: StateFlow<List<VibeMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    val tools = listOf("read", "write", "edit", "grep", "run", "test", "ask")
    private var generationId: Int = 0

    // OpenAI-compatible API configuration (persisted in SharedPreferences)
    private val prefs = OldChatApplication.instance
        .getSharedPreferences("vibe_coding", android.content.Context.MODE_PRIVATE)

    val apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
    val apiBaseUrl: String
        get() = prefs.getString("api_base_url", "") ?: ""

    /** 当前选择的 AI 提供方。默认 OldChat。 */
    val aiProvider: VibeAiProvider
        get() = if (prefs.getString("ai_provider", "oldchat") == "custom") VibeAiProvider.CUSTOM else VibeAiProvider.OLDCHAT

    fun saveApiConfig(key: String, baseUrl: String, provider: VibeAiProvider) {
        prefs.edit()
            .putString("api_key", key)
            .putString("api_base_url", baseUrl)
            .putString("ai_provider", if (provider == VibeAiProvider.CUSTOM) "custom" else "oldchat")
            .apply()
    }

    /**
     * CIP 小程序开发规范，作为 system prompt 注入，让 AI 严格按规范生成 CIP（manifest.json + main.lua）。
     * 内容对应 lua-cip.md。
     */
    private val cipSystemPrompt = """
你是一个 Oldchat CIP（Chat Integration Package）Lua 小程序开发助手。请严格按以下规范生成 CIP 代码。

# CIP 是什么
CIP 是一个 ZIP 文件，仅把扩展名改为 .cip。必须包含：
- manifest.json（必需）：应用信息、版本、权限
- main.lua（必需）：入口脚本，UTF-8，最大 512 KiB，必须 `return ui.page({...})`
- assets/（可选）：icon.png / banner.webp / data.json 等

# 安全限制
Lua 无 io、os、debug、package、require、dofile、loadfile、luajava，不能调用 Java 反射/Shell/任意文件。Android 能力必须在 manifest 声明。

# manifest.json 字段
- id：1~64 位，小写字母/数字/_/-，发布后不改
- name：发现页名称，最多 40 字符
- version：正整数
- enabled：true 才会下发
- order：数字越小越靠前
- permissions：只允许 network、network_external、storage、camera
- 可选 description、icon_url、allowed_hosts

# main.lua 入口
脚本必须 `return ui.page({ title = "...", children = { ... } })`。

# UI DSL
- ui.page({ title, children })
- ui.text({ id, text, size, color, center, margin })
- ui.button({ text, on_click })
- ui.image({ id, url, height, margin, on_click })
- ui.input({ id, hint, text, single_line, input_type, max_length, on_change, on_focus_lost, on_submit })
- ui.checkbox({ id, text, checked, on_change })
- ui.list({ children })
- ui.spacer({ height })
所有控件可用 margin；支持 id 的控件可被受控更新 API 修改。

# 宿主 app API
- app.toast(msg)
- app.get_text(id) / app.set_text(id, text) / app.append_text(id, text)
- app.set_hint(id, hint) / app.focus(id)
- app.get_checked(id) / app.set_checked(id, checked)
- app.set_image(id, url)
- app.set_visible(id, visible) / app.get_visible(id) / app.set_enabled(id, enabled)
- app.storage_get(key) / app.storage_set(key, value) / app.storage_remove(key) / app.storage_clear()  [需 storage 权限]
- app.json_decode(text) / app.json_encode(value)
- app.delay(ms, fn)  [最大 60 秒]
- app.http_get(path, cb)  [需 network，路径以 / 开头；或 network_external，http/https URL]
- app.camera(cb)  [需 camera]
- app.asset(path) / app.back()
回调统一为 function(body_or_result, err)，成功 err=nil。

# 生成要求
1. 先说明页面、事件、网络、权限需求。
2. 只申请实际使用的权限；纯展示页面 permissions 数组为空。
3. 生成合法 manifest.json。
4. 生成单入口 main.lua，必须 return ui.page(...)。
5. 所有异步回调同时处理成功和失败。
6. 不使用规范未列出的 Lua/Android API。

请用代码块分别输出 manifest.json 和 main.lua，并简要说明打包方式（zip 后改 .cip）。
""".trimIndent()

    private val chatHistory = mutableListOf<Pair<String, String>>() // (role, content)

    fun sendMessage(content: String) {
        generationId++
        val userMsg = VibeMessage(
            id = "user_${System.currentTimeMillis()}",
            content = content,
            isUser = true,
            generationId = generationId
        )
        val thinkingMsg = VibeMessage(
            id = "ai_thinking_${System.currentTimeMillis()}",
            content = "思考中…",
            isUser = false,
            isProcessing = true,
            generationId = generationId
        )
        _messages.update { it + listOf(userMsg, thinkingMsg) }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val app = OldChatApplication.instance

                // 组装 messages：system prompt（CIP 规范）+ 历史 + 当前用户输入
                val chatMessages = mutableListOf<Map<String, String>>()
                chatMessages.add(mapOf("role" to "system", "content" to cipSystemPrompt))
                chatHistory.forEach { (role, text) ->
                    chatMessages.add(mapOf("role" to role, "content" to text))
                }
                chatMessages.add(mapOf("role" to "user", "content" to content))

                val body = mapOf(
                    "model" to "gpt-3.5-turbo",
                    "messages" to chatMessages
                )
                val json = app.gson.toJson(body)

                // 根据选择的提供方决定调用方式。
                // OldChat AI：走服务端代理 /v1/ai/chat/completions（base 已含 /v1，故传 /ai/chat/completions）。
                // 自定义：用用户填的 OpenAI 兼容端点 + key 直连。
                val useCustom = aiProvider == VibeAiProvider.CUSTOM && apiKey.isNotEmpty() && apiBaseUrl.isNotEmpty()
                val result: Result<String> = if (useCustom) {
                    callOpenAiDirect(apiBaseUrl, apiKey, json)
                } else {
                    app.apiClient.post("/ai/chat/completions", json)
                }

                result.fold(
                    onSuccess = { response ->
                        val map = app.gson.fromJson(response, Map::class.java) as? Map<*, *>
                        // 兼容服务端错误（如 402 ai_quota_exhausted 装在 body 里返回 2xx 的情况）
                        val errCode = map?.get("code")?.toString()
                        val errMsg = map?.get("error")?.toString()
                        val isError = errCode != null && (errCode.contains("quota") || errCode.contains("exhausted") || errCode.contains("error"))
                        if (isError) {
                            val aiMsg = VibeMessage(
                                id = "ai_${System.currentTimeMillis()}",
                                content = "AI 服务返回错误：${errMsg ?: errCode}",
                                isUser = false,
                                generationId = generationId
                            )
                            _messages.update { current -> current.filter { it.id != thinkingMsg.id } + aiMsg }
                        } else {
                            val choices = map?.get("choices") as? List<Map<*, *>>
                            val reply = choices?.firstOrNull()?.get("message") as? Map<*, *>
                            val replyContent = reply?.get("content")?.toString() ?: "无响应"

                            // 记录对话历史，实现多轮上下文
                            chatHistory.add("user" to content)
                            chatHistory.add("assistant" to replyContent)
                            if (chatHistory.size > 20) { // 限制历史长度，避免 token 超限
                                val excess = chatHistory.size - 20
                                repeat(excess) { chatHistory.removeAt(0) }
                            }

                            val aiMsg = VibeMessage(
                                id = "ai_${System.currentTimeMillis()}",
                                content = replyContent,
                                isUser = false,
                                isCode = replyContent.contains("```") || replyContent.contains("function "),
                                generationId = generationId
                            )
                            _messages.update { current ->
                                current.filter { it.id != thinkingMsg.id } + aiMsg
                            }
                        }
                    },
                    onFailure = {
                        val errorMsg = VibeMessage(
                            id = "ai_${System.currentTimeMillis()}",
                            content = "请求失败：${it.message ?: "未知错误"}",
                            isUser = false,
                            generationId = generationId
                        )
                        _messages.update { current ->
                            current.filter { it.id != thinkingMsg.id } + errorMsg
                        }
                    }
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Call a user-configured OpenAI-compatible endpoint directly with their API key.
     */
    private suspend fun callOpenAiDirect(baseUrl: String, key: String, json: String): Result<String> {
        return try {
            val url = baseUrl.trimEnd('/') + "/chat/completions"
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaType(), json))
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(response.body?.string() ?: "")
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun stopGeneration() {
        generationId++ // Invalidate current generation
        _isProcessing.value = false
        _messages.update { current ->
            current.map {
                if (it.isProcessing) it.copy(content = "已停止", isProcessing = false) else it
            }
        }
    }
}

data class VibeMessage(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val isCode: Boolean = false,
    val isProcessing: Boolean = false,
    val timestamp: String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date()),
    val generationId: Int = 0
)
