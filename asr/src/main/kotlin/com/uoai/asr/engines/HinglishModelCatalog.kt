package com.uoai.asr.engines

import kotlinx.serialization.Serializable

/** User-selectable offline speech recognition engines in UOAI Provider. */
@Serializable
enum class AsrEngineKind(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val subtitle: String,
) {
    WHISPER(
        id = "whisper",
        displayName = "Whisper (GGML)",
        shortLabel = "Whisper",
        subtitle = "Existing whisper.cpp Q5_1 · 99 languages (Tiny / Base / Small)",
    ),
    AKSHAR_V4(
        id = "akshar-v4",
        displayName = "AksharSTT v4",
        shortLabel = "Akshar v4",
        subtitle = "29.5 MB · Apache-2.0 · Hinglish code-switching (mean WER 20.78%)",
    ),
    AKSHAR_LITE_V4(
        id = "akshar-lite-v4",
        displayName = "AksharSTT Lite v4",
        shortLabel = "Akshar Lite",
        subtitle = "20.4 MB · Apache-2.0 · Ultra-light Hinglish (mean WER 21.71%)",
    ),
    SHRUTI_HI(
        id = "shruti-hi",
        displayName = "Shruti Hindi/Hinglish",
        shortLabel = "Shruti",
        subtitle = "29.5 MB · Apache-2.0 · Udgam Labs Hindi + English mixed-script ASR",
    ),
}

/** Output script formatting applied on top of canonical ASR transcripts. */
@Serializable
enum class AsrOutputScript(
    val id: String,
    val displayName: String,
    val shortLabel: String,
    val example: String,
) {
    DEVANAGARI_HINGLISH(
        id = "devanagari_hinglish",
        displayName = "Devanagari Hinglish",
        shortLabel = "Devanagari",
        example = "भाई कल meeting है, तुम presentation भेज देना।",
    ),
    ROMAN_HINGLISH(
        id = "roman_hinglish",
        displayName = "Roman Hinglish",
        shortLabel = "Roman",
        example = "Bhai kal meeting hai, tum presentation bhej dena.",
    ),
    BOTH(
        id = "both",
        displayName = "Both Scripts",
        shortLabel = "Both",
        example = "Shows Devanagari Hinglish and Roman Hinglish together",
    ),
}

/** Verified downloadable `.cact` model artifact metadata pinned to official Hugging Face commits. */
data class HinglishModelSpec(
    val kind: AsrEngineKind,
    val id: String,
    val displayName: String,
    val publisher: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val exactSizeBytes: Long,
    val sha256: String,
    val approxRamMb: Int,
    val minRequiredAvailRamMb: Long,
    val license: String,
    val runtimeName: String,
    val defaultLanguage: String,
    val supportedLanguages: Set<String>,
    val description: String,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

/**
 * Verified catalog of official Apache-2.0 Hinglish ASR models backed by Cactus 4.0.0 (`libcactus.a`).
 * Every filename, byte size, revision SHA, and LFS SHA-256 checksum was verified against Hugging Face.
 */
object HinglishModelCatalog {
    const val CATALOG_VERSION = 1

    val AKSHAR_V4 = HinglishModelSpec(
        kind = AsrEngineKind.AKSHAR_V4,
        id = "akshar-v4",
        displayName = "AksharSTT v4",
        publisher = "ByteKnot Labs",
        repo = "byteknotlabs/AksharSTT-V4",
        revision = "dc56f031e121941d77216ca6a80e3e05368bcf2c",
        fileName = "akshar-v4.cact",
        exactSizeBytes = 29_546_527L,
        sha256 = "076782372806bc2f6a74299d857fd37b22ec1609ddfbab5a02c2078d5493bd75",
        approxRamMb = 80,
        minRequiredAvailRamMb = 220L,
        license = "Apache-2.0",
        runtimeName = "Cactus 4.0.0 (arm64-v8a)",
        defaultLanguage = "hi",
        supportedLanguages = setOf("hi", "en"),
        description = "4-bit Cactus Whistle model fine-tuned for Hindi, Indian English, and Hinglish code-switching.",
    )

    val AKSHAR_LITE_V4 = HinglishModelSpec(
        kind = AsrEngineKind.AKSHAR_LITE_V4,
        id = "akshar-lite-v4",
        displayName = "AksharSTT Lite v4",
        publisher = "ByteKnot Labs",
        repo = "byteknotlabs/AksharSTT-Lite-V4",
        revision = "7f879b2dd5303c6c065fcce4c1478f35833cfd3c",
        fileName = "akshar-lite-v4.cact",
        exactSizeBytes = 20_363_295L,
        sha256 = "9a7bdf9be822d66758a13ad0ec9ef90c6ec47a88a9be1aeb526ba9d3207eff6b",
        approxRamMb = 64,
        minRequiredAvailRamMb = 180L,
        license = "Apache-2.0",
        runtimeName = "Cactus 4.0.0 (arm64-v8a)",
        defaultLanguage = "hi",
        supportedLanguages = setOf("hi", "en"),
        description = "2-bit decoder / 4-bit encoder Hinglish model for low-RAM Android devices.",
    )

    val SHRUTI_HI = HinglishModelSpec(
        kind = AsrEngineKind.SHRUTI_HI,
        id = "shruti-hi",
        displayName = "Shruti Hindi/Hinglish",
        publisher = "Udgam Labs",
        repo = "udgamlabs/Shruti",
        revision = "11f26f7ccdc0324d623937601fe497efceca6e6e",
        fileName = "shruti.cact",
        exactSizeBytes = 29_546_527L,
        sha256 = "b189e954aaed4a9fd41acbad3ac05e486381e96325b22a4c695b9ea5153d7065",
        approxRamMb = 80,
        minRequiredAvailRamMb = 220L,
        license = "Apache-2.0",
        runtimeName = "Cactus 4.0.0 (arm64-v8a)",
        defaultLanguage = "hi",
        supportedLanguages = setOf("hi", "en"),
        description = "55.5M-param 4-bit Cactus Whistle model for Hindi + English mixed-script speech.",
    )

    val ALL_CACTUS_MODELS: List<HinglishModelSpec> = listOf(
        AKSHAR_V4,
        AKSHAR_LITE_V4,
        SHRUTI_HI,
    )

    fun specFor(kind: AsrEngineKind): HinglishModelSpec? = when (kind) {
        AsrEngineKind.AKSHAR_V4 -> AKSHAR_V4
        AsrEngineKind.AKSHAR_LITE_V4 -> AKSHAR_LITE_V4
        AsrEngineKind.SHRUTI_HI -> SHRUTI_HI
        AsrEngineKind.WHISPER -> null
    }

    /** Verified Android compatibility note for future Maya Transcribe evaluation. */
    const val MAYA_TRANSCRIBE_COMPATIBILITY_NOTE =
        "Maya Transcribe (maya-research/maya-transcribe, Qwen3-ASR-0.6B-hf, Apache-2.0) currently ships " +
            "only PyTorch safetensors weights (~1.2 GB) requiring Python transformers (`qwen3_asr`) with no " +
            "official Android ONNX, GGUF, LiteRT, or NDK runtime. Reserved for future integration once an " +
            "official mobile quantized runtime is published."
}
