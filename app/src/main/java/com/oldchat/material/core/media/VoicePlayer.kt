package com.oldchat.material.core.media

import android.content.Context
import android.media.MediaPlayer
import com.oldchat.material.core.network.ApiClient
import com.oldchat.material.OldChatApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Simple voice player with candidate URL fallback.
 * Mirrors MessageVoicePlayer from original client §6.1.
 */
class VoicePlayer(private val context: Context) {

    enum class State { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentUrl: String = ""

    fun play(url: String) {
        if (currentUrl == url && _state.value == State.PAUSED) {
            resume()
            return
        }
        if (currentUrl == url && _state.value == State.PLAYING) {
            pause()
            return
        }

        stop()
        currentUrl = url
        _state.value = State.LOADING

        scope.launch {
            val resolved = MediaUrlResolver.resolve(url) ?: return@launch
            val candidates = MediaUrlResolver.resolveCandidates(resolved)

            // Try all candidates
            for (candidate in candidates) {
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(candidate)
                        prepare()
                        start()
                        setOnCompletionListener { stop() }
                    }
                    _state.value = State.PLAYING
                    startProgressUpdates()
                    currentUrl = candidate
                    return@launch // Success
                } catch (_: Exception) {
                    mediaPlayer?.release()
                    mediaPlayer = null
                }
            }

            // All candidates failed
            _state.value = State.ERROR
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _state.value = State.PAUSED
        progressJob?.cancel()
    }

    fun resume() {
        mediaPlayer?.start()
        _state.value = State.PLAYING
        startProgressUpdates()
    }

    fun stop() {
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _state.value = State.IDLE
        _progress.value = 0f
        currentUrl = ""
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && _state.value == State.PLAYING) {
                val mp = mediaPlayer ?: break
                try {
                    val dur = mp.duration
                    if (dur > 0) {
                        _progress.value = mp.currentPosition.toFloat() / dur.toFloat()
                    }
                } catch (_: Exception) { break }
                delay(100)
            }
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
