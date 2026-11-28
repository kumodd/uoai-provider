package com.uoai.asr.runtime

/**
 * Direct JNI bridge to `libcactus_jni.so` (statically linking Cactus 4.0.0 `libcactus.a`, Apache-2.0).
 * Supports AksharSTT v4, AksharSTT Lite v4, and Shruti `.cact` speech models natively on `arm64-v8a`.
 */
object CactusNative {
    const val MASK_SPEECH = 2
    const val ERROR_PREFIX = "__CACTUS_ERROR__:"

    @Volatile private var loaded = false

    fun ensureLibraryLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("cactus_jni")
                loaded = true
            }
        }
    }

    /** Returns `null` on success, or an error description on failure. */
    external fun nativeLoadFromFile(modelPath: String): String?
    external fun nativeLoadedModelsMask(): Int
    external fun nativeReset()

    /** Returns raw JSON string from `cactus_transcribe`, or string prefixed with [ERROR_PREFIX]. */
    external fun nativeTranscribe(
        audioSamples: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean,
    ): String

    external fun nativeStreamProcess(
        audioSamples: FloatArray,
        language: String?,
        keywords: String?,
    ): String

    external fun nativeStreamStop(): String
}
