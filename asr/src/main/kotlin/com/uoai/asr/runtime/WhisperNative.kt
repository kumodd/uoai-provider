package com.uoai.asr.runtime

/** Callback interface invoked directly from JNI (`whisper_jni.cpp`) as Whisper emits segments. */
interface NativeSegmentListener {
    fun onSegment(index: Int, startMs: Long, endMs: Long, text: String, noSpeechProb: Float)
}

/**
 * Direct JNI bridge to `libwhisper_jni.so` (`whisper.cpp` v1.9.6).
 * Keep method names and signatures strictly synchronized with `asr/src/main/cpp/whisper_jni.cpp`.
 */
object WhisperNative {
    @Volatile private var loaded = false

    fun ensureLibraryLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("whisper_jni")
                nativeInstallLogger()
                loaded = true
            }
        }
    }

    external fun nativeInstallLogger()
    external fun nativeVersion(): String
    external fun nativeInitContext(modelPath: String): Long
    external fun nativeFreeContext(ctxPtr: Long)
    external fun nativeInitState(ctxPtr: Long): Long
    external fun nativeFreeState(statePtr: Long)
    external fun nativeIsMultilingual(ctxPtr: Long): Boolean
    external fun nativeCreateAbortToken(): Long
    external fun nativeSetAbort(tokenPtr: Long)
    external fun nativeFreeAbortToken(tokenPtr: Long)

    external fun nativeFullWithState(
        ctxPtr: Long,
        statePtr: Long,
        audioSamples: FloatArray,
        language: String?,
        translate: Boolean,
        initialPrompt: String?,
        nThreads: Int,
        temperature: Float,
        noTimestamps: Boolean,
        singleSegment: Boolean,
        abortTokenPtr: Long,
        segmentListener: NativeSegmentListener?,
    ): Int

    external fun nativeDetectedLanguage(statePtr: Long): String
    external fun nativeDetectedLanguageProb(statePtr: Long): Float
    external fun nativeSegmentCount(statePtr: Long): Int
    external fun nativeSegmentText(statePtr: Long, index: Int): String
    external fun nativeSegmentStartMs(statePtr: Long, index: Int): Long
    external fun nativeSegmentEndMs(statePtr: Long, index: Int): Long
    external fun nativeSegmentNoSpeechProb(statePtr: Long, index: Int): Float

    const val RC_ABORTED = -99
}

