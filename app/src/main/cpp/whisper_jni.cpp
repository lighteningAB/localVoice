#include <jni.h>
#include <string>
#include <android/log.h>
#include "whisper.h"
#include "ggml.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void whisper_log_cb(ggml_log_level level, const char *text, void * /*user_data*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    __android_log_write(prio, "whisper.cpp", text);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_localvoice_whisper_WhisperContext_nativeInit(
    JNIEnv *env, jclass /*clazz*/, jstring jpath) {
    whisper_log_set(whisper_log_cb, nullptr);
    ggml_log_set(whisper_log_cb, nullptr);

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("loading model: %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params returned null");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_example_localvoice_whisper_WhisperContext_nativeTranscribe(
    JNIEnv *env, jclass /*clazz*/, jlong ptr,
    jfloatArray jsamples, jstring jlanguage, jint nThreads) {

    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");

    const jsize nSamples = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.translate        = false;
    params.detect_language  = false; // we want transcription, not just lang probe
    params.n_threads        = nThreads > 0 ? nThreads : 4;

    const char *language = env->GetStringUTFChars(jlanguage, nullptr);
    std::string langStr(language);
    env->ReleaseStringUTFChars(jlanguage, language);

    // "auto" -> let whisper auto-detect (handled internally when language is "auto")
    params.language = langStr.c_str();

    LOGI("transcribing %d samples (lang=%s, threads=%d)",
         nSamples, langStr.c_str(), params.n_threads);

    int rc = whisper_full(ctx, params, samples, nSamples);
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed: %d", rc);
        return env->NewStringUTF("");
    }

    std::string out;
    const int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; i++) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg) out += seg;
    }
    LOGI("done: %d segments, %zu chars", n, out.size());
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_example_localvoice_whisper_WhisperContext_nativeGetDetectedLanguage(
    JNIEnv *env, jclass /*clazz*/, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");
    int id = whisper_full_lang_id(ctx);
    if (id < 0) return env->NewStringUTF("");
    const char *code = whisper_lang_str(id);
    return env->NewStringUTF(code ? code : "");
}

JNIEXPORT void JNICALL
Java_com_example_localvoice_whisper_WhisperContext_nativeFree(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx) whisper_free(ctx);
}

} // extern "C"
