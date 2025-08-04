#include <jni.h>               // Core JNI types & helpers
#include <android/log.h>       // Logcat output
#include "whisper.h"           // Whisper C API (pulled in via sub‑module)
#include <string>

// ─── Log helpers ──────────────────────────────────────────────────────
#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Small globals ────────────────────────────────────────────────────
static int g_num_threads = 4;  // updated in nativeInit(); reused later

// Convenience: convert Kotlin String → std::string (RAII wrapper)
struct JStringUTF {
    JNIEnv*  env;
    jstring  jstr;
    const char* ptr;
    JStringUTF(JNIEnv* e, jstring s) : env(e), jstr(s), ptr(e->GetStringUTFChars(s, nullptr)) {}
    ~JStringUTF() { if (ptr) env->ReleaseStringUTFChars(jstr, ptr); }
};

// ──────────────────────────────────────────────────────────────────────
// 1. nativeInit  — load Whisper model from file path
// Signature in Kotlin:
//   external fun nativeInit(modelPath: String, nThreads: Int): Long
// Returns a pointer (as long) to whisper_context*.
// ──────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_my_1app_WhisperBridge_nativeInit(
        JNIEnv* env,
        jobject /* this */,          // instance of WhisperBridge (unused)
        jstring modelPath,
        jint    nThreads) {

    JStringUTF path(env, modelPath);
    if (!path.ptr) {
        LOGE("modelPath is null");
        return 0;
    }

    // Load model
    whisper_context* ctx = whisper_init_from_file_with_params(
            path.ptr,
            whisper_context_default_params()
    );
    if (!ctx) {
        LOGE("Failed to load model at %s", path.ptr);
        return 0;
    }

    // Remember thread count for later inference calls
    g_num_threads = (nThreads > 0) ? nThreads : g_num_threads;
    LOGI("Model loaded (ctx=%p, threads=%d)", ctx, g_num_threads);

    // Return opaque handle to Kotlin (as signed 64‑bit)
    return reinterpret_cast<jlong>(ctx);
}

// ──────────────────────────────────────────────────────────────────────
// 2. nativeRunInference  — run STT on one chunk of PCM data
// Signature in Kotlin:
//   external fun nativeRunInference(ctx: Long, pcm: FloatArray): String
// pcm must be mono 16‑kHz float samples in range [‑1, 1].
// ──────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_my_1app_WhisperBridge_nativeRunInference(
        JNIEnv* env,
        jobject /* this */,
        jlong   ctxHandle,
        jfloatArray pcmJava) {

    // Validate arguments
    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx || !pcmJava) {
        LOGE("Invalid context or audio buffer");
        return env->NewStringUTF("");
    }

    // Access Java float[] as C pointer
    jsize nSamples = env->GetArrayLength(pcmJava);
    LOGI("nSamples: %d", nSamples);
    jfloat* pcm    = env->GetFloatArrayElements(pcmJava, nullptr);

    // Prepare default parameters (greedy search) per call
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads   = g_num_threads;
    params.print_realtime = false;
    params.print_progress = false;

    // Run the model
    int err = whisper_full(ctx, params, pcm, nSamples);

    // Release Java buffer (we didn’t modify it → JNI_ABORT)
    env->ReleaseFloatArrayElements(pcmJava, pcm, JNI_ABORT);

    if (err != 0) {
        LOGE("whisper_full() failed: %d", err);
        return env->NewStringUTF("");
    }

    // Collect transcription (all segments concatenated)
    //const char* text = whisper_full_str(ctx);
    //return env->NewStringUTF(text ? text : "");
    //LOGI("BELLOO1: %p", ctx);
    int n_seg = whisper_full_n_segments(ctx);
    std::string result;
    result.reserve(n_seg * 32); // optional—avoid tiny reallocs
    //LOGI("n_seg: %d", n_seg); //surprisingly empty
    for (int i = 0; i < n_seg; ++i) {
        //LOGI("BELLOO2: %p", ctx);
            const char* seg = whisper_full_get_segment_text(ctx, i);
            if (seg && *seg) {
                result += seg;
            }
    }
    LOGI("final string: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

// ──────────────────────────────────────────────────────────────────────
// 3. nativeFree  — free model & its memory
// Signature in Kotlin:
//   external fun nativeFree(ctx: Long)
// ──────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_example_my_1app_WhisperBridge_nativeFree(
        JNIEnv* /* env */,
jobject /* this */,
jlong   ctxHandle) {

auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
if (ctx) {
whisper_free(ctx);
LOGI("Context %p freed", ctx);
}
}