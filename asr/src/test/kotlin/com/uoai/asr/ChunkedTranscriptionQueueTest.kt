package com.uoai.asr

import com.uoai.asr.audio.AudioChunk
import com.uoai.asr.audio.ChunkedAudioConfig
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.LiveAudioChunker
import com.uoai.asr.audio.OrderedChunkProcessor
import com.uoai.asr.audio.TranscriptContinuity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import kotlin.math.PI
import kotlin.math.sin

class ChunkedTranscriptionQueueTest {

    private fun toneFrame(samples: Int = 1600, amplitude: Float = 0.25f, freqHz: Double = 440.0): FloatArray =
        FloatArray(samples) { i -> (amplitude * sin(2.0 * PI * freqHz * i / 16_000.0)).toFloat() }

    private fun silenceFrame(samples: Int = 1600): FloatArray = FloatArray(samples) { 0.0005f }

    @Test
    fun emitsCleanChunksOnSpeechPausesWithImmediateRamEviction() {
        val chunker = LiveAudioChunker(
            ChunkedAudioConfig(
                minChunkMs = 1_000L,
                maxChunkMs = 5_000L,
                pauseCommitMs = 500L,
                preRollMs = 200L,
                silenceTrimStepMs = 200L,
            ),
        )

        // 1. 2 seconds of idle silence -> evicted continuously, zero chunks emitted
        repeat(20) {
            val res = chunker.appendFrame(silenceFrame())
            assertNull(res.emittedChunk)
        }
        assertTrue("Idle silence should stay <= 400 ms in RAM", chunker.retainedBytes <= 6_400L * 4L)
        assertTrue("Idle silence should have been evicted", chunker.evictedBytesTotal > 0L)

        // 2. Speak for 1.5s then pause for 0.5s (pauseCommitMs) -> emits chunk 0 and drops buffer to 0 bytes
        val emitted = mutableListOf<AudioChunk>()
        repeat(15) { chunker.appendFrame(toneFrame()).emittedChunk?.let(emitted::add) }
        repeat(5) { chunker.appendFrame(silenceFrame()).emittedChunk?.let(emitted::add) }

        assertEquals(1, emitted.size)
        assertEquals(0L, emitted[0].sequenceId)
        assertEquals(0L, chunker.retainedBytes)

        // 3. Second utterance 1.2s + 0.5s pause -> emits chunk 1 in strict order and frees RAM immediately
        repeat(12) { chunker.appendFrame(toneFrame()).emittedChunk?.let(emitted::add) }
        repeat(5) { chunker.appendFrame(silenceFrame()).emittedChunk?.let(emitted::add) }

        assertEquals(2, emitted.size)
        assertEquals(1L, emitted[1].sequenceId)
        assertTrue(emitted[1].startSample >= emitted[0].endSample)
        assertEquals(0L, chunker.retainedBytes)
    }

    @Test
    fun continuousSpeechSplitsAtLowEnergyDipWithExactSampleContinuity() {
        val chunker = LiveAudioChunker(
            ChunkedAudioConfig(
                minChunkMs = 1_000L,
                maxChunkMs = 4_000L,
                pauseCommitMs = 600L,
                cutSearchTailMs = 1_000L,
            ),
        )

        val emitted = mutableListOf<AudioChunk>()
        // 3.4s speech + 0.2s quiet dip + 1.4s speech (total 5.0s continuous)
        repeat(34) { chunker.appendFrame(toneFrame()).emittedChunk?.let(emitted::add) }
        repeat(2) { chunker.appendFrame(silenceFrame()).emittedChunk?.let(emitted::add) }
        repeat(14) { chunker.appendFrame(toneFrame()).emittedChunk?.let(emitted::add) }
        chunker.flushFinalChunk()?.let(emitted::add)

        assertEquals(2, emitted.size)
        assertEquals(0L, emitted[0].sequenceId)
        assertEquals(1L, emitted[1].sequenceId)
        // Exact lossless boundary continuity: chunk 1 starts at exact sample where chunk 0 ended!
        assertEquals(emitted[0].endSample, emitted[1].startSample)
        assertEquals(0L, chunker.retainedBytes)
    }

    @Test
    fun orderedChunkProcessorProcessesStrictlyInFifoOrderWithoutLossOrDuplicates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val processedOrder = Collections.synchronizedList(mutableListOf<Long>())
            val processor = OrderedChunkProcessor(scope = scope) { chunk ->
                // Simulate varying decode latencies (earlier chunks slower than later chunks)
                delay(if (chunk.sequenceId % 2L == 0L) 35L else 10L)
                processedOrder.add(chunk.sequenceId)
            }

            val chunks = (0L until 8L).map { seq ->
                AudioChunk(
                    sequenceId = seq,
                    startSample = seq * 16_000L,
                    endSample = (seq + 1L) * 16_000L,
                    samples = toneFrame(16_000),
                )
            }
            chunks.forEach { assertTrue(processor.enqueue(it)) }
            // Duplicate sequence id must be safely ignored
            processor.enqueue(chunks[3])

            processor.finishAndDrain()

            assertEquals((0L until 8L).toList(), processedOrder.toList())
            val snap = processor.snapshot()
            assertEquals(0, snap.queuedCount)
            assertEquals(8, snap.completedCount)
            assertEquals(0L, snap.queuedBytes)
            assertTrue(snap.freedBytes >= 8L * 16_000L * 4L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelAndPurgeDropsQueuedChunksFromRamImmediately() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val processor = OrderedChunkProcessor(scope = scope) { _ ->
                delay(5_000L)
            }
            repeat(5) { idx ->
                processor.enqueue(
                    AudioChunk(
                        sequenceId = idx.toLong(),
                        startSample = idx * 16_000L,
                        endSample = (idx + 1) * 16_000L,
                        samples = toneFrame(16_000),
                    ),
                )
            }
            delay(40L)
            processor.cancelAndPurge()
            val snap = processor.snapshot()
            assertEquals(0, snap.queuedCount)
            assertEquals(0L, snap.queuedBytes)
            assertTrue(snap.freedBytes > 0L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun splitDecodedAudioProducesOrderedLosslessChunks() {
        // 15 seconds of voiced audio -> split into <= 6s chunks
        val samples = FloatArray(15 * 16_000) { i ->
            (0.25 * sin(2.0 * PI * 440.0 * i / 16_000.0)).toFloat()
        }
        val chunks = LiveAudioChunker.splitDecodedAudio(
            decoded = DecodedAudio(samples, 16_000),
            maxChunkSec = 6,
            minChunkSec = 2,
        )
        assertTrue(chunks.size >= 3)
        for (idx in chunks.indices) {
            assertEquals(idx.toLong(), chunks[idx].sequenceId)
            if (idx > 0) {
                assertEquals(chunks[idx - 1].endSample, chunks[idx].startSample)
            }
        }
        assertEquals(samples.size.toLong(), chunks.last().endSample)
    }

    @Test
    fun continuityHelperTrimsOnlyExactBoundaryEchoWithoutModifyingPriorText() {
        val prev = "Today we are testing on-device speech recognition."
        assertEquals(
            "It runs completely offline.",
            TranscriptContinuity.prepareForAppend(prev, "speech recognition. It runs completely offline."),
        )
        assertEquals(
            "And it keeps existing text untouched.",
            TranscriptContinuity.prepareForAppend(prev, "And it keeps existing text untouched."),
        )
    }
}
