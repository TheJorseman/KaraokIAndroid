#include <jni.h>
#include <android/log.h>
#include <whisper.h>

#include <cstdint>
#include <fstream>
#include <string>
#include <vector>

namespace {

constexpr const char * TAG = "karaokei_whisper";

struct NativeContext {
    whisper_context * context = nullptr;
    std::string last_error;
};

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool read_pcm16_wav(const std::string & path, std::vector<float> & output) {
    std::ifstream file(path, std::ios::binary);
    if (!file) return false;

    char riff[4]{};
    char wave[4]{};
    file.read(riff, 4);
    file.seekg(4, std::ios::cur);
    file.read(wave, 4);
    if (std::string(riff, 4) != "RIFF" || std::string(wave, 4) != "WAVE") return false;

    uint16_t channels = 0;
    uint16_t bits = 0;
    uint32_t sample_rate = 0;
    std::vector<uint8_t> pcm;
    while (file && !file.eof()) {
        char id[4]{};
        uint32_t size = 0;
        file.read(id, 4);
        file.read(reinterpret_cast<char *>(&size), sizeof(size));
        if (!file) break;
        const std::string chunk(id, 4);
        if (chunk == "fmt ") {
            uint16_t format = 0;
            file.read(reinterpret_cast<char *>(&format), sizeof(format));
            file.read(reinterpret_cast<char *>(&channels), sizeof(channels));
            file.read(reinterpret_cast<char *>(&sample_rate), sizeof(sample_rate));
            file.seekg(6, std::ios::cur);
            file.read(reinterpret_cast<char *>(&bits), sizeof(bits));
            if (size > 16) file.seekg(size - 16, std::ios::cur);
            if (format != 1 || sample_rate != 16000 || bits != 16) return false;
        } else if (chunk == "data") {
            pcm.resize(size);
            file.read(reinterpret_cast<char *>(pcm.data()), size);
        } else {
            file.seekg(size, std::ios::cur);
        }
    }
    if (channels == 0 || pcm.empty()) return false;

    const size_t frame_count = pcm.size() / (2 * channels);
    output.resize(frame_count);
    for (size_t frame = 0; frame < frame_count; ++frame) {
        float sum = 0.0f;
        for (uint16_t channel = 0; channel < channels; ++channel) {
            const size_t offset = (frame * channels + channel) * 2;
            const int16_t sample = static_cast<int16_t>(
                static_cast<uint16_t>(pcm[offset]) |
                (static_cast<uint16_t>(pcm[offset + 1]) << 8));
            sum += static_cast<float>(sample) / 32768.0f;
        }
        output[frame] = sum / static_cast<float>(channels);
    }
    return true;
}

void call_error(JNIEnv * env, jobject callback, const std::string & message) {
    jclass klass = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(klass, "onError", "(Ljava/lang/String;)V");
    if (method != nullptr) {
        jstring text = env->NewStringUTF(message.c_str());
        env->CallVoidMethod(callback, method, text);
        env->DeleteLocalRef(text);
    }
    env->DeleteLocalRef(klass);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeInit(
    JNIEnv * env, jclass, jstring model_path, jint num_threads) {
    auto * native = new NativeContext();
    const std::string path = jstring_to_string(env, model_path);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    native->context = whisper_init_from_file_with_params(path.c_str(), params);
    if (native->context == nullptr) {
        native->last_error = "whisper_init_from_file_with_params failed: " + path;
        delete native;
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "loaded whisper model with %d threads", num_threads);
    return reinterpret_cast<jlong>(native);
}

JNIEXPORT void JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * native = reinterpret_cast<NativeContext *>(handle);
    if (native == nullptr) return;
    whisper_free(native->context);
    delete native;
}

JNIEXPORT jint JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeTranscribeFile(
    JNIEnv * env, jclass, jlong handle, jstring wav_path, jstring language,
    jboolean translate, jobject callback) {
    auto * native = reinterpret_cast<NativeContext *>(handle);
    if (native == nullptr || native->context == nullptr) return -1;

    std::vector<float> samples;
    if (!read_pcm16_wav(jstring_to_string(env, wav_path), samples)) {
        call_error(env, callback, "expected a 16-bit mono/PCM WAV at 16 kHz");
        return -2;
    }

    const std::string language_value = jstring_to_string(env, language);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.no_timestamps = false;
    params.token_timestamps = true;
    params.split_on_word = true;
    params.translate = translate == JNI_TRUE;
    params.language = language_value.empty() ? nullptr : language_value.c_str();
    params.detect_language = language_value.empty() || language_value == "auto";
    params.n_threads = 2;

    const int result = whisper_full(native->context, params, samples.data(), static_cast<int>(samples.size()));
    if (result != 0) {
        call_error(env, callback, "whisper_full failed with code " + std::to_string(result));
        return result;
    }

    jclass callback_class = env->GetObjectClass(callback);
    const jmethodID language_method = env->GetMethodID(
        callback_class, "onLanguageDetected", "(Ljava/lang/String;)V");
    const jmethodID segment_method = env->GetMethodID(
        callback_class, "onNativeSegment", "(Ljava/lang/String;JJLjava/lang/String;FLjava/lang/String;)V");
    const jmethodID completed_method = env->GetMethodID(callback_class, "onCompleted", "()V");

    const char * language_name = whisper_lang_str(whisper_full_lang_id(native->context));
    if (language_method != nullptr && language_name != nullptr) {
        jstring value = env->NewStringUTF(language_name);
        env->CallVoidMethod(callback, language_method, value);
        env->DeleteLocalRef(value);
    }

    const int segments = whisper_full_n_segments(native->context);
    for (int segment = 0; segment < segments; ++segment) {
        const int64_t start = whisper_full_get_segment_t0(native->context, segment) * 10;
        const int64_t end = whisper_full_get_segment_t1(native->context, segment) * 10;
        const char * segment_text = whisper_full_get_segment_text(native->context, segment);
        std::string words;
        const int token_count = whisper_full_n_tokens(native->context, segment);
        for (int token = 0; token < token_count; ++token) {
            const char * token_text = whisper_full_get_token_text(native->context, segment, token);
            const whisper_token_data data = whisper_full_get_token_data(native->context, segment, token);
            if (token_text == nullptr || token_text[0] == '<') continue;
            words += token_text;
            words += '|';
            words += std::to_string(data.t0 * 10);
            words += '|';
            words += std::to_string(data.t1 * 10);
            words += '|';
            words += std::to_string(data.p);
            words += '\n';
        }
        if (segment_method != nullptr) {
            jstring text = env->NewStringUTF(segment_text == nullptr ? "" : segment_text);
            jstring lang = env->NewStringUTF(language_name == nullptr ? "unknown" : language_name);
            jstring word_data = env->NewStringUTF(words.c_str());
            env->CallVoidMethod(callback, segment_method, text, start, end, lang,
                                whisper_full_get_segment_no_speech_prob(native->context, segment), word_data);
            env->DeleteLocalRef(text);
            env->DeleteLocalRef(lang);
            env->DeleteLocalRef(word_data);
        }
    }
    if (completed_method != nullptr) env->CallVoidMethod(callback, completed_method);
    env->DeleteLocalRef(callback_class);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_karaokei_core_whisper_WhisperBridge_nativeLastError(JNIEnv * env, jclass, jlong handle) {
    auto * native = reinterpret_cast<NativeContext *>(handle);
    return env->NewStringUTF(native == nullptr ? "" : native->last_error.c_str());
}

} // extern "C"
