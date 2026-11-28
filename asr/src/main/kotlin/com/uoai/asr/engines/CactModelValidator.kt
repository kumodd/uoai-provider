package com.uoai.asr.engines

import com.uoai.core.download.ResumableFileDownloader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates Cactus 4.x `.cact` speech model binaries (used by AksharSTT v4, AksharSTT Lite v4,
 * and Shruti) before installation and before native `cactus_load`.
 *
 * Official `.cact` header format verified from `cactus/agent/whistle.py`:
 * - Offset 0..3: little-endian uint32 magic tag `0x05E12A84`
 * - Offset 4..7: little-endian uint32 record count (> 0)
 * - Offset 8..11: little-endian uint32 codebook size
 * - Header precedes tensor records starting at `196 + codebook * 4`.
 */
object CactModelValidator {
    const val CACT_MAGIC_LE = 0x05E12A84
    private const val MIN_HEADER_BYTES = 196

    data class ValidationResult(val valid: Boolean, val reason: String? = null)

    fun validateHeader(file: File, minSizeBytes: Long = 1_000_000L): ValidationResult {
        if (!file.isFile) return ValidationResult(false, "Model file does not exist")
        if (file.length() < minSizeBytes) {
            return ValidationResult(false, "Model file is truncated (${file.length()} bytes)")
        }
        if (file.length() < MIN_HEADER_BYTES) {
            return ValidationResult(false, "Cactus .cact header is incomplete")
        }

        return runCatching {
            val header = ByteArray(12)
            RandomAccessFile(file, "r").use { it.readFully(header) }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val magic = bb.int
            val recordCount = bb.int
            val codebook = bb.int
            if (magic != CACT_MAGIC_LE) {
                return ValidationResult(
                    false,
                    "Invalid Cactus .cact magic header (0x${magic.toUInt().toString(16)})",
                )
            }
            if (recordCount !in 1..100_000 || codebook < 0 || codebook > 1_000_000) {
                return ValidationResult(
                    false,
                    "Invalid Cactus .cact header metadata (records=$recordCount, codebook=$codebook)",
                )
            }
            val minHeaderEnd = 196L + codebook.toLong() * 4L + recordCount.toLong() * 44L
            if (file.length() < minHeaderEnd) {
                return ValidationResult(false, "Cactus .cact table truncated (len=${file.length()} < $minHeaderEnd)")
            }
            ValidationResult(true)
        }.getOrElse { e ->
            ValidationResult(false, "Could not inspect .cact header: ${e.message}")
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
