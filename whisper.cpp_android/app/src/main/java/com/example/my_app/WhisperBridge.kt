
package com.example.my_app

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * Thin, singleton‑style wrapper that hides all JNI details.
 *
 * Usage pattern:
 * ```kotlin
 * val modelPath = WhisperBridge.ensureModel(ctx, "models/ggml-tiny.bin")
 * WhisperBridge.init(modelPath)           // once (cheap)
 * val text = WhisperBridge.transcribe(pcm) // many times
 * WhisperBridge.close()                    // when you’re done
 * ```
 */
object WhisperBridge {

    // ======= Native library bootstrap =======
    init {
        System.loadLibrary("whisper_jni")   // must match CMake target name
    }

    // ======= Internal handle to native context =======
    private var ctxHandle: Long = 0L

    /**
     * Load model into native memory. Safe to call repeatedly; only the first
     * successful call does work. Returns true if the context is ready.
     */
    @Synchronized
    fun init(modelPath: String, nThreads: Int = Runtime.getRuntime().availableProcessors()): Boolean {
        if (ctxHandle != 0L) return true          // already initialised
        ctxHandle = nativeInit(modelPath, nThreads)
        return ctxHandle != 0L
    }


    /**
     * Feed one block of 16‑kHz mono PCM (float ‑1.0..1.0) and receive the
     * decoded text. Returns an empty string on error or no speech.
     */
    private val inferenceMutex = Mutex()   // ★ new

    /**
     * Safe wrapper – suspends while another inference is running.
     */
    suspend fun transcribe(pcm: FloatArray): String =
        withContext(Dispatchers.Default) {
            if (ctxHandle == 0L) return@withContext ""
            inferenceMutex.withLock {
                nativeRunInference(ctxHandle, pcm)
            }
        }
//    fun transcribe(pcm: FloatArray): String {
//        if (ctxHandle == 0L) return ""            // not ready
//        return nativeRunInference(ctxHandle, pcm) //jump off point
//    }

    /** Release native resources. */
//    @Synchronized
//    fun close() {
//        if (ctxHandle != 0L) {
//            nativeFree(ctxHandle)
//            ctxHandle = 0L
//        }
//    }
    suspend fun release() = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            if (ctxHandle != 0L) {
                nativeFree(ctxHandle)
                ctxHandle = 0L
            }
        }
    }

    // ======= Optional helper to copy model from assets at first launch =======
    fun ensureModel(ctx: Context, assetPath: String): String {
        val outFile = File(ctx.filesDir, assetPath.substringAfterLast('/'))
        if (!outFile.exists()) {
            ctx.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return outFile.absolutePath
    }

    // ======= JNI declarations (private) =======
    init {
        // this must match the name in add_library(...)
        System.loadLibrary("whisper_jni")
        // if you also link against c++_shared, you might need:
        // System.loadLibrary("c++_shared")
    }
    private external fun nativeInit(modelPath: String, nThreads: Int): Long
    private external fun nativeRunInference(ctxHandle: Long, pcm: FloatArray): String
    private external fun nativeFree(ctxHandle: Long)
}

