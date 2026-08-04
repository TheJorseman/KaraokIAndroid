// Stub implementation of the JNI surface declared in `whisper_jni.cpp`.
//
// Until the whisper.cpp sources are vendored as a submodule, this
// stub returns empty / error responses. The Kotlin bridge treats any
// return code != 0 as a failure; the rest of the pipeline therefore
// refuses to start transcription and the user sees a clear error.
//
// Once whisper.cpp is wired in, this file is replaced with a real
// implementation that calls the upstream `whisper_*` API. The JNI
// surface stays identical so the Kotlin side is not affected.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>

#define LOG_TAG "karaokei_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct WhisperContext {
    std::string lastError;
};

std::string jstringToString(JNIEnv *env, jstring s) {
    if (s == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeInit(
    JNIEnv *env,
    jclass /* clazz */,
    jstring jModelPath,
    jint numThreads
) {
    auto *ctx = new WhisperContext();
    ctx->lastError = "whisper.cpp sources not yet vendored; rebuild after wiring third_party/whisper.cpp";
    LOGE("nativeInit stub: %s (model=%s, threads=%d)",
        ctx->lastError.c_str(),
        jstringToString(env, jModelPath).c_str(),
        numThreads);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeFree(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle
) {
    auto *ctx = reinterpret_cast<WhisperContext *>(handle);
    if (ctx != nullptr) delete ctx;
}

JNIEXPORT jint JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeTranscribeFile(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle,
    jstring jWavPath,
    jstring jLanguage,
    jboolean translate,
    jobject jCallback
) {
    auto *ctx = reinterpret_cast<WhisperContext *>(handle);
    if (ctx == nullptr) return -1;
    LOGE("nativeTranscribeFile stub: %s (wav=%s, lang=%s, translate=%d)",
        ctx->lastError.c_str(),
        jstringToString(env, jWavPath).c_str(),
        jstringToString(env, jLanguage).c_str(),
        (int) translate);
    return -1;
}

JNIEXPORT jstring JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeLastError(
    JNIEnv *env,
    jclass /* clazz */,
    jlong handle
) {
    auto *ctx = reinterpret_cast<WhisperContext *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(ctx->lastError.c_str());
}

} // extern "C"
