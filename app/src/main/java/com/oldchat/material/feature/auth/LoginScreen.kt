package com.oldchat.material.feature.auth

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Login screen with Material You card design.
 * Mirrors LoginActivity from client-guide.md §2.2.
 *
 * Registration is not a built-in screen (the official guide defines no
 * register UI). The "register" action redirects to the web register page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    viewModel: AuthViewModel = viewModel()
) {

    // ALIGN-09：协议弹窗开关

    var showPolicyDialog by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // Reset stale login-success flag on display (prevents switching back to main
    // screen if the same AuthViewModel instance had a prior loginSuccess=true).
    LaunchedEffect(Unit) {
        viewModel.resetForDisplay()
    }

    // Navigate on login success
    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) {
            onLoginSuccess()
        }
    }

    // Show error in snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            // Animated content switch (only login vs server-config now)
            AnimatedContent(
                targetState = uiState.showServerConfig,
                transitionSpec = {
                    if (targetState) {
                        // Server config: slide from bottom
                        slideInVertically { it } + fadeIn() togetherWith
                                slideOutVertically { -it } + fadeOut()
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "auth_content"
            ) { showConfig ->
                when {
                    showConfig -> ServerConfigCard(viewModel, uiState)
                    else -> LoginCard(viewModel, uiState, onOpenRegister = {
                        openRegister(context)
                    })
                }
            }
        }
    }
}

/**
 * Open the web registration page in the system browser.
 */
private fun openRegister(context: android.content.Context) {
    try {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            // ALIGN-25：不再硬编码域名。注册已迁到网页端（服务端返回 register_url），
            // 这里按当前服务器配置拼接，换服务器/自建部署后依然正确。
            android.net.Uri.parse(
                OldChatApplication.instance.serverConfig.resolveRegisterUrl()
            )
        )
        context.startActivity(intent)
    } catch (_: Exception) {
        // No browser available — ignore.
    }
}

// ---- Login Card ----

@Composable
private fun LoginCard(
    viewModel: AuthViewModel,
    uiState: AuthUiState,
    onOpenRegister: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Logo area
            Surface(
                modifier = Modifier.size(72.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text = "OldChat Material",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "登录你的账号",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Username field
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChanged,
                label = { Text("用户名") },
                leadingIcon = {
                    Icon(Icons.Filled.Person, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !uiState.isLoading
            )

            // Password field
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChanged,
                label = { Text("密码") },
                leadingIcon = {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.Visibility
                            else Icons.Outlined.VisibilityOff,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                        )
                    }
                },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                enabled = !uiState.isLoading
            )

            // Privacy policy
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.privacyAccepted,
                    onCheckedChange = viewModel::onPrivacyAcceptedChanged,
                    enabled = !uiState.isLoading
                )
                Text(
                    text = "我已阅读并同意 ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    // ALIGN-09：原来这里是 TODO（点了没反应），而勾选框又是强制门禁，
                    // 用户「同意」的到底是什么完全没有出处。现在打开协议弹窗。
                    onClick = { showPolicyDialog = true },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "隐私协议",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ALIGN-09：隐私协议弹窗（本地摘要 + 跳转官网完整条款）
            if (showPolicyDialog) {
                AlertDialog(
                    onDismissRequest = { showPolicyDialog = false },
                    title = { Text("隐私协议与用户条款") },
                    text = {
                        Column {
                            Text(
                                "使用本客户端即表示你理解并同意：\n\n" +
                                "· 账号信息与消息内容由你连接的 Oldchat 服务器处理，客户端仅在本地缓存必要的会话数据；\n" +
                                "· 客户端不会在未经你操作的情况下上传通讯录/相册等隐私数据；\n" +
                                "· 本地缓存可通过「设置 → 清除缓存」随时删除。\n\n" +
                                "完整条款以服务方页面为准。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showPolicyDialog = false
                            runCatching {
                                val ctx = context
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            OldChatApplication.instance.serverConfig
                                                .resolveRegisterUrl().removeSuffix("/register")
                                        )
                                    )
                                )
                            }
                        }) { Text("查看完整条款") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPolicyDialog = false }) { Text("关闭") }
                    }
                )
            }

            // Login button
            Button(
                onClick = viewModel::login,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !uiState.isLoading,
                shape = MaterialTheme.shapes.medium
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("登录", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Register link — redirects to the web register page
            TextButton(
                onClick = onOpenRegister,
                enabled = !uiState.isLoading
            ) {
                Text("没有账号？立即注册")
            }

            // Server config link
            TextButton(
                onClick = viewModel::toggleServerConfig,
                enabled = !uiState.isLoading
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "服务器设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- Server Config Card ----

@Composable
private fun ServerConfigCard(viewModel: AuthViewModel, uiState: AuthUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "服务器设置",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "自定义 OldChat Material 服务器地址\n留空使用默认服务器",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = uiState.customServerUrl,
                onValueChange = viewModel::onServerUrlChanged,
                label = { Text("服务器地址") },
                placeholder = { Text(com.oldchat.material.OldChatApplication.instance.serverConfig.baseUrl) },
                leadingIcon = {
                    Icon(Icons.Filled.Link, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::toggleServerConfig,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("返回")
                }
                Button(
                    onClick = viewModel::saveServerUrl,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

