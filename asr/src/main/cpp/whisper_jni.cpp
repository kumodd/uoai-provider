#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "whisper.h"

#define LOG_TAG "UOAI-WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/**
 * Safely converts arbitrary UTF-8 bytes from Whisper byte-level BPE output into a Java String
 * via UTF-16 (`env->NewString`). Never triggers ART's Modified-UTF-8 `NewStringUTF` abort on
 * 4-byte codepoints or truncated multi-byte sequences (e.g. Devanagari/Indic split across BPE tokens).
 */
jstring utf8ToJString(JNIEnv * env, const char * utf8) {
    if (!utf8 || utf8[0] == '\0') {
        static const jchar empty = 0;
        return env->NewString(&empty, 0);
    }
    const auto * bytes = reinterpret_cast<const uint8_t *>(utf8);
    const size_t len = std::strlen(utf8);
    std::vector<jchar> utf16;
    utf16.reserve(len);

    size_t i = 0;
    while (i < len) {
        const uint8_t b0 = bytes[i];
        if (b0 < 0x80) {
            utf16.push_back(static_cast<jchar>(b0));
            i += 1;
        } else if ((b0 & 0xE0) == 0xC0) {
            if (i + 1 >= len) break; // Drop truncated trailing sequence cleanly
            const uint8_t b1 = bytes[i + 1];
            if ((b1 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x1Fu) << 6) | (b1 & 0x3Fu);
                if (cp >= 0x80) {
                    utf16.push_back(static_cast<jchar>(cp));
                }
                i += 2;
            } else {
                i += 1; // Skip stray byte
            }
        } else if ((b0 & 0xF0) == 0xE0) {
            if (i + 2 >= len) break; // Drop truncated trailing 3-byte char (e.g. Devanagari) cleanly
            const uint8_t b1 = bytes[i + 1];
            const uint8_t b2 = bytes[i + 2];
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x0Fu) << 12) | ((b1 & 0x3Fu) << 6) | (b2 & 0x3Fu);
                if (cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                    utf16.push_back(static_cast<jchar>(cp));
                }
                i += 3;
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF8) == 0xF0) {
            if (i + 3 >= len) break; // Drop truncated trailing 4-byte char cleanly
            const uint8_t b1 = bytes[i + 1];
            const uint8_t b2 = bytes[i + 2];
            const uint8_t b3 = bytes[i + 3];
            if ((b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80 && (b3 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x07u) << 18) | ((b1 & 0x3Fu) << 12) |
                              ((b2 & 0x3Fu) << 6) | (b3 & 0x3Fu);
                if (cp >= 0x10000 && cp <= 0x10FFFF) {
                    cp -= 0x10000;
                    utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
                    utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
                }
                i += 4;
            } else {
                i += 1;
            }
        } else {
            i += 1; // Skip invalid continuation/lead byte
        }
    }

    if (utf16.empty()) {
        static const jchar empty = 0;
        return env->NewString(&empty, 0);
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

void android_whisper_log(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("%s", text);
    }
}

struct AbortToken {
    std::atomic<bool> aborted{false};
};

std::mutex gLangProbMutex;
std::unordered_map<jlong, float> gLastLangProb;

void storeLastLangProb(jlong statePtr, float prob) {
    std::lock_guard<std::mutex> lock(gLangProbMutex);
    gLastLangProb[statePtr] = prob;
}

float loadLastLangProb(jlong statePtr) {
    std::lock_guard<std::mutex> lock(gLangProbMutex);
    auto it = gLastLangProb.find(statePtr);
    return it != gLastLangProb.end() ? it->second : 0.0f;
}

void clearLastLangProb(jlong statePtr) {
    std::lock_guard<std::mutex> lock(gLangProbMutex);
    gLastLangProb.erase(statePtr);
}

std::vector<int> parseAllowedLanguageIds(const std::string & spec) {
    std::vector<int> ids;
    size_t start = 0;
    while (start < spec.size()) {
        size_t comma = spec.find(',', start);
        if (comma == std::string::npos) comma = spec.size();
        size_t a = start;
        size_t b = comma;
        while (a < b && std::isspace(static_cast<unsigned char>(spec[a]))) ++a;
        while (b > a && std::isspace(static_cast<unsigned char>(spec[b - 1]))) --b;
        if (b > a) {
            std::string code = spec.substr(a, b - a);
            for (char & c : code) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            if (code != "auto") {
                int id = whisper_lang_id(code.c_str());
                if (id >= 0 && std::find(ids.begin(), ids.end(), id) == ids.end()) {
                    ids.push_back(id);
                }
            }
        }
        start = comma + 1;
    }
    return ids;
}

bool whisper_abort_cb(void * user_data) {
    auto * token = reinterpret_cast<AbortToken *>(user_data);
    return token != nullptr && token->aborted.load(std::memory_order_acquire);
}

bool whisper_encoder_begin_cb(struct whisper_context * /*ctx*/, struct whisper_state * /*state*/, void * user_data) {
    // Return false to abort before encoder computation starts.
    return !whisper_abort_cb(user_data);
}

struct SegmentCallbackEnv {
    JNIEnv * env;
    jobject listener;
    jmethodID onSegmentMethod;
    AbortToken * abortToken;
    int lastEmittedCount;
};

void whisper_segment_cb(struct whisper_context * /*ctx*/, struct whisper_state * state, int n_new, void * user_data) {
    auto * cb = reinterpret_cast<SegmentCallbackEnv *>(user_data);
    if (!cb || !cb->env || !cb->listener || !cb->onSegmentMethod) return;
    if (cb->abortToken && cb->abortToken->aborted.load(std::memory_order_acquire)) return;

    const int total = whisper_full_n_segments_from_state(state);
    const int startIdx = total - n_new;
    if (startIdx < 0) return;

    for (int i = startIdx; i < total; ++i) {
        if (cb->abortToken && cb->abortToken->aborted.load(std::memory_order_acquire)) break;
        const char * text = whisper_full_get_segment_text_from_state(state, i);
        const int64_t t0Ms = whisper_full_get_segment_t0_from_state(state, i) * 10LL;
        const int64_t t1Ms = whisper_full_get_segment_t1_from_state(state, i) * 10LL;
        const float noSpeech = whisper_full_get_segment_no_speech_prob_from_state(state, i);

        jstring jText = utf8ToJString(cb->env, text);
        if (jText != nullptr) {
            cb->env->CallVoidMethod(cb->listener, cb->onSegmentMethod, (jint) i, (jlong) t0Ms, (jlong) t1Ms, jText, (jfloat) noSpeech);
            cb->env->DeleteLocalRef(jText);
        }
        if (cb->env->ExceptionCheck()) {
            cb->env->ExceptionClear();
            if (cb->abortToken) cb->abortToken->aborted.store(true, std::memory_order_release);
            break;
        }
    }
    cb->lastEmittedCount = total;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeInstallLogger(JNIEnv * /*env*/, jobject /*thiz*/) {
    whisper_log_set(android_whisper_log, nullptr);
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeVersion(JNIEnv * env, jobject /*thiz*/) {
    const char * v = whisper_version();
    return env->NewStringUTF(v ? v : "unknown");
}

JNIEXPORT jlong JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeInitContext(JNIEnv * env, jobject /*thiz*/, jstring modelPath) {
    if (!modelPath) return 0L;
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0L;

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = true;

    struct whisper_context * ctx = whisper_init_from_file_with_params_no_state(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params_no_state failed");
        return 0L;
    }
    LOGI("Loaded whisper context (multilingual=%d, n_vocab=%d)",
         whisper_is_multilingual(ctx), whisper_model_n_vocab(ctx));
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeFreeContext(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto * ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx) {
        whisper_free(ctx);
    }
}

JNIEXPORT jlong JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeInitState(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto * ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (!ctx) return 0L;
    struct whisper_state * state = whisper_init_state(ctx);
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeFreeState(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr) {
    clearLastLangProb(statePtr);
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    if (state) {
        whisper_free_state(state);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeIsMultilingual(JNIEnv * /*env*/, jobject /*thiz*/, jlong ctxPtr) {
    auto * ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    return (ctx != nullptr && whisper_is_multilingual(ctx) != 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeCreateAbortToken(JNIEnv * /*env*/, jobject /*thiz*/) {
    auto * token = new AbortToken();
    return reinterpret_cast<jlong>(token);
}

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSetAbort(JNIEnv * /*env*/, jobject /*thiz*/, jlong tokenPtr) {
    auto * token = reinterpret_cast<AbortToken *>(tokenPtr);
    if (token) {
        token->aborted.store(true, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeFreeAbortToken(JNIEnv * /*env*/, jobject /*thiz*/, jlong tokenPtr) {
    auto * token = reinterpret_cast<AbortToken *>(tokenPtr);
    delete token;
}

JNIEXPORT jint JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeFullWithState(
    JNIEnv * env,
    jobject /*thiz*/,
    jlong ctxPtr,
    jlong statePtr,
    jfloatArray audioSamples,
    jstring language,
    jboolean translate,
    jstring initialPrompt,
    jint nThreads,
    jfloat temperature,
    jboolean noTimestamps,
    jboolean singleSegment,
    jlong abortTokenPtr,
    jobject segmentListener
) {
    auto * ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    auto * abortToken = reinterpret_cast<AbortToken *>(abortTokenPtr);
    if (!ctx || !state || !audioSamples) return -1;

    const jsize nSamples = env->GetArrayLength(audioSamples);
    if (nSamples <= 0) return 0;

    jfloat * samples = env->GetFloatArrayElements(audioSamples, nullptr);
    if (!samples) return -2;

    std::string langStr;
    if (language != nullptr) {
        const char * raw = env->GetStringUTFChars(language, nullptr);
        if (raw) {
            langStr.assign(raw);
            env->ReleaseStringUTFChars(language, raw);
        }
    }

    std::string promptStr;
    if (initialPrompt != nullptr) {
        const char * raw = env->GetStringUTFChars(initialPrompt, nullptr);
        if (raw) {
            promptStr.assign(raw);
            env->ReleaseStringUTFChars(initialPrompt, raw);
        }
    }

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.n_threads = nThreads > 0 ? nThreads : 4;
    wparams.translate = (translate == JNI_TRUE);
    wparams.no_context = true;
    // Always keep internal Whisper timestamp tokens enabled so the decoder anchors to audio
    // end-time and terminates cleanly with <|endoftext|> instead of looping.
    // (Whether timestamps are returned to API callers is controlled in Kotlin.)
    (void) noTimestamps;
    wparams.no_timestamps = false;
    wparams.single_segment = (singleSegment == JNI_TRUE);
    wparams.print_special = false;
    wparams.print_progress = false;
    wparams.print_realtime = false;
    wparams.print_timestamps = false;
    wparams.suppress_blank = true;
    wparams.suppress_nst = true;
    wparams.temperature = temperature;
    // Keep whisper.cpp's entropy/logprob hallucination fallback active with a fast 0.4 step:
    // clean speech finishes in 1 single pass at temp=0.0 (zero overhead), while repetition
    // loops trigger entropy_thold and retry at 0.4 / 0.8 instead of emitting garbage.
    wparams.temperature_inc = 0.4f;
    wparams.entropy_thold = 2.2f;
    wparams.logprob_thold = -1.0f;
    wparams.no_speech_thold = 0.6f;
    wparams.max_initial_ts = 1.0f;
    wparams.greedy.best_of = 1;

    // Always use Whisper's trained 1500-frame encoder context (audio_ctx = 0).
    // Experimental audio_ctx truncation (< 1500) breaks sinusoidal positional embeddings
    // on tiny/base/small models and causes severe repetition-loop hallucinations.
    wparams.audio_ctx = 0;

    // Cap max tokens proportionally on short clips (< 16s) with generous headroom for
    // byte-level BPE multi-byte scripts (e.g. Hindi/Devanagari uses ~2-3 BPE tokens/char).
    const double durSec = static_cast<double>(nSamples) / 16000.0;
    if (durSec < 16.0) {
        wparams.max_tokens = std::clamp(static_cast<int>(std::ceil(durSec * 28.0)) + 48, 80, 320);
    } else {
        wparams.max_tokens = 0;
    }

    std::string resolvedLangStr;
    float detectedLangProb = 1.0f;

    const bool isAllowlist = langStr.find(',') != std::string::npos;
    const bool isAuto = langStr.empty() || langStr == "auto";

    if ((isAllowlist || isAuto) && whisper_is_multilingual(ctx)) {
        std::vector<int> allowedIds = isAllowlist ? parseAllowedLanguageIds(langStr) : std::vector<int>{};
        if (isAllowlist && allowedIds.size() == 1) {
            const char * singleCode = whisper_lang_str(allowedIds[0]);
            resolvedLangStr = singleCode ? singleCode : "en";
            detectedLangProb = 1.0f;
            wparams.language = resolvedLangStr.c_str();
        } else {
            if (abortToken != nullptr && abortToken->aborted.load(std::memory_order_acquire)) {
                env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);
                return -99;
            }
            if (whisper_pcm_to_mel_with_state(ctx, state, samples, static_cast<int>(nSamples), wparams.n_threads) == 0) {
                const int maxLangId = whisper_lang_max_id();
                std::vector<float> probs(static_cast<size_t>(maxLangId + 1), 0.0f);
                const int topId = whisper_lang_auto_detect_with_state(ctx, state, 0, wparams.n_threads, probs.data());
                if (abortToken != nullptr && abortToken->aborted.load(std::memory_order_acquire)) {
                    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);
                    return -99;
                }
                if (topId >= 0) {
                    int chosenId = topId;
                    float chosenProb = (topId <= maxLangId) ? probs[topId] : 0.0f;

                    if (isAllowlist && !allowedIds.empty()) {
                        // Acoustically, conversational Hindi and Urdu overlap heavily.
                        // When Hindi is allowed and Urdu is NOT allowed, pool Urdu probability into Hindi
                        // so Hinglish/Hindustani utterances choose Devanagari Hindi rather than garbled Latin.
                        const int hiId = whisper_lang_id("hi");
                        const int urId = whisper_lang_id("ur");
                        const bool hiAllowed = hiId >= 0 && std::find(allowedIds.begin(), allowedIds.end(), hiId) != allowedIds.end();
                        const bool urAllowed = urId >= 0 && std::find(allowedIds.begin(), allowedIds.end(), urId) != allowedIds.end();

                        float bestScore = -1.0f;
                        int bestAllowed = allowedIds[0];
                        for (int cand : allowedIds) {
                            if (cand < 0 || cand > maxLangId) continue;
                            float score = probs[cand];
                            if (cand == hiId && hiAllowed && !urAllowed && urId >= 0 && urId <= maxLangId) {
                                score += probs[urId];
                            }
                            if (score > bestScore) {
                                bestScore = score;
                                bestAllowed = cand;
                            }
                        }
                        chosenId = bestAllowed;
                        chosenProb = std::clamp(bestScore, 0.0f, 1.0f);
                    }

                    const char * chosenCode = whisper_lang_str(chosenId);
                    resolvedLangStr = chosenCode ? chosenCode : "en";
                    detectedLangProb = chosenProb;
                    wparams.language = resolvedLangStr.c_str();
                    LOGI("Lang detect (%s) -> %s (p=%.2f)",
                         isAllowlist ? langStr.c_str() : "auto",
                         resolvedLangStr.c_str(),
                         detectedLangProb);
                } else {
                    wparams.language = isAllowlist && !allowedIds.empty()
                        ? whisper_lang_str(allowedIds[0])
                        : "auto";
                    detectedLangProb = 0.0f;
                }
            } else {
                wparams.language = "auto";
                detectedLangProb = 0.0f;
            }
        }
    } else if (isAuto) {
        wparams.language = "en";
        detectedLangProb = 1.0f;
    } else {
        wparams.language = langStr.c_str();
        detectedLangProb = 1.0f;
    }
    storeLastLangProb(statePtr, detectedLangProb);

    if (!promptStr.empty()) {
        wparams.initial_prompt = promptStr.c_str();
    }

    if (abortToken != nullptr) {
        wparams.abort_callback = whisper_abort_cb;
        wparams.abort_callback_user_data = abortToken;
        wparams.encoder_begin_callback = whisper_encoder_begin_cb;
        wparams.encoder_begin_callback_user_data = abortToken;
    }

    SegmentCallbackEnv cbEnv{env, nullptr, nullptr, abortToken, 0};
    if (segmentListener != nullptr) {
        jclass cls = env->GetObjectClass(segmentListener);
        if (cls != nullptr) {
            jmethodID mid = env->GetMethodID(cls, "onSegment", "(IJJLjava/lang/String;F)V");
            env->DeleteLocalRef(cls);
            if (mid != nullptr) {
                cbEnv.listener = segmentListener;
                cbEnv.onSegmentMethod = mid;
                wparams.new_segment_callback = whisper_segment_cb;
                wparams.new_segment_callback_user_data = &cbEnv;
            }
        }
    }

    const auto t0 = std::chrono::steady_clock::now();
    const int rc = whisper_full_with_state(ctx, state, wparams, samples, static_cast<int>(nSamples));
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0
    ).count();
    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);

    if (abortToken != nullptr && abortToken->aborted.load(std::memory_order_acquire)) {
        return -99; // Explicit cooperative cancellation code
    }
    const int nSeg = whisper_full_n_segments_from_state(state);
    LOGI("Decoded %.2fs audio in %lldms (RTF=%.2fx, segs=%d, max_tok=%d, threads=%d, rc=%d)",
         durSec, static_cast<long long>(elapsedMs),
         durSec > 0.01 ? (elapsedMs / 1000.0) / durSec : 0.0,
         nSeg, wparams.max_tokens, wparams.n_threads, rc);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeDetectedLanguage(JNIEnv * env, jobject /*thiz*/, jlong statePtr) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    if (!state) return env->NewStringUTF("auto");
    const int langId = whisper_full_lang_id_from_state(state);
    const char * code = (langId >= 0) ? whisper_lang_str(langId) : nullptr;
    return env->NewStringUTF(code ? code : "auto");
}

JNIEXPORT jfloat JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeDetectedLanguageProb(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr) {
    return loadLastLangProb(statePtr);
}

JNIEXPORT jint JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSegmentCount(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    return state ? whisper_full_n_segments_from_state(state) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSegmentText(JNIEnv * env, jobject /*thiz*/, jlong statePtr, jint index) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    if (!state) return utf8ToJString(env, "");
    const char * text = whisper_full_get_segment_text_from_state(state, index);
    return utf8ToJString(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSegmentStartMs(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr, jint index) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    return state ? whisper_full_get_segment_t0_from_state(state, index) * 10LL : 0L;
}

JNIEXPORT jlong JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSegmentEndMs(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr, jint index) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    return state ? whisper_full_get_segment_t1_from_state(state, index) * 10LL : 0L;
}

JNIEXPORT jfloat JNICALL
Java_com_uoai_asr_runtime_WhisperNative_nativeSegmentNoSpeechProb(JNIEnv * /*env*/, jobject /*thiz*/, jlong statePtr, jint index) {
    auto * state = reinterpret_cast<struct whisper_state *>(statePtr);
    return state ? whisper_full_get_segment_no_speech_prob_from_state(state, index) : 0.0f;
}

} // extern "C"

