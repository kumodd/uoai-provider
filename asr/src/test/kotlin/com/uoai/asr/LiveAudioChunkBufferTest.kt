package com.uoai.asr

import com.uoai.asr.audio.LiveAudioChunkBuffer
import com.uoai.asr.audio.WavPcmDecoder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

class LiveAudioChunkBufferTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun evictsTranscribedChunksFromRamImmediately() {
        val buf = LiveAudioChunkBuffer()
        val c1 = FloatArray(1600) { 0.1f }
        val c2 = FloatArray(1600) { 0.2f }
        val c3 = FloatArray(1600) { 0.3f }

        buf.append(c1)
        buf.append(c2)
        buf.append(c3)
        assertEquals(4800L, buf.totalSamples)
        assertEquals(3, buf.chunkCount)
        assertEquals(4800 * 4L, buf.retainedBytes)

        // Transcribe and evict first 2 chunks (3200 samples)
        val firstSlice = buf.copySlice(0L, 3200L)
        assertEquals(3200, firstSlice.size)
        assertEquals(0.1f, firstSlice[0], 1e-6f)
        assertEquals(0.2f, firstSlice[1600], 1e-6f)

        val evicted = buf.evictUpTo(3200L)
        assertEquals(3200, evicted)
        assertEquals(1, buf.chunkCount)
        assertEquals(3200L, buf.baseOffset)
        assertEquals(1600, buf.retainedSamples)
        assertEquals(1600 * 4L, buf.retainedBytes)
        assertEquals(3200 * 4L, buf.evictedBytesTotal)

        // Remaining slice still reads cleanly at its absolute offset
        val tail = buf.copySlice(3200L, 4800L)
        assertEquals(1600, tail.size)
        assertEquals(0.3f, tail[0], 1e-6f)
        assertEquals(0.3f, tail[1599], 1e-6f)
    }

    @Test
    fun partialHeadChunkTrimEvictsExactPrefix() {
        val buf = LiveAudioChunkBuffer()
        buf.append(FloatArray(1000) { i -> i / 1000f })
        buf.append(FloatArray(1000) { i -> (1000 + i) / 1000f })

        val evicted = buf.evictUpTo(400L)
        assertEquals(400, evicted)
        assertEquals(400L, buf.baseOffset)
        assertEquals(1600, buf.retainedSamples)
        assertEquals(2, buf.chunkCount)

        val slice = buf.copySlice(400L, 1200L)
        assertEquals(800, slice.size)
        assertEquals(0.4f, slice[0], 1e-6f)
        assertEquals(1.199f, slice[799], 1e-6f)
    }

    @Test
    fun longLiveStreamMaintainsBoundedRamAndDrainsToZero() {
        val buf = LiveAudioChunkBuffer()
        var committed = 0L
        var peakRetainedSamples = 0
        // Simulate 120 seconds of 100 ms chunks (1200 chunks = 1,920,000 samples)
        for (i in 1..1200) {
            val total = buf.append(FloatArray(1600) { 0.05f })
            if (buf.retainedSamples > peakRetainedSamples) {
                peakRetainedSamples = buf.retainedSamples
            }
            // Commit & evict every 5 seconds (50 chunks = 80,000 samples)
            if (i % 50 == 0) {
                val slice = buf.copySlice(committed, total)
                assertEquals(80_000, slice.size)
                buf.evictUpTo(total)
                committed = total
                assertEquals(0, buf.chunkCount)
                assertEquals(0, buf.retainedSamples)
            }
        }
        assertTrue("Peak RAM should stay <= 5s window", peakRetainedSamples <= 80_000)
        assertEquals(1_920_000L, buf.totalSamples)
        assertEquals(1_920_000L * 4L, buf.evictedBytesTotal)

        // Append tail and drainFrom
        buf.append(FloatArray(3200) { 0.25f })
        val drained = buf.drainFrom(committed)
        assertEquals(3200, drained.size)
        assertEquals(0, buf.chunkCount)
        assertEquals(0L, buf.retainedBytes)
    }

    @Test
    fun wavPcm16EncodeDecodeRoundTripPreservesAudio() {
        val input = FloatArray(1600) { i -> ((i % 200) - 100) / 100f }
        val wav = WavPcmDecoder.encodeWavPcm16(input, 16_000)
        assertEquals(44 + input.size * 2, wav.size)
        assertTrue(WavPcmDecoder.looksLikeWav(wav))

        val file = File(tmp.root, "roundtrip.wav").apply { writeBytes(wav) }
        val decoded = WavPcmDecoder.decodeWav(file)
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(input.size, decoded.samples.size)
        for (i in input.indices) {
            assertTrue("Sample $i drifted too far", abs(input[i] - decoded.samples[i]) < 0.001f)
        }
    }
}
