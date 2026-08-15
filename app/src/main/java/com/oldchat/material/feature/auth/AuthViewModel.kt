package com.oldchat.material.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oldchat.material.OldChatApplication
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

    fun onServerUrlChanged(value: String) {
        _uiState.update { it.copy(customServerUrl = value) }
    }

    fun saveServerUrl() {
        val url = _uiState.value.customServerUrl.trim()
        app.serverConfig.baseUrl = url
        _uiState.update { it.copy(showServerConfig = false, errorMessage = null) }
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
                customServerUrl = app.serverConfig.baseUrl
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
