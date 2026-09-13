package com.oldchat.material

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.oldchat.material.core.notify.AppForeground
import com.oldchat.material.core.notify.NotificationHelper
import androidx.compose.runtime.*
import com.oldchat.material.feature.auth.LoginScreen
import com.oldchat.material.service.MusicPlaybackService
import com.oldchat.material.ui.screen.MainScreen
import com.oldchat.material.ui.theme.OldChatMaterialTheme

/**
 * Launcher activity. Mirrors MainActivity from client-guide.md §2.1.
 *
 * Flow:
 * 1. Check auth token
 * 2. No token -> LoginScreen with Material You design
 * 3. Has token -> MainScreen with four tabs: Chats / Friends / Discover / Profile
 */
class MainActivity : ComponentActivity() {

    companion object {
        /**
         * 来自音乐通知的"打开正在播放"指令标题（Compose 可观察）。
         * onNewIntent 时更新；MainScreen 的 LaunchedEffect 监听它触发跳转。
         */
        var pendingOpenMusicTitle by mutableStateOf<String?>(null)
            private set
    }

    /** BUG-09：通知权限（API 33+）与录音权限（语音消息）的运行时申请入口。 */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果无需回调处理：用户拒绝时通知/录音静默不可用即可 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val authManager = (application as OldChatApplication).authManager

        // BUG-09：通知通道 + 各通知偏好（开关/声音/震动）订阅只做一次
        NotificationHelper.init(this)
        requestRuntimePermissions()

        setContent {
            OldChatMaterialTheme {
                var isLoggedIn by remember { mutableStateOf(authManager.isLoggedIn) }
                // 从通知栏带过来的"打开正在播放"指令（点击音乐通知）
                var openMusicTitle by remember { mutableStateOf(extractOpenMusicTitle(intent)) }

                if (isLoggedIn) {
                    MainScreen(
                        onLogout = {
                            isLoggedIn = false
                        },
                        openMusicTitle = openMusicTitle
                    )
                } else {
                    LoginScreen(
                        onLoginSuccess = {
                            isLoggedIn = true
                        }
                    )
                }
            }
        }
    }

    // App 已在后台时点通知，走这里（singleTop 复用 Activity）
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 重新读取并尝试触发打开正在播放；通过重启 content 来实现对状态的重置比较繁琐，
        // 这里用一个共享单例指示器，由 MainScreen 的 LaunchedEffect 监听。
        pendingOpenMusicTitle = extractOpenMusicTitle(intent)
    }

    private fun extractOpenMusicTitle(intent: Intent?): String? {
        if (intent == null) return null
        val open = intent.getBooleanExtra(MusicPlaybackService.EXTRA_OPEN_MUSIC, false)
        if (!open) return null
        val title = intent.getStringExtra(MusicPlaybackService.EXTRA_MUSIC_TITLE)
        return title?.takeIf { it.isNotEmpty() }
    }

    /**
     * BUG-09：原来工程里没有任何运行时权限申请 —— 通知（API 33+）与录音（语音消息）
     * 都会静默失效。这里在进入主界面时申请一次。
     */
    private fun requestRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            wanted += Manifest.permission.RECORD_AUDIO
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        // BUG-09 / ALIGN-06：前台标记，避免给「正在看的会话」重复弹通知
        AppForeground.isForeground = true
    }

    override fun onStop() {
        super.onStop()
        AppForeground.isForeground = false
    }

    override fun onResume() {
        super.onResume()
        // Start foreground MessageService safely (app is now in foreground).
        // Mirrors §2.1 step 8 / §11.1, but deferred from Application.onCreate
        // to avoid ForegroundServiceStartNotAllowedException on Android 12+.
        try {
            com.oldchat.material.service.MessageService.startIfAllowed(this)
        } catch (_: Exception) { /* ignore — WS already started in Application */ }
    }
}

