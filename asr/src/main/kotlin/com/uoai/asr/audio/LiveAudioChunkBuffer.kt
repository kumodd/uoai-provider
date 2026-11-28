package com.uoai.asr.audio

/**
 * Thread-safe, O(1)-evicting audio chunk queue for real-time / live microphone transcription.
 *
 * Unlike a growing `ArrayList`, calling [evictUpTo] immediately drops references to every
 * already-transcribed (or silent) [FloatArray] chunk so the garbage collector reclaims its RAM
 * while recording continues indefinitely.
 */
class LiveAudioChunkBuffer {
    private val lock = Any()
    private val chunks = ArrayDeque<FloatArray>()

    private var _baseSampleOffset: Long = 0L
    private var _totalCapturedSamples: Long = 0L
    private var _bufferedSamples: Int = 0
    private var _evictedSamplesTotal: Long = 0L

    /** Absolute sample index of the oldest sample still retained in RAM. */
    val baseOffset: Long get() = synchronized(lock) { _baseSampleOffset }

    /** Total monotonic sample count captured since [clear]. */
    val totalSamples: Long get() = synchronized(lock) { _totalCapturedSamples }

    /** Number of float32 samples currently retained in RAM. */
    val retainedSamples: Int get() = synchronized(lock) { _bufferedSamples }

    /** Approximate PCM float32 heap memory currently retained in bytes. */
    val retainedBytes: Long get() = synchronized(lock) { _bufferedSamples.toLong() * Float.SIZE_BYTES }

    /** Cumulative float32 bytes freed from RAM by [evictUpTo] / [drainAll] during this session. */
    val evictedBytesTotal: Long get() = synchronized(lock) { _evictedSamplesTotal * Float.SIZE_BYTES }

    /** Number of live [FloatArray] chunks currently retained in RAM. */
    val chunkCount: Int get() = synchronized(lock) { chunks.size }

    /** Appends a newly captured audio chunk and returns the new total monotonic sample count. */
    fun append(chunk: FloatArray): Long = synchronized(lock) {
        if (chunk.isNotEmpty()) {
            chunks.addLast(chunk)
            _totalCapturedSamples += chunk.size
            _bufferedSamples += chunk.size
        }
        _totalCapturedSamples
    }

    /** Copies samples in `[fromSample, toSample)` clamped to the currently retained window. */
    fun copySlice(fromSample: Long, toSample: Long): FloatArray = synchronized(lock) {
        val safeFrom = fromSample.coerceIn(_baseSampleOffset, _totalCapturedSamples)
        val safeTo = toSample.coerceIn(safeFrom, _totalCapturedSamples)
        val len = (safeTo - safeFrom).toInt()
        if (len <= 0) return FloatArray(0)

        val out = FloatArray(len)
        var cursor = _baseSampleOffset
        var outPos = 0
        for (chunk in chunks) {
            val chunkStart = cursor
            val chunkEnd = cursor + chunk.size
            if (chunkEnd > safeFrom && chunkStart < safeTo) {
                val srcStart = (safeFrom - chunkStart).coerceAtLeast(0L).toInt()
                val srcEnd = (safeTo - chunkStart).coerceAtMost(chunk.size.toLong()).toInt()
                val count = srcEnd - srcStart
                chunk.copyInto(out, outPos, srcStart, srcEnd)
                outPos += count
            }
            cursor = chunkEnd
            if (cursor >= safeTo) break
        }
        out
    }

    /**
     * Immediately removes from RAM all audio chunks strictly before [targetSample].
     * If [targetSample] falls inside the head chunk, that chunk is trimmed to only the unconsumed tail.
     *
     * @return number of float32 samples removed from RAM.
     */
    fun evictUpTo(targetSample: Long): Int = synchronized(lock) {
        val cut = targetSample.coerceIn(_baseSampleOffset, _totalCapturedSamples)
        if (cut <= _baseSampleOffset) return 0

        var evicted = 0
        while (chunks.isNotEmpty()) {
            val head = chunks.first()
            val headEnd = _baseSampleOffset + head.size
            if (headEnd <= cut) {
                chunks.removeFirst()
                _baseSampleOffset = headEnd
                _bufferedSamples -= head.size
                evicted += head.size
            } else {
                val trim = (cut - _baseSampleOffset).toInt()
                if (trim > 0) {
                    chunks.removeFirst()
                    chunks.addFirst(head.copyOfRange(trim, head.size))
                    _baseSampleOffset = cut
                    _bufferedSamples -= trim
                    evicted += trim
                }
                break
            }
        }
        if (chunks.isEmpty()) {
            _bufferedSamples = 0
            _baseSampleOffset = cut
        }
        _evictedSamplesTotal += evicted
        evicted
    }

    /**
     * Copies all remaining samples from `[fromSample, totalSamples)` and immediately removes
     * all retained chunks from RAM so zero audio buffers remain queued.
     */
    fun drainFrom(fromSample: Long): FloatArray = synchronized(lock) {
        val safeFrom = fromSample.coerceIn(_baseSampleOffset, _totalCapturedSamples)
        val out = copySliceUnlocked(safeFrom, _totalCapturedSamples)
        val freed = _bufferedSamples
        chunks.clear()
        _baseSampleOffset = _totalCapturedSamples
        _bufferedSamples = 0
        _evictedSamplesTotal += freed
        out
    }

    /** Resets all state and drops all retained chunks from RAM. */
    fun clear() = synchronized(lock) {
        chunks.clear()
        _baseSampleOffset = 0L
        _totalCapturedSamples = 0L
        _bufferedSamples = 0
        _evictedSamplesTotal = 0L
    }

    private fun copySliceUnlocked(safeFrom: Long, safeTo: Long): FloatArray {
        val len = (safeTo - safeFrom).toInt()
        if (len <= 0) return FloatArray(0)
        val out = FloatArray(len)
        var cursor = _baseSampleOffset
        var outPos = 0
        for (chunk in chunks) {
            val chunkStart = cursor
            val chunkEnd = cursor + chunk.size
            if (chunkEnd > safeFrom && chunkStart < safeTo) {
                val srcStart = (safeFrom - chunkStart).coerceAtLeast(0L).toInt()
                val srcEnd = (safeTo - chunkStart).coerceAtMost(chunk.size.toLong()).toInt()
                val count = srcEnd - srcStart
                chunk.copyInto(out, outPos, srcStart, srcEnd)
                outPos += count
            }
            cursor = chunkEnd
            if (cursor >= safeTo) break
        }
        return out
    }
}
