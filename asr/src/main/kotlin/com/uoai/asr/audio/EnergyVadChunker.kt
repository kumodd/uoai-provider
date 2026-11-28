package com.uoai.asr.audio

import kotlin.math.sqrt

/**
 * Lightweight energy/RMS Voice Activity Detection & speech-window chunker for
 * trimming long leading/trailing silence and segmenting streaming audio windows.
 */
object EnergyVadChunker {
    data class SpeechWindow(val startSample: Int, val endSample: Int)

    /**
     * Splits a 16 kHz mono float buffer into bounded speech windows (preferring silence boundaries)
     * so long streaming inputs emit timely partial results without overflowing Whisper's 30 s window.
     */
    fun segmentWindows(
        samples: FloatArray,
        sampleRate: Int = DecodedAudio.TARGET_SAMPLE_RATE,
        maxWindowSec: Int = 15,
        minWindowSec: Int = 3,
        silenceRmsThreshold: Float = 0.012f,
    ): List<SpeechWindow> {
        if (samples.isEmpty()) return emptyList()
        val maxSamples = maxWindowSec * sampleRate
        val minSamples = minWindowSec * sampleRate
        if (samples.size <= maxSamples) {
            return listOf(SpeechWindow(0, samples.size))
        }

        val frameLen = (sampleRate / 50).coerceAtLeast(160) // 20 ms frames
        val windows = mutableListOf<SpeechWindow>()
        var start = 0
        while (start < samples.size) {
            val hardEnd = minOf(start + maxSamples, samples.size)
            if (hardEnd == samples.size) {
                windows += SpeechWindow(start, hardEnd)
                break
            }
            // Search backward from hardEnd down to start + minSamples for lowest-energy frame
            var bestCut = hardEnd
            var lowestRms = Float.MAX_VALUE
            var cursor = hardEnd - frameLen
            val earliest = start + minSamples
            while (cursor >= earliest) {
                val rms = frameRms(samples, cursor, minOf(cursor + frameLen, samples.size))
                if (rms < lowestRms) {
                    lowestRms = rms
                    bestCut = cursor + frameLen / 2
                    if (rms <= silenceRmsThreshold) break
                }
                cursor -= frameLen
            }
            windows += SpeechWindow(start, bestCut)
            start = bestCut
        }
        return windows
    }

    fun frameRms(samples: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return 0f
        var sumSq = 0.0
        for (i in from until to) {
            val v = samples[i].toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / (to - from)).toFloat()
    }
}

