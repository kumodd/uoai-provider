package com.uoai.asr.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * An immutable, strictly ordered audio slice produced by [LiveAudioChunker] or file segmentation.
 * Each chunk has a monotonic [sequenceId] (`0, 1, 2, ...`) and exact sample bounds so chunks
 * are never dropped, duplicated, or reordered.
 */
data class AudioChunk(
    val sequenceId: Long,
    val startSample: Long,
    val endSample: Long,
    val sampleRate: Int = DecodedAudio.TARGET_SAMPLE_RATE,
    val samples: FloatArray,
) {
    val durationSec: Double get() = samples.size.toDouble() / sampleRate
    val sizeBytes: Long get() = samples.size.toLong() * Float.SIZE_BYTES

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sequenceId == other.sequenceId &&
            startSample == other.startSample &&
            endSample == other.endSample &&
            sampleRate == other.sampleRate &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sequenceId.hashCode()
        result = 31 * result + startSample.hashCode()
        result = 31 * result + endSample.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/** Configuration for [LiveAudioChunker] speech-boundary chunking and RAM eviction. */
data class ChunkedAudioConfig(
    val sampleRate: Int = DecodedAudio.TARGET_SAMPLE_RATE,
    val minChunkMs: Long = 1_200L,
    val maxChunkMs: Long = 5_000L,
    val pauseCommitMs: Long = 550L,
    val preRollMs: Long = 250L,
    val silenceTrimStepMs: Long = 200L,
    val cutSearchTailMs: Long = 1_000L,
    val speechRmsThreshold: Float = 0.022f,
    val minVoicedPeak: Float = 0.045f,
    val minVoicedFrames50ms: Int = 3,
)

/** Result returned for every appended microphone frame. */
data class ChunkerFrameResult(
    val rms: Float,
    val normalizedLevel: Float,
    val emittedChunk: AudioChunk?,
    val retainedBytes: Long,
    val evictedBytesTotal: Long,
    val totalSamples: Long,
)

/**
 * Splits incoming real-time PCM frames into clean, non-overlapping, speech-boundary [AudioChunk]s
 * while immediately evicting already-emitted and idle-silent samples from RAM via [LiveAudioChunkBuffer].
 *
 * Guarantees:
 * - Strict monotonic `sequenceId` (`0, 1, 2, ...`).
 * - Exact sample-level continuity on continuous speech cuts (`next.startSample == prev.endSample`).
 * - Zero overlap and zero duplication between chunks.
 * - Cuts continuous speech at the lowest-energy 20 ms dip in the trailing window to avoid mid-word splits.
 * - Evicts idle pre-speech silence beyond a short pre-roll so RAM stays O(1) bounded.
 */
class LiveAudioChunker(
    private val config: ChunkedAudioConfig = ChunkedAudioConfig(),
) {
    private val buffer = LiveAudioChunkBuffer()
    private val minChunkSamples = (config.minChunkMs * config.sampleRate) / 1000L
    private val maxChunkSamples = (config.maxChunkMs * config.sampleRate) / 1000L
    private val pauseCommitSamples = (config.pauseCommitMs * config.sampleRate) / 1000L
    private val preRollSamples = (config.preRollMs * config.sampleRate) / 1000L
    private val silenceTrimStepSamples = (config.silenceTrimStepMs * config.sampleRate) / 1000L
    private val cutSearchTailSamples = (config.cutSearchTailMs * config.sampleRate) / 1000L

    private var nextSequenceId: Long = 0L
    private var speechSeen: Boolean = false
    private var lastVoiceSample: Long = -1L

    val retainedBytes: Long get() = buffer.retainedBytes
    val evictedBytesTotal: Long get() = buffer.evictedBytesTotal
    val totalSamples: Long get() = buffer.totalSamples

    /**
     * Appends a single captured PCM frame (e.g. 100 ms = 1600 samples at 16 kHz) and returns
     * an [AudioChunk] whenever a clean speech pause or max-chunk boundary is reached.
     */
    fun appendFrame(frame: FloatArray): ChunkerFrameResult {
        if (frame.isEmpty()) {
            return ChunkerFrameResult(0f, 0f, null, buffer.retainedBytes, buffer.evictedBytesTotal, buffer.totalSamples)
        }

        var sumSq = 0.0
        for (s in frame) sumSq += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSq / frame.size).toFloat()
        val level = (rms * 4.5f).coerceIn(0f, 1f)

        val total = buffer.append(frame)
        if (rms >= config.speechRmsThreshold) {
            speechSeen = true
            lastVoiceSample = total
        }

        var emitted: AudioChunk? = null
        if (!speechSeen) {
            // Evict idle silence beyond pre-roll so waiting for speech consumes ~16 KB RAM.
            if (buffer.retainedSamples > preRollSamples + silenceTrimStepSamples) {
                buffer.evictUpTo(total - preRollSamples)
            }
        } else {
            val windowSamples = buffer.retainedSamples.toLong()
            val silenceSamples = if (lastVoiceSample >= 0L) (total - lastVoiceSample).coerceAtLeast(0L) else 0L

            if (silenceSamples >= pauseCommitSamples && windowSamples >= minChunkSamples) {
                val base = buffer.baseOffset
                val slice = buffer.drainFrom(base)
                speechSeen = false
                lastVoiceSample = -1L
                if (hasVoicedSpeech(slice, config)) {
                    emitted = AudioChunk(
                        sequenceId = nextSequenceId++,
                        startSample = base,
                        endSample = total,
                        sampleRate = config.sampleRate,
                        samples = slice,
                    )
                }
            } else if (windowSamples >= maxChunkSamples) {
                val base = buffer.baseOffset
                val rawWindow = buffer.copySlice(base, total)
                val bestCutRel = findBestCutOffset(rawWindow)
                val cutAbs = base + bestCutRel
                val slice = rawWindow.copyOfRange(0, bestCutRel)
                // Immediately evict the emitted prefix from RAM; keep untouched tail for seamless continuity.
                buffer.evictUpTo(cutAbs)
                speechSeen = lastVoiceSample > cutAbs
                if (!speechSeen) lastVoiceSample = -1L
                if (hasVoicedSpeech(slice, config)) {
                    emitted = AudioChunk(
                        sequenceId = nextSequenceId++,
                        startSample = base,
                        endSample = cutAbs,
                        sampleRate = config.sampleRate,
                        samples = slice,
                    )
                }
            }
        }

        return ChunkerFrameResult(
            rms = rms,
            normalizedLevel = level,
            emittedChunk = emitted,
            retainedBytes = buffer.retainedBytes,
            evictedBytesTotal = buffer.evictedBytesTotal,
            totalSamples = total,
        )
    }

    /**
     * Drains any remaining voiced tail on recording Stop and resets the buffer to 0 retained bytes.
     */
    fun flushFinalChunk(): AudioChunk? {
        val base = buffer.baseOffset
        val total = buffer.totalSamples
        val slice = buffer.drainFrom(base)
        val hadSpeech = speechSeen
        speechSeen = false
        lastVoiceSample = -1L
        val minFinalSamples = config.sampleRate / 4 // 250 ms
        if (!hadSpeech || slice.size < minFinalSamples || !hasVoicedSpeech(slice, config)) {
            return null
        }
        return AudioChunk(
            sequenceId = nextSequenceId++,
            startSample = base,
            endSample = total,
            sampleRate = config.sampleRate,
            samples = slice,
        )
    }

    /** Clears all buffered samples and resets sequence numbering for a new session. */
    fun reset() {
        buffer.clear()
        nextSequenceId = 0L
        speechSeen = false
        lastVoiceSample = -1L
    }

    /**
     * Searches backward through the trailing [ChunkedAudioConfig.cutSearchTailMs] window for the
     * lowest-energy 20 ms frame so continuous speech cuts land on natural micro-pauses between words.
     */
    private fun findBestCutOffset(window: FloatArray): Int {
        val frameLen = (config.sampleRate / 50).coerceAtLeast(160) // 20 ms
        val earliest = maxOf(minChunkSamples.toInt(), window.size - cutSearchTailSamples.toInt())
            .coerceIn(frameLen, window.size)
        if (window.size - earliest < frameLen) return window.size

        var bestCut = window.size
        var lowestRms = Float.MAX_VALUE
        var cursor = window.size - frameLen
        while (cursor >= earliest) {
            val rms = EnergyVadChunker.frameRms(window, cursor, minOf(cursor + frameLen, window.size))
            if (rms < lowestRms) {
                lowestRms = rms
                bestCut = cursor + frameLen / 2
                if (rms <= config.speechRmsThreshold * 0.5f) break
            }
            cursor -= frameLen
        }
        return bestCut.coerceIn(earliest, window.size)
    }

    companion object {
        /** Returns true only if [samples] contains sustained voiced speech above noise floor. */
        fun hasVoicedSpeech(samples: FloatArray, config: ChunkedAudioConfig = ChunkedAudioConfig()): Boolean {
            val frameSize = (config.sampleRate / 20).coerceAtLeast(160) // 50 ms
            if (samples.size < frameSize) return false
            var voicedFrames = 0
            var peakAbs = 0f
            var offset = 0
            while (offset + frameSize <= samples.size) {
                var sumSq = 0.0
                for (i in offset until offset + frameSize) {
                    val v = samples[i]
                    val a = if (v < 0f) -v else v
                    if (a > peakAbs) peakAbs = a
                    sumSq += v.toDouble() * v.toDouble()
                }
                val rms = sqrt(sumSq / frameSize).toFloat()
                if (rms >= config.speechRmsThreshold) voicedFrames++
                offset += frameSize
            }
            return voicedFrames >= config.minVoicedFrames50ms && peakAbs >= config.minVoicedPeak
        }

        /**
         * Splits a decoded audio file cleanly at silence/low-energy boundaries into sequential
         * [AudioChunk]s for the same ordered queue pipeline.
         */
        fun splitDecodedAudio(
            decoded: DecodedAudio,
            maxChunkSec: Int = 10,
            minChunkSec: Int = 2,
            config: ChunkedAudioConfig = ChunkedAudioConfig(sampleRate = decoded.sampleRate),
        ): List<AudioChunk> {
            val windows = EnergyVadChunker.segmentWindows(
                samples = decoded.samples,
                sampleRate = decoded.sampleRate,
                maxWindowSec = maxChunkSec,
                minWindowSec = minChunkSec,
                silenceRmsThreshold = config.speechRmsThreshold * 0.6f,
            )
            var seq = 0L
            val chunks = ArrayList<AudioChunk>(windows.size)
            for (w in windows) {
                val slice = decoded.samples.copyOfRange(w.startSample, w.endSample)
                if (slice.isNotEmpty() && (windows.size == 1 || hasVoicedSpeech(slice, config))) {
                    chunks += AudioChunk(
                        sequenceId = seq++,
                        startSample = w.startSample.toLong(),
                        endSample = w.endSample.toLong(),
                        sampleRate = decoded.sampleRate,
                        samples = slice,
                    )
                }
            }
            return chunks
        }
    }
}

/** Snapshot of real-time FIFO queue progress and RAM eviction accounting. */
data class ChunkQueueMetrics(
    val queuedCount: Int = 0,
    val processingSequenceId: Long? = null,
    val completedCount: Int = 0,
    val queuedBytes: Long = 0L,
    val freedBytes: Long = 0L,
)

/**
 * Strictly sequential, ordered FIFO audio chunk processor.
 *
 * - Processes enqueued [AudioChunk]s one at a time in exact arrival / [AudioChunk.sequenceId] order.
 * - Immediately releases each chunk's PCM [FloatArray] after [processChunk] completes and accounts
 *   for the freed RAM bytes.
 * - Guarantees zero lost, duplicated, or out-of-order chunks when drained via [finishAndDrain].
 * - Releases all queued PCM buffers immediately if aborted via [cancelAndPurge].
 */
class OrderedChunkProcessor(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onMetrics: (ChunkQueueMetrics) -> Unit = {},
    private val onError: (AudioChunk, Throwable) -> Unit = { _, _ -> },
    private val processChunk: suspend (AudioChunk) -> Unit,
) {
    private val channel = Channel<AudioChunk>(Channel.UNLIMITED)
    private val queuedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val queuedBytes = AtomicLong(0L)
    private val freedBytes = AtomicLong(0L)
    @Volatile private var processingSeq: Long? = null
    @Volatile private var lastProcessedSeq: Long = -1L

    private val workerJob: Job = scope.launch(dispatcher) {
        try {
            for (chunk in channel) {
                queuedCount.decrementAndGet()
                queuedBytes.addAndGet(-chunk.sizeBytes)
                // Guard against duplicate or out-of-order delivery
                if (chunk.sequenceId <= lastProcessedSeq) {
                    freedBytes.addAndGet(chunk.sizeBytes)
                    emitMetrics()
                    continue
                }
                processingSeq = chunk.sequenceId
                emitMetrics()
                try {
                    processChunk(chunk)
                    lastProcessedSeq = chunk.sequenceId
                    completedCount.incrementAndGet()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastProcessedSeq = chunk.sequenceId
                    onError(chunk, e)
                } finally {
                    processingSeq = null
                    // Chunk audio samples go out of scope right after this loop iteration.
                    freedBytes.addAndGet(chunk.sizeBytes)
                    emitMetrics()
                }
            }
        } catch (_: CancellationException) {
            purgeRemainingChannel()
        }
    }

    /** Adds an [AudioChunk] to the FIFO queue without blocking the audio capture thread. */
    fun enqueue(chunk: AudioChunk): Boolean {
        val ok = channel.trySend(chunk).isSuccess
        if (ok) {
            queuedCount.incrementAndGet()
            queuedBytes.addAndGet(chunk.sizeBytes)
            emitMetrics()
        }
        return ok
    }

    /** Closes input and suspends until every queued chunk has been processed in strict FIFO order. */
    suspend fun finishAndDrain() {
        channel.close()
        workerJob.join()
    }

    /** Immediately cancels processing and purges any queued PCM arrays from RAM. */
    fun cancelAndPurge() {
        channel.close()
        workerJob.cancel()
        purgeRemainingChannel()
        processingSeq = null
        emitMetrics()
    }

    fun snapshot(): ChunkQueueMetrics = ChunkQueueMetrics(
        queuedCount = queuedCount.get().coerceAtLeast(0),
        processingSequenceId = processingSeq,
        completedCount = completedCount.get(),
        queuedBytes = queuedBytes.get().coerceAtLeast(0L),
        freedBytes = freedBytes.get(),
    )

    private fun purgeRemainingChannel() {
        while (true) {
            val dropped = channel.tryReceive().getOrNull() ?: break
            queuedCount.decrementAndGet()
            queuedBytes.addAndGet(-dropped.sizeBytes)
            freedBytes.addAndGet(dropped.sizeBytes)
        }
    }

    private fun emitMetrics() {
        onMetrics(snapshot())
    }
}

/**
 * Preserves continuity between consecutive independently transcribed chunks without ever
 * mutating previously committed text.
 */
object TranscriptContinuity {
    private val NON_WORD = Regex("""[^\p{L}\p{N}]""")

    /**
     * Cleans only the newly transcribed [newChunkText] so it appends smoothly after
     * [previousChunkText] (trims rare 2-to-4-word boundary echoes without touching prior text).
     */
    fun prepareForAppend(previousChunkText: String?, newChunkText: String): String {
        val next = newChunkText.trim()
        if (next.isEmpty() || previousChunkText.isNullOrBlank()) return next

        val prevTokens = previousChunkText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nextTokens = next.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (prevTokens.size < 2 || nextTokens.size < 2) return next

        val prevNorm = prevTokens.map { it.lowercase().replace(NON_WORD, "") }
        val nextNorm = nextTokens.map { it.lowercase().replace(NON_WORD, "") }

        // Check for a 2..4 word overlap between the exact tail of previousChunk and head of newChunk
        val maxOverlap = minOf(4, prevNorm.size, nextNorm.size)
        for (overlap in maxOverlap downTo 2) {
            var match = true
            for (k in 0 until overlap) {
                val a = prevNorm[prevNorm.size - overlap + k]
                val b = nextNorm[k]
                if (a.isEmpty() || a != b) {
                    match = false
                    break
                }
            }
            if (match) {
                return nextTokens.drop(overlap).joinToString(" ").trim()
            }
        }
        return next
    }
}
