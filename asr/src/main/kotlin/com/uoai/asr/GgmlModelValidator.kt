package com.uoai.asr

import com.uoai.core.download.ResumableFileDownloader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates GGML Whisper model binaries before installation or native load.
 *
 * Verifies:
 * 1. Exact/minimum size bounds.
 * 2. GGML magic header (`0x67676d6c`, little-endian `"lmgg"`) and sane Whisper hyperparameters.
 * 3. Optional SHA-256 cryptographic digest match.
 */
object GgmlModelValidator {
    const val GGML_MAGIC_LE = 0x67676d6c // "ggml" stored little-endian as 6c 6d 67 67
    private const val MIN_HEADER_BYTES = 48

    data class ValidationResult(val valid: Boolean, val reason: String? = null)

    fun validateHeader(file: File, minSizeBytes: Long = 1_000_000L): ValidationResult {
        if (!file.isFile) return ValidationResult(false, "Model file does not exist")
        if (file.length() < minSizeBytes) return ValidationResult(false, "Model file is truncated (${file.length()} bytes)")
        if (file.length() < MIN_HEADER_BYTES) return ValidationResult(false, "Model header is incomplete")

        return runCatching {
            val header = ByteArray(MIN_HEADER_BYTES)
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(header)
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            if (magic != GGML_MAGIC_LE) {
                return ValidationResult(false, "Invalid GGML magic header (0x${magic.toUInt().toString(16)})")
            }
            val nVocab = bb.int
            val nAudioCtx = bb.int
            val nAudioState = bb.int
            val nAudioHead = bb.int
            val nAudioLayer = bb.int
            val nTextCtx = bb.int
            val nTextState = bb.int
            val nTextHead = bb.int
            val nTextLayer = bb.int
            val nMels = bb.int
            if (nVocab !in 10_000..100_000 || nAudioCtx <= 0 || nAudioState <= 0 || nAudioHead <= 0 ||
                nAudioLayer !in 1..64 || nTextCtx <= 0 || nTextState <= 0 || nTextHead <= 0 ||
                nTextLayer !in 1..64 || (nMels != 80 && nMels != 128)
            ) {
                return ValidationResult(false, "Invalid Whisper GGML hyperparameter block (vocab=$nVocab, mels=$nMels)")
            }
            ValidationResult(true)
        }.getOrElse { e ->
            ValidationResult(false, "Could not inspect GGML header: ${e.message}")
        }
    }

    fun verifyFull(file: File, expectedSha256: String?, expectedSizeBytes: Long? = null): ValidationResult {
        if (expectedSizeBytes != null && file.length() != expectedSizeBytes) {
            return ValidationResult(false, "File size mismatch (actual=${file.length()}, expected=$expectedSizeBytes)")
        }
        val header = validateHeader(file, minSizeBytes = expectedSizeBytes ?: 1_000_000L)
        if (!header.valid) return header
        if (!expectedSha256.isNullOrBlank()) {
            val actual = ResumableFileDownloader.sha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return ValidationResult(false, "SHA-256 checksum mismatch")
            }
        }
        return ValidationResult(true)
    }
}

