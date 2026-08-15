package com.oldchat.material.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.LaunchedEffect

/**
 * Edit profile screen.
 * Mirrors §7.4 (编辑资料) — modify nickname / signature via POST /me/profile,
 * and upload avatar via POST /me/avatar (multipart).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    onBack: () -> Unit = {},
    viewModel: EditProfileViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState() // MutableStateFlow-based, see below
    val snackbarHostState = remember { SnackbarHostState() }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.uploadAvatar(uri)
    }

    // Load current profile once on entry.
    LaunchedEffect(Unit) {
        viewModel.load()
    }

    // Show toast/feedback on save result.
    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            snackbarHostState.showSnackbar("保存成功")
            viewModel.consumeSaveResult()
            onBack()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("编辑资料") },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(modifier = Modifier.size(96.dp).clip(CircleShape).clickableAvatar({
                imagePicker.launch("image/*")
            })) {
                if (state.avatarUrl != null) {
                    AsyncImage(
                        model = state.avatarUrl,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Person, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (state.uploadingAvatar) {
                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { imagePicker.launch("image/*") }) {
                Text("更换头像")
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = state.nickname,
                onValueChange = viewModel::onNicknameChanged,
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.signature,
                onValueChange = viewModel::onSignatureChanged,
                label = { Text("个性签名") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        }
    }
}

private fun Modifier.clickableAvatar(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))

class EditProfileViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState: StateFlow<EditProfileUiState> = _uiState.asStateFlow()

    private val app get() = OldChatApplication.instance

    /** Load current profile from /me. */
    fun load() {
        viewModelScope.launch {
            app.apiClient.get("/me").onSuccess { json ->
                val map = app.gson.fromJson(json, Map::class.java) as? Map<*, *>
                val data = map?.get("data") as? Map<*, *> ?: map
                _uiState.update {
                    it.copy(
                        nickname = (data?.get("display_name")
                            ?: data?.get("nickname") ?: data?.get("name"))?.toString()
                            ?: it.nickname,
                        signature = data?.get("signature")?.toString() ?: it.signature,
                        avatarUrl = resolveAvatarUrl(
                            (data?.get("avatar_url") ?: data?.get("avatar"))?.toString()
                        ) ?: it.avatarUrl
                    )
                }
            }
        }
    }

    fun onNicknameChanged(v: String) = _uiState.update { it.copy(nickname = v) }
    fun onSignatureChanged(v: String) = _uiState.update { it.copy(signature = v) }

    fun uploadAvatar(uri: android.net.Uri) {
        _uiState.update { it.copy(uploadingAvatar = true) }
        viewModelScope.launch {
            try {
                val context = OldChatApplication.instance
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@launch
                val bytes = inputStream.readBytes()
                inputStream.close()
                // POST /me/avatar (multipart) §18
                val result = app.apiClient.postMultipart(
                    "/me/avatar",
                    listOf(
                        com.oldchat.material.core.network.FormPartData.FilePart(
                            "file", bytes, "avatar.jpg", "image/jpeg"
                        )
                    )
                )
                result.fold(
                    onSuccess = { body ->
                        val map = app.gson.fromJson(body, Map::class.java) as? Map<*, *>
                        val url = map?.get("avatar_url") as? String
                            ?: (map?.get("data") as? Map<*, *>)?.get("avatar_url") as? String
                            ?: ""
                        _uiState.update { it.copy(uploadingAvatar = false, avatarUrl = resolveAvatarUrl(url)) }
                    },
                    onFailure = {
                        _uiState.update { it.copy(uploadingAvatar = false, errorMessage = "头像上传失败") }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(uploadingAvatar = false, errorMessage = "头像上传失败") }
            }
        }
    }

    fun save() {
        val s = _uiState.value
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = app.apiClient.post(
                "/me/profile",
                app.gson.toJson(
                    mapOf(
                        "nickname" to s.nickname,
                        "signature" to s.signature
                    )
                )
            )
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(saving = false, saveSuccess = true) }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(saving = false, errorMessage = e.message ?: "保存失败")
                    }
                }
            )
        }
    }

    fun consumeSaveResult() = _uiState.update { it.copy(saveSuccess = false) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}

/**
 * Resolve a (possibly relative) avatar URL（动态跟随登录/文件服务器）。
 */
private fun resolveAvatarUrl(url: String?): String? =
    OldChatApplication.instance.serverConfig.resolveMediaUrl(url)

data class EditProfileUiState(
    val nickname: String = "",
    val signature: String = "",
    val avatarUrl: String? = null,
    val uploadingAvatar: Boolean = false,
    val saving: Boolean = false,
    val saveSuccess: Boolean = false,
    val errorMessage: String? = null
)
