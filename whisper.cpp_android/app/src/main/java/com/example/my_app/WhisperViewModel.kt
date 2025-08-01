package com.example.my_app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun startRecording (context: Context, assetModelPath: String = "models/ggml-tiny.bin") {
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
                    val initSuccess = WhisperBridge.init(modelPath, 1)
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
                    val shortBuf = ShortArray(sampleRate)
                    val floatBuf = FloatArray(shortBuf.size)
                    println("[DEBUG] captureJob: buffers of size ${shortBuf.size} initialized")
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                    while (isActive &&
                        recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder?.read(shortBuf, 0, shortBuf.size) ?: break
                        println("[DEBUG] captureJob: read $n samples")
                        if (n > 0) {
                            println("[DEBUG] sample[0..4] = ${floatBuf.take(5)}")
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
                // ────────────── 2) inference coroutine ──────────────
                inferJob = launch(Dispatchers.Default) {
                    frameChan?.let { chan ->
                        val windowSize = sampleRate * 3               // e.g. 16 000 * 3 = 48 000 samples
                        val ring = ArrayDeque<Float>(windowSize)

                        for (frame in chan) {
                            // 1) Append the new frame
                            frame.forEach { ring.addLast(it) }
                            // 2) Drop oldest samples so ring.size ≤ windowSize
                            while (ring.size > windowSize) ring.removeFirst()

                            // 3) Only transcribe once we have a full 3 s of audio
                            if (ring.size == windowSize) {
                                val recentSamples = ring.toFloatArray()
                                val text = WhisperBridge.transcribe(recentSamples)
                                if (text.isNotBlank()) {
                                    _transcript.value += text
                                }
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

    fun transcribeFile(context: Context, assetFileName: String = "jfk.wav") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Ensure model is loaded
                val modelPath = WhisperBridge.ensureModel(context, "models/ggml-tiny.bin")
                val initSuccess = withContext(Dispatchers.Default) {
                    WhisperBridge.init(modelPath, 1)
                }
                check(initSuccess) { "Model failed to load" }

                // 2. Load audio file from assets
                Log.d("Dhruv", "$assetFileName")
                val inputStream = context.assets.open(assetFileName)
                val pcmData = decodeWavToFloatArray(inputStream)

                // 3. Run Whisper
                val text = withContext(Dispatchers.Default) {
                    WhisperBridge.transcribe(pcmData)
                }
                Log.d("Dhruv", " $text")
                _transcript.value += text
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun decodeWavToFloatArray(input: InputStream): FloatArray {
        val header = ByteArray(44)
        input.read(header)

        val audioData = input.readBytes()
        val shortBuffer = ByteBuffer.wrap(audioData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val floatArray = FloatArray(shortBuffer.limit())
        for (i in floatArray.indices) {
            floatArray[i] = shortBuffer.get(i) / 32768f
        }

        return floatArray
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