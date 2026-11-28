package com.uoai.asr.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Unified audio decoding entry point converting uploaded files (WAV, raw PCM, MP3, M4A/AAC, OGG, FLAC, WebM, AMR)
 * into 16 kHz mono float32 samples required by Whisper.
 */
interface AudioDecoder {
    fun decode(
        file: File,
        contentType: String? = null,
        fileName: String? = null,
        maxDurationSec: Int = 600,
        pcmSampleRate: Int = DecodedAudio.TARGET_SAMPLE_RATE,
        pcmChannels: Int = 1,
    ): DecodedAudio
}

class DefaultAudioDecoder(
    private val compressedDecoder: (File, Int) -> DecodedAudio = MediaCodecAudioDecoder::decodeCompressed,
) : AudioDecoder {
    override fun decode(
        file: File,
        contentType: String?,
        fileName: String?,
        maxDurationSec: Int,
        pcmSampleRate: Int,
        pcmChannels: Int,
    ): DecodedAudio {
        if (!file.isFile || file.length() == 0L) {
            throw CorruptAudioException("Uploaded audio file is empty")
        }

        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()

        // Explicit raw PCM fast path
        if (mime == "audio/pcm" || mime == "audio/l16" || ext == "pcm" || ext == "raw") {
            return WavPcmDecoder.decodeRawPcm16Le(file, pcmSampleRate, pcmChannels, maxDurationSec)
        }

        val header = readMagicHeader(file)
        if (WavPcmDecoder.looksLikeWav(header)) {
            return WavPcmDecoder.decodeWav(file, maxDurationSec)
        }

        if (!looksLikeSupportedCompressed(header, mime, ext)) {
            throw UnsupportedAudioFormatException(
                "Unsupported audio format (contentType='${mime.ifEmpty { "unknown" }}', ext='${ext.ifEmpty { "none" }}'). Supported: wav, pcm, mp3, m4a, aac, ogg, flac, webm, amr.",
            )
        }

        return compressedDecoder(file, maxDurationSec)
    }

    private fun readMagicHeader(file: File): ByteArray {
        val len = minOf(file.length(), 16L).toInt()
        val buf = ByteArray(len)
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        return buf
    }

    private fun looksLikeSupportedCompressed(h: ByteArray, mime: String, ext: String): Boolean {
        if (h.size >= 4) {
            val b0 = h[0].toInt() and 0xFF
            val b1 = h[1].toInt() and 0xFF
            val b2 = h[2].toInt() and 0xFF
            val b3 = h[3].toInt() and 0xFF
            // FLAC: "fLaC"
            if (b0 == 'f'.code && b1 == 'L'.code && b2 == 'a'.code && b3 == 'C'.code) return true
            // OGG: "OggS"
            if (b0 == 'O'.code && b1 == 'g'.code && b2 == 'g'.code && b3 == 'S'.code) return true
            // MP3 ID3 or MPEG sync frame (0xFF 0xE0..0xFF)
            if (b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code) return true
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return true
            // EBML / WebM / Matroska
            if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) return true
            // AMR
            if (b0 == '#'.code && b1 == '!'.code && b2 == 'A'.code && b3 == 'M'.code) return true
        }
        // MP4 / M4A / 3GP ("ftyp" at offset 4)
        if (h.size >= 8 && h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() &&
            h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte()
        ) return true

        val knownExts = setOf("mp3", "m4a", "mp4", "aac", "ogg", "opus", "flac", "webm", "amr", "3gp")
        if (ext in knownExts) return true
        if (mime.startsWith("audio/") || mime == "video/webm" || mime == "video/mp4") return true
        return false
    }
}

