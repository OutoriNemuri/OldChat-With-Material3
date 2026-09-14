package com.oldchat.material.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oldchat.material.OldChatApplication
import com.oldchat.material.core.network.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Authentication ViewModel.
 * Manages login form state, validation, API calls, and navigation.
 */
class AuthViewModel : ViewModel() {

    private val app get() = OldChatApplication.instance
    private val apiClient get() = app.apiClient
    private val authManager get() = app.authManager

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // ---- Login Form ----

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value, errorMessage = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null) }
    }

    fun onPrivacyAcceptedChanged(accepted: Boolean) {
        _uiState.update { it.copy(privacyAccepted = accepted) }
    }

    /**
     * Perform login.
     */
    fun login() {
        val state = _uiState.value
        if (!validateLoginForm(state)) return

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val result = apiClient.login(state.username, state.password)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, loginSuccess = true)
                    }
                    // Start message receiving (WS + HTTP polling coordinated by MessageReceiver)
                    app.messageReceiver.start()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "登录失败，请重试"
                        )
                    }
                }
            )
        }
    }

    // ---- Server Config ----
    //
    // 模式（官方 v1 / 官方 v2 / 自定义）与自定义地址都存在 ServerConfig 里，
    // 这里只做「读进 UI 状态」与「从 UI 状态保存」，不再自己拼 URL。

    fun onServerModeChanged(mode: ServerConfig.Mode) {
        _uiState.update { it.copy(serverMode = mode) }
    }

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(customServerUrl = value) }
    }

    fun saveServerSelection() {
        val state = _uiState.value
        val normalized = app.serverConfig.normalizeCustomInput(state.customServerUrl)
        if (state.serverMode == ServerConfig.Mode.CUSTOM && normalized.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请填写自定义服务器地址") }
            return
        }
        app.serverConfig.saveSelection(state.serverMode, normalized)
        // 切服务器后旧会话全部失效（会话与令牌都绑定服务器）
        authManager.clearSession()
        _uiState.update {
            it.copy(showServerConfig = false, errorMessage = null, customServerUrl = normalized)
        }
    }

    // ---- Navigation ----

    /**
     * Reset login-success flag so that re-displaying LoginScreen (e.g. after logout)
     * does not immediately re-trigger onLoginSuccess.
     */
    fun resetForDisplay() {
        _uiState.update { it.copy(loginSuccess = false) }
    }

    fun toggleServerConfig() {
        _uiState.update {
            it.copy(
                showServerConfig = !it.showServerConfig,
                // 打开面板时把当前配置读进 UI（之前是读 baseUrl，现在读模式 + 自定义地址）
                serverMode = app.serverConfig.mode,
                customServerUrl = app.serverConfig.customBaseUrl
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ---- Validation ----

    private fun validateLoginForm(state: AuthUiState): Boolean {
        if (state.username.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入用户名") }
            return false
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入密码") }
            return false
        }
        if (!state.privacyAccepted) {
            _uiState.update { it.copy(errorMessage = "请先同意隐私协议") }
            return false
        }
        return true
    }
}

/**
 * Authentication UI state.
 */
data class AuthUiState(
    // Mode
    val showServerConfig: Boolean = false,

    /** 服务器选择：官方 v1 / 官方 v2 / 自定义 */
    val serverMode: ServerConfig.Mode = ServerConfig.Mode.OFFICIAL_V1,

    // Login fields
    val username: String = "",
    val password: String = "",

    // Common
    val privacyAccepted: Boolean = false,
    val customServerUrl: String = "",

    // State
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loginSuccess: Boolean = false
)
