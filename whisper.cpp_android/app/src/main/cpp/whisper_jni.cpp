#include <jni.h>               // Core JNI types & helpers
#include <android/log.h>       // Logcat output
#include "whisper.h"           // Whisper C API (pulled in via sub‑module)
#include <string>
#include <vector>

// ─── Log helpers ──────────────────────────────────────────────────────
#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO , TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── Small globals ────────────────────────────────────────────────────
static int g_num_threads = 8;  // updated in nativeInit(); reused later

// Convenience: convert Kotlin String → std::string (RAII wrapper)
struct JStringUTF {
    JNIEnv*  env;
    jstring  jstr;
    const char* ptr;
    JStringUTF(JNIEnv* e, jstring s) : env(e), jstr(s), ptr(e->GetStringUTFChars(s, nullptr)) {}
    ~JStringUTF() { if (ptr) env->ReleaseStringUTFChars(jstr, ptr); }
};

struct CallbackData {
    JNIEnv*   env;             // valid for this thread
    jobject   listenerGlobal;  // GlobalRef to TranscriptionListener
    jmethodID onPartialMid;    // void onPartial(String)
    jmethodID onCompleteMid;   // void onComplete(String)
};

std::string jstringToStdString(JNIEnv* env, jstring jStr) {
    if (!jStr) return "";
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return out;
}

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



extern "C" JNIEXPORT void JNICALL
Java_com_example_my_1app_WhisperBridge_transcribeStreaming(
        JNIEnv* env,
        jobject /* this */,
        jlong   ctxHandle,
        jfloatArray pcmJava,
        jstring jLang,
        jboolean translate,
        jobject listener /* TranscriptionListener */) {

    //if anything is missing, bail out now
    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx || !pcmJava || !listener) return;

    //raw pointer to the java array of pcm values
    const jsize nSamples = env->GetArrayLength(pcmJava);
    jfloat* pcm = env->GetFloatArrayElements(pcmJava, nullptr);
    if (!pcm) return;

    std::string lang = jstringToStdString(env, jLang);

    // Hold listener across the blocking call
    jobject listenerGlobal = env->NewGlobalRef(listener);
    if (!listenerGlobal) {
        env->ReleaseFloatArrayElements(pcmJava, pcm, JNI_ABORT);
        return;
    }

    //retrieve method IDs for partial and complete
    jclass listenerCls = env->GetObjectClass(listenerGlobal);
    jmethodID onPartialMid  = env->GetMethodID(listenerCls, "onPartial",  "(Ljava/lang/String;)V");
    jmethodID onCompleteMid = env->GetMethodID(listenerCls, "onComplete", "(Ljava/lang/String;)V");
    if (!onPartialMid || !onCompleteMid) {
        env->DeleteGlobalRef(listenerGlobal);
        env->ReleaseFloatArrayElements(pcmJava, pcm, JNI_ABORT);
        return;
    }

    // Prepare params (same knobs you already use)
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads       = g_num_threads;
    params.print_realtime  = false;
    params.print_progress  = false;

    // OPTIONAL knobs to make segments shorter (more frequent callbacks):
    params.max_len        = 20;     // max tokens per segment (0 = inf)
    params.split_on_word  = true;   // avoid splitting inside a word
    params.max_tokens     = 64;      // 0 = auto

    // --- Language handling ---
    if (lang == "auto") {
        // Detect from the first few seconds (don’t overdo it)
        const int sampleRate   = 16000;            // or pass in if variable
        const int detectWindow = sampleRate * 5;   // ~5 s
        const int nDetect      = std::min((int)nSamples, detectWindow);

        std::vector<float> probs(whisper_lang_max_id() + 1, 0.0f);
        int lang_id = whisper_lang_auto_detect(ctx,0, g_num_threads,probs.data());
        if (lang_id >= 0) {
            params.language = whisper_lang_str(lang_id);   // e.g., "es"
        } else {
            params.language = "en"; // fallback
        }
    } else {
        // Validate user selection to avoid typos, e.g., "zh" for Chinese
        if (whisper_lang_id(lang.c_str()) >= 0) {
            params.language = lang.c_str();
        } else {
            params.language = "en"; // fallback if unknown code
        }
    }

    params.translate = translate;

    CallbackData cbData{ env, listenerGlobal, onPartialMid, onCompleteMid };

    // Segment callback: called when n_new final segments are produced
    params.new_segment_callback = [](struct whisper_context* wctx,
                                     struct whisper_state*   wstate,
                                     int n_new,
                                     void* user_data) {
        auto* data = static_cast<CallbackData*>(user_data);
        if (!data || !data->env) return;

        // Query segments from the *state* so we see what was just produced
        const int n_seg = whisper_full_n_segments_from_state(wstate);
        const int first = n_seg - n_new;
        for (int i = first; i < n_seg; ++i) {
            const char* seg_text = whisper_full_get_segment_text_from_state(wstate, i);
            if (!seg_text || *seg_text == '\0') continue;

            jstring jtxt = data->env->NewStringUTF(seg_text);
            if (!jtxt) continue;

            // Send each finalized segment to Kotlin as a "partial" update
            data->env->CallVoidMethod(data->listenerGlobal, data->onPartialMid, jtxt);
            data->env->DeleteLocalRef(jtxt);

            // if (data->env->ExceptionCheck()) data->env->ExceptionClear(); // optional
        }
    };
    params.new_segment_callback_user_data = &cbData;

    // Run the model (blocking). The callback above fires during this call.
    const int err = whisper_full(ctx, params, pcm, nSamples);

    // Release PCM ASAP
    env->ReleaseFloatArrayElements(pcmJava, pcm, JNI_ABORT);

    // Build final string (concatenate all segments)
    std::string final_text;
    if (err == 0) {
        const int n_seg_final = whisper_full_n_segments(ctx);
        final_text.reserve(n_seg_final * 32);
        for (int i = 0; i < n_seg_final; ++i) {
            const char* seg = whisper_full_get_segment_text(ctx, i);
            if (seg && *seg) final_text += seg;
        }
    } else {
        // If it failed, send empty final text (or you could skip)
        // LOGE("whisper_full failed: %d", err);
    }

    jstring jfull = env->NewStringUTF(final_text.c_str());
    if (jfull) {
        env->CallVoidMethod(listenerGlobal, onCompleteMid, jfull);
        env->DeleteLocalRef(jfull);
    }

    env->DeleteGlobalRef(listenerGlobal);
}

// ──────────────────────────────────────────────────────────────────────
// 2. nativeRunInference  — run STT on one chunk of PCM data
// Signature in Kotlin:
//   external fun nativeRunInference(ctx: Long, pcm: FloatArray): String
// pcm must be mono 16‑kHz float samples in range [‑1, 1].
//// ──────────────────────────────────────────────────────────────────────
//extern "C"
//JNIEXPORT jstring JNICALL
//Java_com_example_my_1app_WhisperBridge_nativeRunInference(
//        JNIEnv* env,
//        jobject /* this */,
//        jlong   ctxHandle,
//        jfloatArray pcmJava) {
//
//    // Validate arguments
//    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
//    if (!ctx || !pcmJava) {
//        LOGE("Invalid context or audio buffer");
//        return env->NewStringUTF("");
//    }
//
//    // Access Java float[] as C pointer
//    jsize nSamples = env->GetArrayLength(pcmJava);
//    jfloat* pcm    = env->GetFloatArrayElements(pcmJava, nullptr);
//
//    // Prepare default parameters (greedy search) per call
//    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
//    params.n_threads   = g_num_threads;
//    params.print_realtime = false;
//    params.print_progress = false;
//
//    // Run the model
//    int err = whisper_full(ctx, params, pcm, nSamples);
//
//    // Release Java buffer (we didn’t modify it → JNI_ABORT)
//    env->ReleaseFloatArrayElements(pcmJava, pcm, JNI_ABORT);
//
//    if (err != 0) {
//        LOGE("whisper_full() failed: %d", err);
//        return env->NewStringUTF("");
//    }
//
//    // Collect transcription (all segments concatenated)
//    //const char* text = whisper_full_str(ctx);
//    //return env->NewStringUTF(text ? text : "");
//    //LOGI("BELLOO1: %p", ctx);
//    int n_seg = whisper_full_n_segments(ctx);
//    std::string result;
//    result.reserve(n_seg * 32); // optional—avoid tiny reallocs
//    //LOGI("n_seg: %d", n_seg); //surprisingly empty
//    for (int i = 0; i < n_seg; ++i) {
//        //LOGI("BELLOO2: %p", ctx);
//            const char* seg = whisper_full_get_segment_text(ctx, i);
//            if (seg && *seg) {
//                result += seg;
//            }
//    }
//    LOGI("final string: %s", result.c_str());
//    return env->NewStringUTF(result.c_str());
//}

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