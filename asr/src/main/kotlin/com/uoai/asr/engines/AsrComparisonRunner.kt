package com.uoai.asr.engines

import java.io.File

/** Exact edit-distance metrics computed against a manually corrected ground-truth reference. */
data class TranscriptErrorMetrics(
    val werPercent: Double,
    val cerPercent: Double,
    val wordEdits: Int,
    val refWordCount: Int,
    val charEdits: Int,
    val refCharCount: Int,
)

/**
 * Pure-Kotlin Unicode-safe Word Error Rate (WER) and Character Error Rate (CER) calculator
 * for evaluating multiple ASR engines against manually corrected ground truth.
 */
object TextErrorMetrics {
    private val PUNCT_REGEX = Regex("""[^\p{L}\p{N}\u0900-\u097F\s]""")
    private val WS_REGEX = Regex("""\s+""")

    fun normalize(text: String): String =
        text.lowercase()
            .replace('।', ' ')
            .replace(PUNCT_REGEX, " ")
            .replace(WS_REGEX, " ")
            .trim()

    fun compute(reference: String, hypothesis: String): TranscriptErrorMetrics {
        val refNorm = normalize(reference)
        val hypNorm = normalize(hypothesis)

        val refWords = if (refNorm.isEmpty()) emptyList() else refNorm.split(' ')
        val hypWords = if (hypNorm.isEmpty()) emptyList() else hypNorm.split(' ')
        val wordEdits = levenshtein(refWords, hypWords)
        val wer = if (refWords.isEmpty()) {
            if (hypWords.isEmpty()) 0.0 else 100.0
        } else {
            (wordEdits.toDouble() / refWords.size.toDouble()) * 100.0
        }

        val refChars = refNorm.toList()
        val hypChars = hypNorm.toList()
        val charEdits = levenshtein(refChars, hypChars)
        val cer = if (refChars.isEmpty()) {
            if (hypChars.isEmpty()) 0.0 else 100.0
        } else {
            (charEdits.toDouble() / refChars.size.toDouble()) * 100.0
        }

        return TranscriptErrorMetrics(
            werPercent = wer,
            cerPercent = cer,
            wordEdits = wordEdits,
            refWordCount = refWords.size,
            charEdits = charEdits,
            refCharCount = refChars.size,
        )
    }

    private fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var prev = IntArray(b.size + 1) { it }
        var curr = IntArray(b.size + 1)
        for (i in 1..a.size) {
            curr[0] = i
            val ai = a[i - 1]
            for (j in 1..b.size) {
                val cost = if (ai == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,       // deletion
                    curr[j - 1] + 1,   // insertion
                    prev[j - 1] + cost // substitution
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.size]
    }
}

/** Per-engine benchmark entry produced by [AsrComparisonRunner]. */
data class EngineComparisonEntry(
    val kind: AsrEngineKind,
    val displayName: String,
    val available: Boolean,
    val transcript: HinglishTranscript? = null,
    val detectedLanguage: String? = null,
    val loadTimeMs: Long? = null,
    val inferenceDurationMs: Long? = null,
    val audioDurationSec: Double = 0.0,
    val peakRssMb: Long? = null,
    val rssDeltaMb: Long? = null,
    val error: String? = null,
) {
    val rtf: Double?
        get() = if (inferenceDurationMs != null && audioDurationSec > 0.01) {
            (inferenceDurationMs / 1000.0) / audioDurationSec
        } else null
}

/**
 * Runs identical 16 kHz mono audio sequentially across all selected engines (Whisper, AksharSTT v4,
 * AksharSTT Lite v4, Shruti) without opening duplicate microphone sessions or holding multiple
 * Cactus models simultaneously in RAM.
 */
class AsrComparisonRunner(
    private val registry: SpeechEngineRegistry,
    private val memoryReaderMb: () -> Long? = ::readProcessRssMb,
) {
    suspend fun compare(
        pcm16kMono: FloatArray,
        engines: List<AsrEngineKind>,
        language: String? = null,
        onEngineFinished: (EngineComparisonEntry) -> Unit = {},
    ): List<EngineComparisonEntry> {
        val durSec = pcm16kMono.size.toDouble() / 16_000.0
        val results = ArrayList<EngineComparisonEntry>(engines.size)

        for (kind in engines) {
            val provider = registry.get(kind)
            if (!provider.isModelInstalled()) {
                val entry = EngineComparisonEntry(
                    kind = kind,
                    displayName = provider.displayName,
                    available = false,
                    audioDurationSec = durSec,
                    error = "Model not downloaded. Install it in Models -> Speech (ASR).",
                )
                results += entry
                onEngineFinished(entry)
                continue
            }

            val rssBefore = memoryReaderMb()
            val entry = try {
                val res = provider.transcribeFloat(pcm16kMono, language)
                val rssAfter = memoryReaderMb()
                val peak = listOfNotNull(rssBefore, rssAfter).maxOrNull()
                val delta = if (rssBefore != null && rssAfter != null) (rssAfter - rssBefore).coerceAtLeast(0L) else null
                EngineComparisonEntry(
                    kind = kind,
                    displayName = res.engineDisplayName,
                    available = true,
                    transcript = res.transcript,
                    detectedLanguage = res.language,
                    loadTimeMs = res.loadTimeMs,
                    inferenceDurationMs = res.inferenceDurationMs,
                    audioDurationSec = res.audioDurationSec,
                    peakRssMb = peak,
                    rssDeltaMb = delta,
                )
            } catch (e: Throwable) {
                EngineComparisonEntry(
                    kind = kind,
                    displayName = provider.displayName,
                    available = true,
                    audioDurationSec = durSec,
                    error = e.message ?: e::class.simpleName ?: "Inference failed",
                )
            }
            results += entry
            onEngineFinished(entry)
        }
        return results
    }

    companion object {
        /** Reads live process Resident Set Size (`VmRSS`) in MB from Linux/Android `/proc/self/status`. */
        fun readProcessRssMb(): Long? = runCatching {
            val status = File("/proc/self/status")
            if (status.isFile) {
                val line = status.useLines { seq -> seq.firstOrNull { it.startsWith("VmRSS:") } }
                val kb = line?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
                if (kb != null) return@runCatching kb / 1024L
            }
            val rt = Runtime.getRuntime()
            (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
        }.getOrNull()
    }
}
