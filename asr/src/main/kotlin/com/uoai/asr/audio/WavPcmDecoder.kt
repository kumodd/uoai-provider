package com.uoai.asr.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class UnsupportedAudioFormatException(message: String) : Exception(message)
class CorruptAudioException(message: String) : Exception(message)
class AudioTooLongException(message: String) : Exception(message)

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int = TARGET_SAMPLE_RATE,
    val durationSec: Double = samples.size.toDouble() / sampleRate,
) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
    }
}

/**
 * Pure-Kotlin fast-path decoder for uncompressed WAV (RIFF/WAVE PCM 8/16/24/32-bit & IEEE Float32)
 * and raw PCM16LE streams, with mono downmix and linear resampling to 16 kHz mono float32.
 */
object WavPcmDecoder {
    const val TARGET_RATE = DecodedAudio.TARGET_SAMPLE_RATE

    fun looksLikeWav(header: ByteArray): Boolean =
        header.size >= 12 &&
            header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
            header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()

    fun decodeWav(file: File, maxDurationSec: Int = 600): DecodedAudio {
        if (!file.isFile || file.length() < 44) throw CorruptAudioException("WAV file is too small")

        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (!looksLikeWav(riff)) throw UnsupportedAudioFormatException("Not a valid RIFF/WAVE file")

            var audioFormat = -1
            var numChannels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = 0L

            val chunkHeader = ByteArray(8)
            val fileLen = raf.length()
            while (raf.filePointer + 8 <= fileLen) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val payloadPos = raf.filePointer

                when (id) {
                    "fmt " -> {
                        if (size < 16 || payloadPos + 16 > fileLen) throw CorruptAudioException("Invalid WAV fmt chunk")
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        audioFormat = bb.short.toInt() and 0xFFFF
                        numChannels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = payloadPos
                        dataSize = min(size, fileLen - payloadPos)
                        break
                    }
                }
                val nextPos = payloadPos + size + (size and 1L)
                if (nextPos > fileLen || nextPos <= payloadPos) break
                raf.seek(nextPos)
            }

            if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xFFFE) {
                throw UnsupportedAudioFormatException("Unsupported WAV encoding format ($audioFormat); PCM or IEEE float required")
            }
            if (numChannels !in 1..8 || sampleRate !in 8_000..192_000) {
                throw CorruptAudioException("Unsupported WAV stream parameters (channels=$numChannels, rate=$sampleRate)")
            }
            if (bitsPerSample !in setOf(8, 16, 24, 32)) {
                throw UnsupportedAudioFormatException("Unsupported WAV bit depth ($bitsPerSample-bit)")
            }
            if (dataOffset < 0 || dataSize <= 0) {
                throw CorruptAudioException("WAV file contains no audio data chunk")
            }

            val bytesPerSample = bitsPerSample / 8
            val frameBytes = bytesPerSample * numChannels
            val totalFrames = (dataSize / frameBytes).toInt()
            val durationSec = totalFrames.toDouble() / sampleRate
            if (durationSec > maxDurationSec) {
                throw AudioTooLongException("Audio duration (${"%.1f".format(durationSec)}s) exceeds max allowed ${maxDurationSec}s")
            }

            val mono = FloatArray(totalFrames)
            raf.seek(dataOffset)
            val buf = ByteArray(64 * 1024 - (64 * 1024 % frameBytes))
            var frameIdx = 0
            var remaining = totalFrames * frameBytes.toLong()
            while (remaining > 0 && frameIdx < totalFrames) {
                val toRead = min(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n <= 0) break
                val usableFrames = n / frameBytes
                for (f in 0 until usableFrames) {
                    var sum = 0f
                    val base = f * frameBytes
                    for (c in 0 until numChannels) {
                        val off = base + c * bytesPerSample
                        sum += readSample(buf, off, bitsPerSample, audioFormat == 3)
                    }
                    mono[frameIdx++] = (sum / numChannels).coerceIn(-1f, 1f)
                }
                remaining -= usableFrames * frameBytes.toLong()
            }

            val trimmed = if (frameIdx == mono.size) mono else mono.copyOf(frameIdx)
            val resampled = resampleLinear(trimmed, sampleRate, TARGET_RATE)
            return DecodedAudio(resampled, TARGET_RATE, resampled.size.toDouble() / TARGET_RATE)
        }
    }

    fun decodeRawPcm16Le(
        file: File,
        sampleRate: Int = TARGET_RATE,
        channels: Int = 1,
        maxDurationSec: Int = 600,
    ): DecodedAudio {
        if (sampleRate !in 8_000..96_000 || channels !in 1..2) {
            throw UnsupportedAudioFormatException("Unsupported PCM parameters (rate=$sampleRate, channels=$channels)")
        }
        val frameBytes = 2 * channels
        val totalFrames = (file.length() / frameBytes).toInt()
        if (totalFrames <= 0) throw CorruptAudioException("Empty PCM audio stream")
        val durationSec = totalFrames.toDouble() / sampleRate
        if (durationSec > maxDurationSec) {
            throw AudioTooLongException("Audio duration (${"%.1f".format(durationSec)}s) exceeds max allowed ${maxDurationSec}s")
        }

        val mono = FloatArray(totalFrames)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024 - (64 * 1024 % frameBytes))
            var frameIdx = 0
            while (frameIdx < totalFrames) {
                val n = input.read(buf)
                if (n <= 0) break
                val frames = min(n / frameBytes, totalFrames - frameIdx)
                for (f in 0 until frames) {
                    var sum = 0f
                    val base = f * frameBytes
                    for (c in 0 until channels) {
                        val off = base + c * 2
                        val lo = buf[off].toInt() and 0xFF
                        val hi = buf[off + 1].toInt()
                        val s = (hi shl 8) or lo
                        sum += s / 32768f
                    }
                    mono[frameIdx++] = (sum / channels).coerceIn(-1f, 1f)
                }
            }
        }
        val resampled = resampleLinear(mono, sampleRate, TARGET_RATE)
        return DecodedAudio(resampled, TARGET_RATE, resampled.size.toDouble() / TARGET_RATE)
    }

    fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int = TARGET_RATE): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val lastIdx = input.size - 1
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx.coerceAtMost(lastIdx)]
            val b = input[(idx + 1).coerceAtMost(lastIdx)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /**
     * Encodes 16 kHz mono float32 PCM samples (`[-1f, 1f]`) into a standard RIFF/WAVE PCM16LE
     * byte array suitable for native multimodal LLM audio input (e.g. Gemma 4 LiteRT-LM).
     */
    fun encodeWavPcm16(samples: FloatArray, sampleRate: Int = TARGET_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val totalSize = 44 + dataBytes
        val out = ByteArray(totalSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte(); out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        bb.putInt(4, 36 + dataBytes)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte(); out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()

        // fmt chunk (16 bytes, PCM = 1, mono = 1, 16-bit)
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte(); out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        bb.putInt(16, 16)
        bb.putShort(20, 1)
        bb.putShort(22, 1)
        bb.putInt(24, sampleRate)
        bb.putInt(28, sampleRate * 2)
        bb.putShort(32, 2)
        bb.putShort(34, 16)

        // data chunk
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte(); out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        bb.putInt(40, dataBytes)

        var pos = 44
        for (s in samples) {
            val clamped = (s.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
            out[pos++] = (clamped and 0xFF).toByte()
            out[pos++] = ((clamped shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(buf: ByteArray, off: Int, bits: Int, isFloat: Boolean): Float = when (bits) {
        8 -> ((buf[off].toInt() and 0xFF) - 128) / 128f
        16 -> {
            val lo = buf[off].toInt() and 0xFF
            val hi = buf[off + 1].toInt()
            ((hi shl 8) or lo) / 32768f
        }
        24 -> {
            val b0 = buf[off].toInt() and 0xFF
            val b1 = buf[off + 1].toInt() and 0xFF
            val b2 = buf[off + 2].toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
        }
        32 -> {
            val bits32 = (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
            if (isFloat) java.lang.Float.intBitsToFloat(bits32) else bits32 / 2_147_483_648f
        }
        else -> 0f
    }
}

