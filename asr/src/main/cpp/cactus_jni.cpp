#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>

#include "cactus.h"

#define LOG_TAG "UOAI-CactusJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex gCactusMutex;

/**
 * Safely converts arbitrary UTF-8 bytes (including 3-byte Devanagari and 4-byte codepoints)
 * into a Java String via UTF-16 (`env->NewString`) so ART never aborts on Modified UTF-8 checks.
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
            if (i + 1 >= len) break;
            const uint8_t b1 = bytes[i + 1];
            if ((b1 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x1Fu) << 6) | (b1 & 0x3Fu);
                if (cp >= 0x80) utf16.push_back(static_cast<jchar>(cp));
                i += 2;
            } else {
                i += 1;
            }
        } else if ((b0 & 0xF0) == 0xE0) {
            if (i + 2 >= len) break;
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
            if (i + 3 >= len) break;
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
            i += 1;
        }
    }

    if (utf16.empty()) {
        static const jchar empty = 0;
        return env->NewString(&empty, 0);
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string jstringToStd(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * raw = env->GetStringUTFChars(js, nullptr);
    if (!raw) return "";
    std::string out(raw);
    env->ReleaseStringUTFChars(js, raw);
    return out;
}

std::string safeLastError() {
    const char * err = cactus_last_error();
    return (err && err[0] != '\0') ? std::string(err) : std::string("Unknown Cactus runtime error");
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeLoadFromFile(JNIEnv * env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = jstringToStd(env, modelPath);
    if (path.empty()) {
        return utf8ToJString(env, "Model path is empty");
    }

    std::ifstream ifs(path, std::ios::binary | std::ios::ate);
    if (!ifs.is_open()) {
        return utf8ToJString(env, ("Cannot open .cact model file: " + path).c_str());
    }
    const std::streamsize fileSize = ifs.tellg();
    if (fileSize <= 196) {
        return utf8ToJString(env, "Model .cact file is empty or truncated");
    }

    std::vector<unsigned char> buffer(static_cast<size_t>(fileSize));
    ifs.seekg(0, std::ios::beg);
    if (!ifs.read(reinterpret_cast<char *>(buffer.data()), fileSize)) {
        return utf8ToJString(env, "Failed to read .cact model file");
    }
    ifs.close();

    std::lock_guard<std::mutex> lock(gCactusMutex);
    const auto t0 = std::chrono::steady_clock::now();
    const int rc = cactus_load(buffer.data(), static_cast<unsigned long long>(buffer.size()));
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0
    ).count();

    if (rc < 0) {
        const std::string err = safeLastError();
        LOGE("cactus_load failed for %s (size=%lld, rc=%d): %s",
             path.c_str(), static_cast<long long>(fileSize), rc, err.c_str());
        return utf8ToJString(env, err.c_str());
    }

    LOGI("Loaded Cactus model %s (%lld bytes) in %lldms (modelsMask=%d)",
         path.c_str(), static_cast<long long>(fileSize), static_cast<long long>(elapsedMs), cactus_models());
    return nullptr; // null indicates success
}

JNIEXPORT jint JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeLoadedModelsMask(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gCactusMutex);
    return static_cast<jint>(cactus_models());
}

JNIEXPORT void JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeReset(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(gCactusMutex);
    cactus_reset();
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeTranscribe(
    JNIEnv * env,
    jobject /*thiz*/,
    jfloatArray audioSamples,
    jstring language,
    jstring keywords,
    jboolean wordTimestamps
) {
    if (!audioSamples) return utf8ToJString(env, "{\"error\":\"Audio buffer is null\"}");
    const jsize nSamples = env->GetArrayLength(audioSamples);
    if (nSamples <= 0) return utf8ToJString(env, "{\"text\":\"\",\"language\":\"\"}");

    jfloat * samples = env->GetFloatArrayElements(audioSamples, nullptr);
    if (!samples) return utf8ToJString(env, "{\"error\":\"Failed to access audio sample array\"}");

    const std::string langStr = jstringToStd(env, language);
    const std::string kwStr = jstringToStd(env, keywords);
    const char * langPtr = langStr.empty() ? nullptr : langStr.c_str();
    const char * kwPtr = kwStr.empty() ? nullptr : kwStr.c_str();

    const int durSec = static_cast<int>(std::ceil(static_cast<double>(nSamples) / 16000.0));
    const int outCapacity = std::max(262144, (durSec + 4) * 4096);
    std::vector<char> outBuf(static_cast<size_t>(outCapacity), 0);

    int rc = 0;
    long long elapsedMs = 0;
    std::string errMsg;
    {
        std::lock_guard<std::mutex> lock(gCactusMutex);
        const auto t0 = std::chrono::steady_clock::now();
        rc = cactus_transcribe(
            samples,
            static_cast<int>(nSamples),
            langPtr,
            kwPtr,
            (wordTimestamps == JNI_TRUE) ? 1 : 0,
            outBuf.data(),
            outCapacity
        );
        elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0
        ).count();
        if (rc < 0) {
            errMsg = safeLastError();
        }
    }

    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);

    if (rc < 0) {
        LOGE("cactus_transcribe failed (samples=%d, rc=%d): %s", static_cast<int>(nSamples), rc, errMsg.c_str());
        // Prefix with __CACTUS_ERROR__: so Kotlin distinguishes native errors cleanly from valid JSON
        const std::string tagged = "__CACTUS_ERROR__:" + errMsg;
        return utf8ToJString(env, tagged.c_str());
    }

    const double durF = static_cast<double>(nSamples) / 16000.0;
    LOGI("Cactus decoded %.2fs audio in %lldms (RTF=%.2fx, lang=%s)",
         durF, elapsedMs, durF > 0.01 ? (elapsedMs / 1000.0) / durF : 0.0,
         langPtr ? langPtr : "auto");
    return utf8ToJString(env, outBuf.data());
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeStreamProcess(
    JNIEnv * env,
    jobject /*thiz*/,
    jfloatArray audioSamples,
    jstring language,
    jstring keywords
) {
    if (!audioSamples) return utf8ToJString(env, "{\"text\":\"\",\"pending\":\"\"}");
    const jsize nSamples = env->GetArrayLength(audioSamples);
    if (nSamples <= 0) return utf8ToJString(env, "{\"text\":\"\",\"pending\":\"\"}");

    jfloat * samples = env->GetFloatArrayElements(audioSamples, nullptr);
    if (!samples) return utf8ToJString(env, "__CACTUS_ERROR__:Failed to access audio sample array");

    const std::string langStr = jstringToStd(env, language);
    const std::string kwStr = jstringToStd(env, keywords);
    const char * langPtr = langStr.empty() ? nullptr : langStr.c_str();
    const char * kwPtr = kwStr.empty() ? nullptr : kwStr.c_str();

    const int outCapacity = 262144;
    std::vector<char> outBuf(static_cast<size_t>(outCapacity), 0);

    int rc = 0;
    std::string errMsg;
    {
        std::lock_guard<std::mutex> lock(gCactusMutex);
        rc = cactus_stream_transcribe_process(
            samples,
            static_cast<int>(nSamples),
            langPtr,
            kwPtr,
            outBuf.data(),
            outCapacity
        );
        if (rc < 0) errMsg = safeLastError();
    }

    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);

    if (rc < 0) {
        const std::string tagged = "__CACTUS_ERROR__:" + errMsg;
        return utf8ToJString(env, tagged.c_str());
    }
    return utf8ToJString(env, outBuf.data());
}

JNIEXPORT jstring JNICALL
Java_com_uoai_asr_runtime_CactusNative_nativeStreamStop(JNIEnv * env, jobject /*thiz*/) {
    const int outCapacity = 262144;
    std::vector<char> outBuf(static_cast<size_t>(outCapacity), 0);
    int rc = 0;
    std::string errMsg;
    {
        std::lock_guard<std::mutex> lock(gCactusMutex);
        rc = cactus_stream_transcribe_stop(outBuf.data(), outCapacity);
        if (rc < 0) errMsg = safeLastError();
    }
    if (rc < 0) {
        const std::string tagged = "__CACTUS_ERROR__:" + errMsg;
        return utf8ToJString(env, tagged.c_str());
    }
    return utf8ToJString(env, outBuf.data());
}

} // extern "C"
