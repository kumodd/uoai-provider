package com.uoai.asr.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Deterministic JVM-testable fake ASR runtime (never used in production). */
class MockAsrRuntime(
    private val segmentDelayMs: Long = 0L,
    private val segmentCount: Int = 2,
    private val loadFailWith: Throwable? = null,
) : AsrRuntime {
    @Volatile override var isLoaded: Boolean = false
        private set
    @Volatile override var loadedModelPath: String? = null
        private set

    var loadCount: Int = 0
        private set
    var unloadCount: Int = 0
        private set
    var lastParams: AsrDecodeParams? = null
        private set

    override suspend fun load(modelPath: String) {
        loadFailWith?.let { throw it }
        isLoaded = true
        loadedModelPath = modelPath
        loadCount++
    }

    override suspend fun unload() {
        isLoaded = false
        loadedModelPath = null
        unloadCount++
    }

    override suspend fun transcribe(
        pcm16kMono: FloatArray,
        params: AsrDecodeParams,
        abortSignal: AsrAbortSignal,
        onSegment: ((AsrSegment) -> Unit)?,
    ): AsrTranscriptionOutput {
        check(isLoaded) { "ASR model is not loaded" }
        lastParams = params
        val totalSec = (pcm16kMono.size / 16_000.0).coerceAtLeast(0.1)
        val lang = if (params.language == "auto") "en" else params.language
        val segments = mutableListOf<AsrSegment>()
        val count = segmentCount.coerceAtLeast(1)

        for (i in 0 until count) {
            if (abortSignal.isAborted) throw CancellationException("Mock ASR cancelled")
            if (segmentDelayMs > 0) {
                val steps = (segmentDelayMs / 10L).coerceAtLeast(1L)
                for (s in 0 until steps) {
                    if (abortSignal.isAborted) throw CancellationException("Mock ASR cancelled")
                    delay(10L)
                }
            }
            if (abortSignal.isAborted) throw CancellationException("Mock ASR cancelled")
            val t0 = totalSec * i / count
            val t1 = totalSec * (i + 1) / count
            val text = when (lang) {
                "hi" -> if (i == 0) "नमस्ते दुनिया" else "यह ऑफ़लाइन परीक्षण है"
                else -> if (i == 0) "hello offline world" else "segment ${i + 1}"
            }
            val seg = AsrSegment(id = i, startSec = t0, endSec = t1, text = text, noSpeechProb = 0.02f)
            segments += seg
            onSegment?.invoke(seg)
        }

        return AsrTranscriptionOutput(
            text = segments.joinToString(" ") { it.text },
            language = lang,
            segments = segments,
        )
    }
}

