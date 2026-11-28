package com.uoai.asr

import kotlinx.serialization.Serializable

/** User-facing speech recognition profiles mapped to GGML Q5_1 Whisper models. */
@Serializable
enum class AsrProfile(
    val apiId: String,
    val displayName: String,
    val badgeLabel: String,
    val summary: String,
) {
    FAST(
        apiId = "fast",
        displayName = "Fast",
        badgeLabel = "Low resource",
        summary = "Older devices and battery-conscious quick transcription",
    ),
    BALANCED(
        apiId = "balanced",
        displayName = "Balanced",
        badgeLabel = "Recommended",
        summary = "Default everyday multilingual transcription",
    ),
    HIGH_ACCURACY(
        apiId = "high_accuracy",
        displayName = "High Accuracy",
        badgeLabel = "Resource intensive",
        summary = "Difficult or noisy recordings where accuracy matters most",
    ),
}

/** Immutable specification for a supported GGML Whisper Q5_1 model preset. */
data class AsrModelPreset(
    val id: String,
    val profile: AsrProfile,
    val displayName: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val format: String = "ggml",
    val quantization: String = "Q5_1",
    val multilingual: Boolean = true,
    val exactSizeBytes: Long,
    val approxRamMb: Int,
    val minRequiredAvailRamMb: Long,
    val minRecommendedDeviceRamGb: Int,
    val sha256: String,
    val recommended: Boolean = false,
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/$revision/$fileName"
}

/**
 * Verified catalog of official MIT-licensed `ggerganov/whisper.cpp` GGML Q5_1 models
 * pinned to commit `5359861c739e955e79d9a303bcbc70fb988958b1` with exact LFS SHA-256 digests.
 */
object AsrModelCatalog {
    const val CATALOG_VERSION = 1
    const val HF_REPO = "ggerganov/whisper.cpp"
    const val HF_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"

    val FAST_PRESET = AsrModelPreset(
        id = "whisper-tiny-q5",
        profile = AsrProfile.FAST,
        displayName = "Whisper Tiny Q5",
        repo = HF_REPO,
        revision = HF_REVISION,
        fileName = "ggml-tiny-q5_1.bin",
        exactSizeBytes = 32_152_673L,
        approxRamMb = 120,
        minRequiredAvailRamMb = 300L,
        minRecommendedDeviceRamGb = 4,
        sha256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7",
        recommended = false,
    )

    val BALANCED_PRESET = AsrModelPreset(
        id = "whisper-base-q5",
        profile = AsrProfile.BALANCED,
        displayName = "Whisper Base Q5",
        repo = HF_REPO,
        revision = HF_REVISION,
        fileName = "ggml-base-q5_1.bin",
        exactSizeBytes = 59_707_625L,
        approxRamMb = 190,
        minRequiredAvailRamMb = 450L,
        minRecommendedDeviceRamGb = 4,
        sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
        recommended = true,
    )

    val HIGH_ACCURACY_PRESET = AsrModelPreset(
        id = "whisper-small-q5",
        profile = AsrProfile.HIGH_ACCURACY,
        displayName = "Whisper Small Q5",
        repo = HF_REPO,
        revision = HF_REVISION,
        fileName = "ggml-small-q5_1.bin",
        exactSizeBytes = 190_085_487L,
        approxRamMb = 420,
        minRequiredAvailRamMb = 800L,
        minRecommendedDeviceRamGb = 6,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb",
        recommended = false,
    )

    val ALL_PRESETS: List<AsrModelPreset> = listOf(
        FAST_PRESET,
        BALANCED_PRESET,
        HIGH_ACCURACY_PRESET,
    )

    fun forProfile(profile: AsrProfile): AsrModelPreset = when (profile) {
        AsrProfile.FAST -> FAST_PRESET
        AsrProfile.BALANCED -> BALANCED_PRESET
        AsrProfile.HIGH_ACCURACY -> HIGH_ACCURACY_PRESET
    }

    /** Resolves an API model identifier or profile alias (`fast`, `balanced`, `high_accuracy`, `whisper-base-q5`, etc.). */
    fun resolveOrNull(nameOrId: String?): AsrModelPreset? {
        val raw = nameOrId?.trim()?.lowercase() ?: return null
        return ALL_PRESETS.firstOrNull { p ->
            p.id == raw ||
                p.profile.apiId == raw ||
                p.profile.name.lowercase() == raw ||
                p.fileName.lowercase() == raw ||
                p.fileName.substringBeforeLast('.').lowercase() == raw
        }
    }

    /** Common language modes & ISO-639-1 languages shown in Speech Settings and Playground pickers. */
    val COMMON_LANGUAGES: List<Pair<String, String>> = listOf(
        "auto" to "Auto (Lock)",
        "en,hi" to "EN + HI (Hinglish)",
        "en" to "English",
        "hi" to "Hindi (हिन्दी)",
        "mr" to "Marathi (मराठी)",
        "bn" to "Bengali (বাংলা)",
        "ta" to "Tamil (தமிழ்)",
        "te" to "Telugu (తెలుగు)",
        "gu" to "Gujarati (ગુજરાતી)",
        "kn" to "Kannada (ಕನ್ನಡ)",
        "ml" to "Malayalam (മലയാളം)",
        "pa" to "Punjabi (ਪੰਜਾਬੀ)",
        "ur" to "Urdu (اردو)",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "zh" to "Chinese",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ar" to "Arabic",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "it" to "Italian",
        "tr" to "Turkish",
        "vi" to "Vietnamese",
        "id" to "Indonesian",
    )

    val SUPPORTED_LANGUAGE_CODES: Set<String> = setOf(
        "auto", "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar",
        "sv", "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta",
        "no", "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn",
        "sr", "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq",
        "sw", "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd",
        "gu", "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo",
        "tl", "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su", "yue",
    )

    /** Full human-readable directory of all 99 supported Whisper languages plus bilingual presets. */
    val ALL_LANGUAGES: List<Pair<String, String>> by lazy {
        val base = LinkedHashMap<String, String>()
        COMMON_LANGUAGES.forEach { (code, label) -> base[code] = label }
        val extras = listOf(
            "as" to "Assamese", "ne" to "Nepali", "sd" to "Sindhi", "sa" to "Sanskrit", "si" to "Sinhala",
            "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish", "uk" to "Ukrainian", "el" to "Greek",
            "he" to "Hebrew", "th" to "Thai", "fa" to "Persian", "ms" to "Malay", "cs" to "Czech",
            "ro" to "Romanian", "da" to "Danish", "hu" to "Hungarian", "fi" to "Finnish", "no" to "Norwegian",
            "ca" to "Catalan", "hr" to "Croatian", "bg" to "Bulgarian", "sk" to "Slovak", "sl" to "Slovenian",
            "sr" to "Serbian", "lt" to "Lithuanian", "lv" to "Latvian", "et" to "Estonian", "tl" to "Tagalog",
            "sw" to "Swahili", "af" to "Afrikaans", "am" to "Amharic", "az" to "Azerbaijani", "be" to "Belarusian",
            "bs" to "Bosnian", "cy" to "Welsh", "eu" to "Basque", "gl" to "Galician", "ha" to "Hausa",
            "hy" to "Armenian", "is" to "Icelandic", "jw" to "Javanese", "ka" to "Georgian", "kk" to "Kazakh",
            "km" to "Khmer", "la" to "Latin", "lb" to "Luxembourgish", "lo" to "Lao", "mg" to "Malagasy",
            "mi" to "Maori", "mk" to "Macedonian", "mn" to "Mongolian", "mt" to "Maltese", "my" to "Myanmar (Burmese)",
            "nn" to "Nynorsk", "oc" to "Occitan", "ps" to "Pashto", "sn" to "Shona", "so" to "Somali",
            "sq" to "Albanian", "su" to "Sundanese", "tg" to "Tajik", "tk" to "Turkmen", "tt" to "Tatar",
            "uz" to "Uzbek", "yi" to "Yiddish", "yo" to "Yoruba", "yue" to "Cantonese", "bo" to "Tibetan",
            "br" to "Breton", "fo" to "Faroese", "haw" to "Hawaiian", "ht" to "Haitian Creole", "ln" to "Lingala",
            "ba" to "Bashkir",
        )
        extras.forEach { (code, label) -> base.putIfAbsent(code, label) }
        SUPPORTED_LANGUAGE_CODES.forEach { code -> base.putIfAbsent(code, code.uppercase()) }
        base.entries.map { it.key to it.value }
    }

    /**
     * Validates either `"auto"`, a single ISO-639-1 code in [SUPPORTED_LANGUAGE_CODES], or a
     * comma-separated constrained allowlist (e.g. `"en,hi"`).
     */
    fun isValidLanguageSpec(raw: String?): Boolean {
        val spec = raw?.trim()?.lowercase() ?: return true
        if (spec.isEmpty() || spec == "auto") return true
        if (!spec.contains(',')) return spec in SUPPORTED_LANGUAGE_CODES
        val parts = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.isNotEmpty() && parts.all { it != "auto" && it in SUPPORTED_LANGUAGE_CODES }
    }

    /** Canonicalizes a language spec (`"EN , Hi"` -> `"en,hi"`). */
    fun normalizeLanguageSpec(raw: String?): String {
        val spec = raw?.trim()?.lowercase().orEmpty()
        if (spec.isEmpty() || spec == "auto") return "auto"
        if (!spec.contains(',')) return if (spec in SUPPORTED_LANGUAGE_CODES) spec else "auto"
        val valid = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "auto" && it in SUPPORTED_LANGUAGE_CODES }.distinct()
        return if (valid.isEmpty()) "auto" else valid.joinToString(",")
    }

    fun displayNameForLanguage(code: String): String {
        val norm = normalizeLanguageSpec(code)
        return ALL_LANGUAGES.firstOrNull { it.first == norm }?.second
            ?: norm.split(',').joinToString(" + ") { c -> c.uppercase() }
    }
}

