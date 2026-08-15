package com.oldchat.material.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.oldchat.material.core.media.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice record button with hold-to-record gesture.
 * Mirrors the voice input from original client §5.1.
 *
 * Behavior:
 * - Hold Mic button → start recording (amplitude animation)
 * - Release → stop recording → upload and send
 * - Slide up past threshold → cancel recording
 */
@Composable
fun VoiceRecordButton(
    onVoiceReady: (voiceFileUri: android.net.Uri, durationMs: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(context) }
    val recorderState by recorder.state.collectAsState()
    val amplitude by recorder.amplitude.collectAsState()

    var isPressed by remember { mutableStateOf(false) }
    var slideOffset by remember { mutableFloatStateOf(0f) }
    val cancelThreshold = -80f

    // Animate pulse on recording
    val pulseScale by animateFloatAsState(
        targetValue = if (recorderState == VoiceRecorder.State.RECORDING) 1.2f else 1f,
        animationSpec = if (recorderState == VoiceRecorder.State.RECORDING)
            infiniteRepeatable(tween(600), RepeatMode.Reverse) else tween(200),
        label = "pulse"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Recording indicator
            if (recorderState == VoiceRecorder.State.RECORDING) {
                Text(
                    text = if (slideOffset < cancelThreshold) "松开取消" else "松开发送，上滑取消",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (slideOffset < cancelThreshold) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Amplitude bar
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(4.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = amplitude.coerceIn(0f, 1f))
                            .background(
                                if (slideOffset < cancelThreshold) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // Mic button
            FilledIconButton(
                onClick = { /* handled by pointerInput */ },
                modifier = Modifier
                    .size(44.dp)
                    .scale(pulseScale)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Check permission
                                if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    return@detectTapGestures
                                }

                                isPressed = true
                                val started = recorder.startRecording()
                                if (!started) {
                                    isPressed = false
                                    return@detectTapGestures
                                }

                                try {
                                    // Wait for release
                                    awaitRelease()
                                } finally {
                                    isPressed = false
                                    if (recorderState == VoiceRecorder.State.RECORDING) {
                                        if (slideOffset < cancelThreshold) {
                                            recorder.cancelRecording()
                                        } else {
                                            val result = recorder.stopRecording()
                                            if (result != null) {
                                                onVoiceReady(result.first, result.second)
                                            }
                                        }
                                    }
                                    slideOffset = 0f
                                }
                            }
                        )
                    },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (recorderState == VoiceRecorder.State.RECORDING)
                        MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (recorderState == VoiceRecorder.State.RECORDING)
                        MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Filled.Mic, "语音")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.destroy() }
    }
}
