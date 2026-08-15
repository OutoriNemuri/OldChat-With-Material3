package com.oldchat.material.core.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Simple voice recorder using MediaRecorder.
 * Records to a cache file, returns URI and duration on stop.
 */
class VoiceRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordStartTime: Long = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var amplitudeJob: Job? = null

    fun startRecording(): Boolean {
        try {
            outputFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.aac")
            outputFile?.createNewFile()

            mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(outputFile?.absolutePath)
                prepare()
                start()
            }

            recordStartTime = System.currentTimeMillis()
            _state.value = State.RECORDING

            // Start amplitude updates
            amplitudeJob = scope.launch {
                while (isActive && _state.value == State.RECORDING) {
                    try {
                        val maxAmplitude = mediaRecorder?.maxAmplitude ?: 0
                        _amplitude.value = maxAmplitude / 32767f
                    } catch (_: Exception) { break }
                    delay(100)
                }
            }

            return true
        } catch (e: IOException) {
            _state.value = State.IDLE
            return false
        }
    }

    /**
     * Stop recording and return the file URI and duration in ms.
     */
    fun stopRecording(): Pair<Uri, Int>? {
        val durationMs = if (recordStartTime > 0) {
            (System.currentTimeMillis() - recordStartTime).toInt()
        } else 0

        amplitudeJob?.cancel()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { /* already stopped */ }
        mediaRecorder?.release()
        mediaRecorder = null
        _state.value = State.STOPPED
        _amplitude.value = 0f

        val file = outputFile ?: return null
        if (!file.exists() || file.length() == 0L) return null

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            Uri.fromFile(file)
        }

        return Pair(uri, durationMs)
    }

    fun cancelRecording() {
        amplitudeJob?.cancel()
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { }
        mediaRecorder?.release()
        mediaRecorder = null
        _state.value = State.IDLE
        _amplitude.value = 0f
        outputFile?.delete()
        outputFile = null
    }

    fun destroy() {
        cancelRecording()
        scope.cancel()
    }
}
