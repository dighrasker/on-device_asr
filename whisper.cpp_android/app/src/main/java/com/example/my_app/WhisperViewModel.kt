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

    // ───────────────────────── Internal members ─────────────────────────
    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private var inferJob: Job? = null
    private var frameChan: Channel<FloatArray>? = null
    private val modelsPath = File(app.filesDir, "models")
    private val samplesPath = File(app.filesDir, "samples")

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

        frameChan = Channel(capacity = 4) // 4 * 250 ms ring buffer


        viewModelScope.launch {
            try {
                // 1) Copy model from assets (first launch only) & load it
                val modelPath = WhisperBridge.ensureModel(context, assetModelPath)
                withContext(Dispatchers.Default) {
                    val initSuccess = WhisperBridge.init(modelPath, 1)
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

    fun transcribeFile(context: Context, assetFileName: String = "1jfk.wav") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Ensure model is loaded
                val modelPath = WhisperBridge.ensureModel(context, "models/ggml-tiny.bin")
                val initSuccess = withContext(Dispatchers.Default) {
                    WhisperBridge.init(modelPath, 1)
                }
                check(initSuccess) { "Model failed to load" }

                Log.d("Dhruv", "Bello1")
                copyAssets()
                // 2. Load audio file from assets
                Log.d("Dhruv", "$assetFileName")
                //val inputStream = context.assets.open(assetFileName)


                val wavFile = File(samplesPath, assetFileName)
                Log.d("Dhruv", "wavFile: $wavFile")

                val pcmData = decodeWaveFile(wavFile)//decodeWavToFloatArray(inputStream)


                Log.d("Dhruv", "pcmData: ${pcmData.contentToString()}")
                // 3. Run Whisper
                val text = withContext(Dispatchers.Default) {
                    WhisperBridge.transcribe(pcmData) //jump off point
                }
                Log.d("Dhruv", " $text")
                _transcript.value += text
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        Log.d("Dhruv", "entered copyAssets")
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        //application.copyData("models", modelsPath, ::printMessage)
        app.copyData("samples", samplesPath)
        Log.d("Dhruv", "Finished copyAssets")
    }

    fun decodeWaveFile(file: File): FloatArray {

        //Read bytes into a byte buffer
        val baos = ByteArrayOutputStream()
        file.inputStream().use { it.copyTo(baos) }
        val buffer = ByteBuffer.wrap(baos.toByteArray())
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // reading the header
        val channel = buffer.getShort(22).toInt()
        buffer.position(24)
        val originalRate = buffer.int
        Log.d("Dhruv", "WAV sampleRate = $originalRate, channels = $channel")

        //Jump to PCM data, view as shorts
        buffer.position(44)
        val shortBuffer = buffer.asShortBuffer()
        val shortArray = ShortArray(shortBuffer.limit())
        shortBuffer.get(shortArray)
        Log.d("Dhruv", "shortBuffer: ${shortArray.contentToString()}")

        //change data type and numChannels as needed
        val rawFloats =  FloatArray(shortArray.size / channel) { index ->
            when (channel) {
                1 -> (shortArray[index] / 32767.0f).coerceIn(-1f..1f)
                else -> ((shortArray[2*index] + shortArray[2*index + 1])/ 32767.0f / 2.0f).coerceIn(-1f..1f)
            }
        }
        Log.d("Dhruv", "rawFloats size=${rawFloats.size}, head=${rawFloats.take(10)}")
        val targetRate = 16_000
        Log.d("Dhruv", "returning rawFloats")
        return rawFloats
    }

    private suspend fun Context.copyData(
        assetDirName: String,
        destDir: File
    ) = withContext(Dispatchers.IO) {
        Log.d("Dhruv", "Entering copyData")
        assets.list(assetDirName)?.forEach { name ->
            val assetPath = "$assetDirName/$name"
            Log.v("Dhruv", "Processing $assetPath...")
            val destination = File(destDir, name)
            Log.v("Dhruv", "Copying $assetPath to $destination...")
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.v("Dhruv", "Copied $assetPath to $destination")
        }
    }

    //DOES NOT HANDLE ALL USE CASES YET
//    fun decodeWavToFloatArray(input: InputStream): FloatArray {
//        val audioData = input.readBytes()
//        val header = ByteBuffer.wrap(audioData, 0, 44)
//            .order(ByteOrder.LITTLE_ENDIAN)
//
//        //reading header for useful information
//
//        val audioFormat   = header.getShort(20).toInt()      // 1=PCM, 3=Float
//        val numChannels   = header.getShort(22).toInt()
//        val sampleRate    = header.getInt(24)
//        val bitsPerSample = header.getShort(34).toInt()
//        Log.d("Dhruv", "$bitsPerSample")
//
//        val pcmStart = 44
//        val pcmBytes = audioData.copyOfRange(pcmStart, audioData.size)
//        Log.d("Dhruv", "fmt=0x%04x rate=%d bits=%d".format(audioFormat, sampleRate, bitsPerSample))
//        if (audioFormat == 3) { // ADDED
//            val floatBuffer = ByteBuffer.wrap(pcmBytes)       // ADDED
//                .order(ByteOrder.LITTLE_ENDIAN)               // ADDED
//                .asFloatBuffer()                              // ADDED
//
//            val totalSamples = floatBuffer.limit()            // ADDED
//            val monoFloats = if (numChannels == 1) {          // ADDED
//                FloatArray(totalSamples) { i ->               // ADDED
//                    floatBuffer.get(i)                        // ADDED
//                }                                             // ADDED
//            } else {                                          // ADDED
//                require(totalSamples % 2 == 0) {              // ADDED
//                    "Stereo data must have even number of samples"
//                }                                             // ADDED
//                val monoSamples = totalSamples / 2            // ADDED
//                FloatArray(monoSamples) { i ->                // ADDED
//                    val left  = floatBuffer.get(i * 2)        // ADDED
//                    val right = floatBuffer.get(i * 2 + 1)    // ADDED
//                    (left + right) / 2f                      // ADDED
//                }                                             // ADDED
//            }                                                 // ADDED
//
//            if (sampleRate == 16_000) return monoFloats       // ADDED
//
//            // ADDED: resample to 16 kHz (same logic as PCM path)
//            val dstRate = 16_000                              // ADDED
//            val lengthInSeconds = monoFloats.size.toDouble() / sampleRate // ADDED
//            val outSize = (lengthInSeconds * dstRate).toInt() // ADDED
//            val ratio = sampleRate.toDouble() / dstRate       // ADDED
//            val reSampledFloats = FloatArray(outSize)         // ADDED
//
//            for (i in 0 until outSize) {                      // ADDED
//                val srcIndex = i * ratio                      // ADDED
//                val i0 = floor(srcIndex).toInt().coerceIn(0, monoFloats.lastIndex) // ADDED
//                val i1 = min(i0 + 1, monoFloats.lastIndex)    // ADDED
//                val frac = (srcIndex - i0).toFloat()          // ADDED
//                reSampledFloats[i] = monoFloats[i0] * (1 - frac) + monoFloats[i1] * frac // ADDED
//            }                                                 // ADDED
//
//            return reSampledFloats                            // ADDED
//        } // ADDED
//
//        //little endian format if needed
//        val shortBuffer = ByteBuffer.wrap(pcmBytes)
//            .order(ByteOrder.LITTLE_ENDIAN)
//            .asShortBuffer()
//
//        val totalSamples = shortBuffer.limit()
//        val monoFloats: FloatArray
//
//        //collapsing stereo to mono if needed + right dtype
//        if (numChannels == 1) {
//            // Mono – convert directly
//            monoFloats = FloatArray(totalSamples) { i ->
//                shortBuffer.get(i) / 32768f
//            }
//        } else if (numChannels == 2) {
//            // Stereo – average left and right channels
//            require(totalSamples % 2 == 0) { "Stereo data must have even number of samples" }
//            val monoSamples = totalSamples / 2
//            monoFloats = FloatArray(monoSamples) { i ->
//                val left = shortBuffer.get(i * 2).toFloat()
//                val right = shortBuffer.get(i * 2 + 1).toFloat()
//                ((left + right) / 2f) / 32768f
//            }
//        } else {
//            throw IllegalArgumentException("Unsupported number of channels: $numChannels")
//        }
//
//        if (sampleRate == 16_000) return monoFloats
//
//        //resampling if needed
//        val dstRate = 16_000
//        val lengthInSeconds = monoFloats.size.toDouble() /sampleRate
//        val outSize = (lengthInSeconds * dstRate).toInt()
//        val ratio = sampleRate.toDouble() / dstRate
//        val reSampledFloats = FloatArray(outSize)
//
//        for (i in 0 until outSize) {
//            val srcIndex = i * ratio
//            val i0 = floor(srcIndex).toInt().coerceIn(0, monoFloats.lastIndex)
//            val i1 = min(i0 + 1, monoFloats.lastIndex)
//            val frac = (srcIndex - i0).toFloat()
//            reSampledFloats[i] = monoFloats[i0] * (1 - frac) + monoFloats[i1] * frac
//        }
//
//        return reSampledFloats
//
//    }


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