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
//                    val shortBuf = ShortArray(sampleRate)
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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel that glues the Compose UI to the native whisper.cpp engine
 * via WhisperBridge.  It now decouples audio capture from inference to
 * avoid buffer overruns on slower devices/emulators.
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
    private var captureJob: Job? = null
    private var inferJob: Job? = null
    private var frameChan: Channel<FloatArray>? = null

    /**
     * Begin real‑time transcription. Safe to call multiple times; a second
     * call while already recording is ignored.
     *
     * @param context any Context (Activity is fine)
     * @param assetModelPath path inside /assets that contains the GGML model
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(
        context: Context,
        assetModelPath: String = "models/ggml-tiny.bin"
    ) {
        if (_isRecording.value) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            _error.value = "Microphone permission not granted"
            println("[DEBUG] startRecording: permission denied")
            return
        }

        _isRecording.value = true
        println("[DEBUG] startRecording: _isRecording set to true")
        frameChan = Channel(capacity = 4) // 4 * 250 ms ring buffer
        println("[DEBUG] startRecording: frameChan initialized with capacity 4")

        viewModelScope.launch {
            try {
                // 1) Copy model from assets (first launch only) & load it
                println("[DEBUG] startRecording: ensuring model at $assetModelPath")
                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
                println("[DEBUG] startRecording: model copied to $modelPath")
                withContext(Dispatchers.Default) {
                    val initSuccess = WhisperBridge.init(modelPath)
                    println("[DEBUG] startRecording: WhisperBridge.init returned $initSuccess")
                    check(initSuccess) { "Model failed to load" }
                }

                // 2) Configure microphone (16‑kHz, mono, PCM‑16)
                val sampleRate = 16000
                println("[DEBUG] startRecording: sampleRate = $sampleRate")
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                println("[DEBUG] startRecording: minBuf size = $minBuf")
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4                      // extra slack to tolerate jitter
                ).apply {
                    println("[DEBUG] startRecording: AudioRecord created, starting recording...")
                    startRecording()
                }

                // ─────────────── 1) capture coroutine ───────────────
                captureJob = launch(Dispatchers.IO) {
                    val shortBuf = ShortArray(sampleRate * 1)   // 250 ms frame
                    val floatBuf = FloatArray(shortBuf.size)
                    println("[DEBUG] captureJob: buffers of size ${shortBuf.size} initialized")
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                    while (isActive &&
                        recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder?.read(shortBuf, 0, shortBuf.size) ?: break
                        println("[DEBUG] captureJob: read $n samples")
                        if (n > 0) {
                            for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
                            println("[DEBUG] captureJob: converted to floatBuf sample[0]=${floatBuf[0]}")
                            frameChan?.trySend(floatBuf.copyOf()).also {
                                println("[DEBUG] captureJob: frame sent to channel")
                            }
                        }
                    }
                    println("[DEBUG] captureJob: exiting loop")
                }

                // ────────────── 2) inference coroutine ──────────────
                inferJob = launch(Dispatchers.Default) {
                    frameChan?.let { chan ->
                        for (frame in chan) {
                            val text = WhisperBridge.transcribe(frame)
                            if (text.isNotBlank()) {
                                _transcript.value += text
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                _error.value = t.message
                _isRecording.value = false
                println("[DEBUG] startRecording: exception occurred: ${t.message}")
                stopRecording()   // ensure clean‑up on failure
            }
        }
    }

    /** Stop the ongoing transcription session and free resources. */
    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false

        captureJob?.cancel(); captureJob = null
        inferJob?.cancel();   inferJob   = null

        recorder?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null

        frameChan?.close(); frameChan = null

        // Unload model off the main thread
        viewModelScope.launch(Dispatchers.Default) { WhisperBridge.close() }
    }

    /** Convenience helper to reset the on‑screen transcript. */
    fun clearTranscript() { _transcript.value = "" }

    // ViewModel is about to be destroyed – stop everything gracefully
    override fun onCleared() {
        stopRecording()
    }

    fun setError(message: String) { _error.value = message }
}


//package com.example.my_app
//
//import android.Manifest
//import android.content.Context
//import android.content.pm.PackageManager
//import android.media.AudioFormat
//import android.media.AudioRecord
//import android.media.MediaRecorder
//import android.os.Process
//import androidx.annotation.RequiresPermission
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import kotlinx.coroutines.*
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//
///**
// * ViewModel with streaming inference: 1s capture frames, 25s rolling context, 1s hop
// */
//class WhisperViewModel : ViewModel() {
//
//    // UI state
//    private val _isRecording = MutableStateFlow(false)
//    val isRecording: StateFlow<Boolean> get() = _isRecording
//
//    private val _transcript = MutableStateFlow("")
//    val transcript: StateFlow<String> get() = _transcript
//
//    private val _error = MutableStateFlow<String?>(null)
//    val error: StateFlow<String?> get() = _error
//
//    // internal
//    private var recorder: AudioRecord? = null
//    private var captureJob: Job? = null
//    private var inferJob: Job? = null
//    private var frameChan: Channel<FloatArray>? = null
//
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    fun startRecording(
//        context: Context,
//        assetModelPath: String = "models/ggml-base.en.bin"
//    ) {
//        if (_isRecording.value) return
//        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
//            != PackageManager.PERMISSION_GRANTED) {
//            _error.value = "Microphone permission not granted"
//            return
//        }
//        _isRecording.value = true
//        frameChan = Channel(capacity = 4)
//
//        viewModelScope.launch {
//            try {
//                // load model
//                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
//                withContext(Dispatchers.Default) {
//                    check(WhisperBridge.init(modelPath)) { "Model failed to load" }
//                }
//
//                // audio config
//                val sampleRate = 16000
//                val minBuf = AudioRecord.getMinBufferSize(
//                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
//                recorder = AudioRecord(
//                    MediaRecorder.AudioSource.MIC,
//                    sampleRate,
//                    AudioFormat.CHANNEL_IN_MONO,
//                    AudioFormat.ENCODING_PCM_16BIT,
//                    minBuf * 4
//                ).apply { startRecording() }
//
//                // capture 1s frames
//                captureJob = launch(Dispatchers.IO) {
//                    val shortBuf = ShortArray(sampleRate * 1)
//                    val floatBuf = FloatArray(shortBuf.size)
//                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
//                    while (isActive && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
//                        val n = recorder?.read(shortBuf, 0, shortBuf.size) ?: break
//                        if (n > 0) {
//                            for (i in 0 until n) floatBuf[i] = shortBuf[i] / 32768f
//                            frameChan?.send(floatBuf.copyOf())
//                        }
//                    }
//                }
//
//                // streaming inference: 25s ring buffer, 1s hop
//                inferJob = launch(Dispatchers.Default) {
//                    val ctxSec = 25
//                    val hopSec = 1
//                    val ctxSamples = sampleRate * ctxSec
//                    val hopSamples = sampleRate * hopSec
//                    val ring = FloatArray(ctxSamples)
//                    var writePos = 0
//                    var filled = 0
//
//                    val chan = frameChan ?: return@launch
//                    for (frame in chan) {
//                        // write frame into ring
//                        System.arraycopy(frame, 0, ring, writePos, frame.size)
//                        writePos = (writePos + frame.size) % ctxSamples
//                        filled = minOf(filled + frame.size, ctxSamples)
//
//                        // every hopSec seconds, decode
//                        if (filled == ctxSamples && (filled % hopSamples) == 0) {
//                            val ordered = if (writePos == 0) ring
//                            else ring.copyOfRange(writePos, ctxSamples) + ring.copyOfRange(0, writePos)
//                            val text = WhisperBridge.transcribe(ordered)
//                            if (text.isNotBlank()) _transcript.value += text
//                        }
//                    }
//                }
//
//            } catch (t: Throwable) {
//                _error.value = t.message
//                _isRecording.value = false
//                stopRecording()
//            }
//        }
//    }
//
//    fun stopRecording() {
//        if (!_isRecording.value) return
//        _isRecording.value = false
//        captureJob?.cancel()
//        inferJob?.cancel()
//        recorder?.run { stop(); release() }
//        recorder = null
//        frameChan?.close()
//        viewModelScope.launch(Dispatchers.Default) { WhisperBridge.close() }
//    }
//
//    fun clearTranscript() { _transcript.value = "" }
//    override fun onCleared() { stopRecording() }
//}