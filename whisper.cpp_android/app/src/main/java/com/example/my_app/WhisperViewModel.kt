//package com.example.my_app
//
//import android.content.Context
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import androidx.annotation.RequiresPermission
//import android.Manifest
//import android.content.pm.PackageManager
//import androidx.core.content.ContextCompat
//
///**
// * ViewModel that glues the Compose UI to the native whisper.cpp engine
// * via WhisperBridge.  It is responsible for
// *  – loading the model once (and only once)
// *  – capturing microphone PCM
// *  – streaming those samples to WhisperBridge on a background thread
// *  – exposing the latest UI‑friendly state through StateFlow
// */
//class WhisperViewModel : ViewModel() {
//
//    // ────────────────────── State exposed to the UI ──────────────────────
//    private val _isRecording = MutableStateFlow(false)
//    val isRecording: StateFlow<Boolean> get() = _isRecording
//
//    private val _transcript = MutableStateFlow("")
//    val transcript: StateFlow<String> get() = _transcript
//
//    private val _error = MutableStateFlow<String?>(null)
//    val error: StateFlow<String?> get() = _error
//
//    // ───────────────────────── Internal members ─────────────────────────
//    private var recorder: AudioRecord? = null
//    private var micJob: Job? = null
//
//    /**
//     * Begin real‑time transcription.  Safe to call multiple times; a second
//     * call while already recording is ignored.
//     *
//     * @param context  any Context (Activity is fine)
//     * @param assetModelPath  path inside /assets that contains the GGML model
//     */
//    fun startRecording(
//        context: Context,
//        assetModelPath: String = "models/ggml-tiny.bin"
//    ) {
//        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED) {
//            _error.value = "Microphone permission not granted"
//            _isRecording.value = false
//            return
//        }
//        if (_isRecording.value) return            // Already active → ignore
//        _isRecording.value = true
//
//        viewModelScope.launch @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO) {
//            try {
//                // 1) Copy model from assets (first launch only) & load it
//                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
//                withContext(Dispatchers.Default) {
//                    check(WhisperBridge.init(modelPath)) { "Model failed to load" }
//                }
//
//                // 2) Configure microphone (16‑kHz, mono, PCM‑16)
//
//                val sampleRate = 16000
//                val minBuf = AudioRecord.getMinBufferSize(
//                    sampleRate,
//                    AudioFormat.CHANNEL_IN_MONO,
//                    AudioFormat.ENCODING_PCM_16BIT
//                )
//                recorder = AudioRecord(
//                    MediaRecorder.AudioSource.MIC,
//                    sampleRate,
//                    AudioFormat.CHANNEL_IN_MONO,
//                    AudioFormat.ENCODING_PCM_16BIT,
//                    minBuf
//                ).apply { startRecording() }
//
//                // 3) Launch worker coroutine to pull audio & forward to Whisper
//                micJob = launch(Dispatchers.Default) {
//                    val shortBuf = ShortArray(sampleRate) // ~20 ms buffer
//                    val floatBuf = FloatArray(shortBuf.size)
//
//                    while (_isRecording.value &&
//                        recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
//                        val n = recorder?.read(shortBuf, 0, shortBuf.size) ?: 0
//                        if (n > 0) {
//                            // Convert shorts (‑32768..32767) → floats (‑1.0..1.0)
//                            for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
//                            val text = WhisperBridge.transcribe(floatBuf)
//                            if (text.isNotBlank()) _transcript.value += text
//                        }
//                    }
//                }
//            } catch (t: Throwable) {
//                _error.value = t.message
//                _isRecording.value = false
//                stopRecording()   // ensure clean‑up on failure
//            }
//        }
//    }
//
//    /** Stop the ongoing transcription session and free resources. */
//    fun stopRecording() {
//        if (!_isRecording.value) return
//        _isRecording.value = false
//
//        micJob?.cancel()
//        micJob = null
//
//        recorder?.run {
//            try { stop() } catch (_: Exception) {}
//            release()
//        }
//        recorder = null
//
//        // Unload model off the main thread
//        viewModelScope.launch(Dispatchers.Default) { WhisperBridge.close() }
//    }
//
//    /** Convenience helper to reset the on‑screen transcript. */
//    fun clearTranscript() { _transcript.value = "" }
//
//    // ViewModel is about to be destroyed – stop everything gracefully
//    override fun onCleared() {
//        stopRecording()
//    }
//
//    fun setError(message: String) {
//        _error.value = message
//    }
//}

package com.example.my_app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.RequiresPermission
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * ViewModel that glues the Compose UI to the native whisper.cpp engine
 * via WhisperBridge.  It is responsible for
 *  – loading the model once (and only once)
 *  – capturing microphone PCM
 *  – streaming those samples to WhisperBridge on a background thread
 *  – exposing the latest UI‑friendly state through StateFlow
 */
class WhisperViewModel : ViewModel() {

    // ────────────────────── State exposed to the UI ──────────────────────
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> get() = _isRecording

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> get() = _transcript

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    // ───────────────────────── Internal members ─────────────────────────
    private var recorder: AudioRecord? = null
    private var micJob: Job? = null

    /**
     * Begin real‑time transcription.  Safe to call multiple times; a second
     * call while already recording is ignored.
     *
     * @param context       any Context (Activity is fine)
     * @param assetModelPath path inside /assets that contains the GGML model
     */
    fun startRecording(
        context: Context,
        assetModelPath: String = "models/ggml-tiny.bin"
    ) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            _error.value = "Microphone permission not granted"
            _isRecording.value = false
            return
        }
        if (_isRecording.value) return            // Already active → ignore
        _isRecording.value = true

        viewModelScope.launch @RequiresPermission(Manifest.permission.RECORD_AUDIO) {
            try {
                // 1) Copy model from assets (first launch only) & load it
                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
                withContext(Dispatchers.Default) {
                    check(WhisperBridge.init(modelPath)) { "Model failed to load" }
                }

                // 2) Configure microphone (16‑kHz, mono, PCM‑16)
                val sampleRate = 16000
                val chunkSamples = sampleRate / 50       // ~20 ms chunks (320 samples)
                val minRecBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minRecBuf, chunkSamples * Short.SIZE_BYTES)
                ).apply { startRecording() }

                // 3) Launch worker coroutine to pull audio, amplify, play back, & forward to Whisper
                micJob = launch(Dispatchers.Default) {
                    val shortBuf = ShortArray(chunkSamples)
                    val floatBuf = FloatArray(chunkSamples)
                    val gainFactor = 3.0f      // amplify by 2x (clamp later)

                    // Prepare AudioTrack for playback of PCM16
                    val minPlayBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val playBufSize = maxOf(minPlayBuf, chunkSamples * Short.SIZE_BYTES)
                    val audioTrack = AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        playBufSize,
                        AudioTrack.MODE_STREAM
                    ).apply {
                        // Optionally boost track volume (API21+)
                        setVolume(1.0f)
                        play()
                    }

                    while (_isRecording.value &&
                        recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder?.read(shortBuf, 0, shortBuf.size) ?: 0
                        if (n > 0) {
                            // ---- AMPLIFY & PLAYBACK ----
                            for (i in 0 until n) {
                                // amplify and clamp to Short range
                                val amplified = (shortBuf[i] * gainFactor).toInt()
                                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                shortBuf[i] = amplified.toShort()
                            }
                            audioTrack.write(shortBuf, 0, n)

                            // ---- TRANSCRIBE ----
                            for (i in 0 until n) {
                                floatBuf[i] = shortBuf[i] / 32768f
                            }
                            val text = WhisperBridge.transcribe(floatBuf)
                            if (text.isNotBlank()) _transcript.value += text
                        }
                    }

                    // Cleanup playback
                    audioTrack.stop()
                    audioTrack.release()
                }
            } catch (t: Throwable) {
                _error.value = t.message
                _isRecording.value = false
                stopRecording()   // ensure clean‑up on failure
            }
        }
    }

    /** Stop the ongoing transcription session and free resources. */
    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false

        micJob?.cancel()
        micJob = null

        recorder?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null

        // Unload model off the main thread
        viewModelScope.launch(Dispatchers.Default) { WhisperBridge.close() }
    }

    /** Convenience helper to reset the on‑screen transcript. */
    fun clearTranscript() { _transcript.value = "" }

    // ViewModel is about to be destroyed – stop everything gracefully
    override fun onCleared() {
        stopRecording()
    }

    fun setError(message: String) {
        _error.value = message
    }
}
