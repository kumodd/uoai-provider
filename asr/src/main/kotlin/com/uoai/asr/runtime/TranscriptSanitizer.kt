package com.uoai.asr.runtime

/**
 * Post-decode hallucination and repetition guard for Whisper ASR segments.
 *
 * Filters:
 * 1. High `no_speech_prob` silence segments and non-speech marker tokens (`[BLANK_AUDIO]`, `[MUSIC]`, etc.).
 * 2. Classic Whisper silence phantom phrases when `no_speech_prob` is elevated.
 * 3. Autoregressive n-gram phrase repetition loops (e.g. `"back, then, when I would come back, then, when I would come back..."`).
 */
object TranscriptSanitizer {
    private val NON_SPEECH_ARTIFACT_REGEX = Regex(
        """^\s*[\[\(]\s*(blank[_\s-]*audio|silence|music|applause|laughter|inaudible|noise|no[_\s-]*speech|sound|sigh|cough)\s*[\]\)]\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val COMMON_SILENCE_PHANTOMS = setOf(
        "thank you",
        "thank you.",
        "thank you!",
        "thanks for watching",
        "thanks for watching.",
        "thanks for watching!",
        "thank you for watching",
        "thank you for watching.",
        "please subscribe",
        "bye",
        "bye.",
        "you",
        "you.",
        "...",
        ".",
    )

    private val PUNCT_STRIP_REGEX = Regex("""[^\p{L}\p{N}]""")

    /**
     * Cleans a single Whisper segment and returns empty string if it is likely a silence hallucination.
     */
    fun cleanSegment(rawText: String, noSpeechProb: Float): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (NON_SPEECH_ARTIFACT_REGEX.matches(trimmed)) return ""

        // Drop high-confidence silence hallucinations
        if (noSpeechProb >= 0.72f) return ""

        val lower = trimmed.lowercase()
        if (noSpeechProb >= 0.45f && lower in COMMON_SILENCE_PHANTOMS) {
            return ""
        }

        // Collapse runaway phrase loops inside the segment
        return collapseRepetitionLoops(trimmed)
    }

    /**
     * Collapses consecutive repeating word n-grams (size 1..14) that repeat >= 3 times
     * (or >= 2 times for long phrases of >= 5 words) down to a single clean occurrence.
     */
    fun collapseRepetitionLoops(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 4) return text.trim()

        val norm = tokens.map { it.lowercase().replace(PUNCT_STRIP_REGEX, "") }
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var matchedPeriod = 0
            var matchedRepeats = 0
            val maxPeriod = minOf(14, (tokens.size - i) / 2)

            // Prefer the smallest repeating phrase period that repeats consecutively
            for (period in 1..maxPeriod) {
                var reps = 1
                while (i + (reps + 1) * period <= tokens.size) {
                    var equal = true
                    for (k in 0 until period) {
                        val a = norm[i + k]
                        val b = norm[i + reps * period + k]
                        if (a.isEmpty() || a != b) {
                            equal = false
                            break
                        }
                    }
                    if (!equal) break
                    reps++
                }
                val threshold = when {
                    period == 1 -> 4 // e.g. "no no no no"
                    period in 2..4 -> 3 // e.g. "and I started working" x3
                    else -> 2 // e.g. 5..14 word phrase repeated back-to-back
                }
                if (reps >= threshold) {
                    matchedPeriod = period
                    matchedRepeats = reps
                    break
                }
            }

            if (matchedPeriod > 0) {
                // Keep one clean copy of the repeated phrase and skip all looped copies
                for (k in 0 until matchedPeriod) {
                    out.add(tokens[i + k])
                }
                i += matchedPeriod * matchedRepeats
            } else {
                out.add(tokens[i])
                i++
            }
        }
        return out.joinToString(" ").trim()
    }

    /**
     * Deduplicates consecutive identical/near-identical segments and re-runs loop collapse
     * across segment boundaries.
     */
    fun cleanJoinedSegments(segments: List<AsrSegment>): String {
        if (segments.isEmpty()) return ""
        val kept = ArrayList<String>(segments.size)
        var lastNorm = ""
        for (seg in segments) {
            val cleaned = cleanSegment(seg.text, seg.noSpeechProb)
            if (cleaned.isEmpty()) continue
            val n = cleaned.lowercase().replace(PUNCT_STRIP_REGEX, "")
            if (n.isNotEmpty() && n == lastNorm) continue
            kept.add(cleaned)
            lastNorm = n
        }
        return collapseRepetitionLoops(kept.joinToString(" "))
    }
}
