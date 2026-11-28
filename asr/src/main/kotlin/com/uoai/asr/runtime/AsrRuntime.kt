package com.uoai.asr.runtime

import java.util.concurrent.atomic.AtomicBoolean

/** Cooperative cancellation token bridged to native `whisper_full_params.abort_callback`. */
interface AsrAbortSignal {
    val isAborted: Boolean
    fun abort()
}

class DefaultAsrAbortSignal(
    private val onAbort: (() -> Unit)? = null,
) : AsrAbortSignal {
    private val flag = AtomicBoolean(false)
    override val isAborted: Boolean get() = flag.get()

    override fun abort() {
        if (flag.compareAndSet(false, true)) {
            runCatching { onAbort?.invoke() }
        }
    }
}

data class AsrDecodeParams(
    val language: String = "auto",
    val translate: Boolean = false,
    val initialPrompt: String? = null,
    val temperature: Float = 0.0f,
    val threads: Int = 4,
    val timestamps: Boolean = true,
    val singleSegment: Boolean = false,
)

data class AsrSegment(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val noSpeechProb: Float = 0f,
)

data class AsrTranscriptionOutput(
    val text: String,
    val language: String,
    val segments: List<AsrSegment>,
    val languageConfidence: Float = 0f,
)

/**
 * Replaceable ASR native runtime abstraction.
 * Implementations own at most one loaded model instance; only `:asr` may invoke it directly.
 */
interface AsrRuntime {
    val isLoaded: Boolean
    val loadedModelPath: String?
    suspend fun load(modelPath: String)
    suspend fun unload()
    suspend fun transcribe(
        pcm16kMono: FloatArray,
        params: AsrDecodeParams,
        abortSignal: AsrAbortSignal,
        onSegment: ((AsrSegment) -> Unit)? = null,
    ): AsrTranscriptionOutput
}

