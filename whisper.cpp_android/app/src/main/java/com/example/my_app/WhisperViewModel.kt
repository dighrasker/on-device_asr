package com.example.my_app

import android.Manifest
import android.app.Application
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
//import be.tarsos.dsp.AudioDispatcher
//import be.tarsos.dsp.AudioDispatcherFactory
//import be.tarsos.dsp.AudioEvent
//import be.tarsos.dsp.AudioProcessor
//import be.tarsos.dsp.resample.RateTransposer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.absoluteValue


/**
 * ViewModel that glues the Compose UI to the native whisper.cpp engine
 * via WhisperBridge.  It now decouples audio capture from inference to
 * avoid buffer overruns on slower devices/emulators.
 */
class WhisperViewModel(private val app: Application): AndroidViewModel(app){

    // ────────────────────── State exposed to the UI ──────────────────────
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> get() = _isRecording

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> get() = _transcript

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error

    // ───────────────────────── Creating instances of classes ─────────────────────────
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var inferJob: Job? = null
    private var frameChan: Channel<FloatArray>? = null
    //private val modelsPath = File(app.filesDir, "models")
    //private val samplesPath = File(app.filesDir, "samples")

    /**
     * Begin real‑time transcription. Safe to call multiple times; a second
     * call while already recording is ignored.
     *
     * @param context any Context (Activity is fine)
     * @param assetModelPath path inside /assets that contains the GGML model
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording (context: Context, assetModelPath: String = "models/ggml-tiny.bin") {
        Log.d("Dhruv", "Just Entered startRecording")
        //if there is already a recording in progress, this prevents another one from starting
        if (_isRecording.value) return

        //throws an error if we start recording without mic permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            _error.value = "Microphone permission not granted"

            return
        }

        //sets recording flag to true, indicating that recording is in progress
        _isRecording.value = true
        Log.v("Dhruv", "isRecording.value: $_isRecording")
        //frameChan was declared but never defined
        //this is essentially a fifo of size 4, send() to push and recieve() to pop
        frameChan = Channel(capacity = Channel.UNLIMITED) // 4 * 250 ms ring buffer


        //start a new coroutine to do ASR in the background, tie scope to this viewModel
        viewModelScope.launch {
            try {
                Log.d("Dhruv", "Just Entered viewModelScope.launch")
                // 1) Copy model from assets (first launch only) & load it
                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
                Log.v("Dhruv", "modelPath: $modelPath")
                //use a seperate dispatcher to ensure heavy duty stuff is being done on a different thread than UI thread
                withContext(Dispatchers.Default) {
                    //loading model
                    val initSuccess = WhisperBridge.init(modelPath, 1)
                    Log.v("Dhruv", "initSuccess: $initSuccess")
                    check(initSuccess) { "Model failed to load" }
                }
                // 2) Configure microphone (16‑kHz, mono, PCM‑16)
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                Log.v("Dhruv", "minBuf: $minBuf")

                //creates an instance of AudioRecord and sets recorder equal to that
                //recorder was declared up top but never defined
                //the .apply at the end immediately calls the startRecording function in the AudioRecord
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 4                      // extra slack to tolerate jitter
                ).apply {
                    startRecording()
                }

                // ─────────────── 1) mic -> buffer ───────────────
                captureJob = launch(Dispatchers.IO) {
                    val frameSize = 1024
                    val shortBuf = ShortArray(frameSize)
                    val floatBuf = FloatArray(frameSize)

                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

                    while (isActive &&
                        recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder?.read(shortBuf, 0, frameSize) ?: break
                        val start = (0 until frameSize - 10).random()
                        Log.v("Dhruv", "shortBuf[$start..${start+9}]: ${shortBuf.slice(start until start+10)}")
                        if (n > 0) {
                            for (i in 0 until n) floatBuf[i] = (shortBuf[i] / 32768f).coerceIn(-1f, 1f)
//                            Log.v("Dhruv", "floatBuf[0..9]: ${floatBuf.take(10)}")
                            Log.v("Dhruv", "floatBuf[$start..${start+9}]: ${floatBuf.slice(start until start+10)}")
                            val result = frameChan!!.trySend(floatBuf.copyOf())
                            Log.v("Dhruv", "trySend -> success=${result.isSuccess}, dropped=${result.isFailure}")
                        }
                    }
                }

                // ────────────── 2) buffer -> whisper ──────────────
                inferJob = launch(Dispatchers.Default) {
                    Log.v("Dhruv", "inferJob: I am alive and well")
                    frameChan?.let { chan ->
                        val windowSize = sampleRate * 3               // e.g. 16 000 * 3 = 48 000 samples
                        val hopSize    = sampleRate * 3
                        val ring = ArrayDeque<Float>(windowSize)

                        var sinceInfer = 0
                        for (frame in chan) {
                            // 1) Append the new frame
                            Log.v("Dhruv", "frame: ${frame.take(10)}")
                            Log.v("Dhruv", "Pulled one frame; ring before append=${ring.size}")
                            frame.forEach { ring.addLast(it) }
                            Log.v("Dhruv", "Ring after append=${ring.size}")
                            // 2) Drop oldest samples so ring.size ≤ windowSize
                            while (ring.size > windowSize) ring.removeFirst()
                            sinceInfer += frame.size

                            Log.v("Dhruv", "Ring after trim=${ring.size}")
                            // 3) Only transcribe once we have a full 3 s of audio
                            if (ring.size == windowSize && sinceInfer >= hopSize) {
                                Log.d("Dhruv", "Ring full! calling transcribe()")
                                val recentSamples = ring.toFloatArray()
                                Log.d("Dhruv", "recentSamples: ${recentSamples.take(10)}")
                                val text = WhisperBridge.transcribe(recentSamples)
                                if (text.isNotBlank()) {
                                    _transcript.value += text
                                }

                                repeat(hopSize) { if (ring.isNotEmpty()) ring.removeFirst() }
                                sinceInfer = 0
                            }
                        }
                    }
                }
                Log.d("Dhruv", "Exiting startRecording")
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
        Log.d("Dhruv", "Just Entered stopRecording")
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
        Log.d("Dhruv", "Exiting stopRecording")
    }

    /** Convenience helper to reset the on‑screen transcript. */
    fun clearTranscript() { _transcript.value = "" }

    // ViewModel is about to be destroyed – stop everything gracefully
    override fun onCleared() {
        stopRecording()
    }

    fun setError(message: String) { _error.value = message }
}