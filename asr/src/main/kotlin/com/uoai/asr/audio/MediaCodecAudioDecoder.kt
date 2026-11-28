package com.uoai.asr.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Android-native compressed audio decoder (`MediaExtractor` + `MediaCodec`, API 26+) supporting
 * MP3, M4A/AAC, FLAC, OGG/Vorbis, WebM/Opus, and AMR without bundling FFmpeg binaries.
 */
object MediaCodecAudioDecoder {
    private const val TIMEOUT_US = 10_000L

    fun decodeCompressed(file: File, maxDurationSec: Int = 600): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                throw UnsupportedAudioFormatException("No audio track found in uploaded file")
            }

            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val durSec = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                if (durSec > maxDurationSec) {
                    throw AudioTooLongException("Audio duration (${"%.1f".format(durSec)}s) exceeds max allowed ${maxDurationSec}s")
                }
            }

            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw UnsupportedAudioFormatException("Missing audio MIME type")

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 16_000
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val maxSamplesAtSrcRate = (maxDurationSec.toLong() + 2L) * sampleRate.coerceAtLeast(16_000)
            val chunks = ArrayList<FloatArray>(32)
            var totalMonoSamples = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            val read = extractor.readSampleData(inBuf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = extractMonoFloats(outBuf, info.size, channelCount.coerceAtLeast(1), pcmEncoding)
                            if (chunk.isNotEmpty()) {
                                totalMonoSamples += chunk.size
                                if (totalMonoSamples > maxSamplesAtSrcRate) {
                                    throw AudioTooLongException("Decoded audio exceeds max allowed ${maxDurationSec}s")
                                }
                                chunks += chunk
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            }

            if (totalMonoSamples == 0L) {
                throw CorruptAudioException("Decoded audio contains zero samples")
            }

            val merged = FloatArray(totalMonoSamples.toInt())
            var pos = 0
            for (c in chunks) {
                c.copyInto(merged, pos)
                pos += c.size
            }
            val resampled = WavPcmDecoder.resampleLinear(merged, sampleRate, DecodedAudio.TARGET_SAMPLE_RATE)
            val durSec = resampled.size.toDouble() / DecodedAudio.TARGET_SAMPLE_RATE
            if (durSec > maxDurationSec) {
                throw AudioTooLongException("Decoded audio duration (${"%.1f".format(durSec)}s) exceeds max allowed ${maxDurationSec}s")
            }
            return DecodedAudio(resampled, DecodedAudio.TARGET_SAMPLE_RATE, durSec)
        } catch (e: AudioTooLongException) {
            throw e
        } catch (e: UnsupportedAudioFormatException) {
            throw e
        } catch (e: CorruptAudioException) {
            throw e
        } catch (e: Throwable) {
            throw UnsupportedAudioFormatException("Failed to decode compressed audio: ${e.message ?: e::class.simpleName}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun extractMonoFloats(
        buf: java.nio.ByteBuffer,
        size: Int,
        channels: Int,
        pcmEncoding: Int,
    ): FloatArray {
        val dup = buf.slice().order(ByteOrder.LITTLE_ENDIAN)
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val totalFloats = size / 4
                val frames = totalFloats / channels
                FloatArray(frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += dup.float
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }
            else -> {
                val totalShorts = size / 2
                val frames = totalShorts / channels
                FloatArray(frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += dup.short / 32768f
                    (sum / channels).coerceIn(-1f, 1f)
                }
            }
        }
    }
}

