package com.uoai.asr.engines

/**
 * Common transcript representation carrying the canonical ASR output alongside both
 * Roman Hinglish and Devanagari Hinglish script forms without mutating original meaning.
 */
data class HinglishTranscript(
    /** Exact canonical text emitted by the ASR engine (preserved untouched). */
    val canonicalText: String,
    /** Hindi words transliterated to natural Roman Hinglish; English/numbers/punctuation preserved. */
    val romanHinglish: String,
    /** Mixed-script Devanagari Hinglish (Hindi in Devanagari, English preserved in Latin). */
    val devanagariHinglish: String,
) {
    fun forScript(script: AsrOutputScript): String = when (script) {
        AsrOutputScript.ROMAN_HINGLISH -> romanHinglish
        AsrOutputScript.DEVANAGARI_HINGLISH -> devanagariHinglish
        AsrOutputScript.BOTH -> when {
            devanagariHinglish.isBlank() -> romanHinglish
            romanHinglish.isBlank() -> devanagariHinglish
            devanagariHinglish == romanHinglish -> devanagariHinglish
            else -> "$devanagariHinglish\n$romanHinglish"
        }
    }
}

/**
 * Offline, language-aware Hinglish script converter supporting bidirectional formatting between
 * mixed-script Devanagari Hinglish and natural Roman Hinglish.
 *
 * Design guarantees:
 * - Preserves the canonical ASR transcript untouched.
 * - Transliterates ONLY Devanagari tokens into natural colloquial Roman Hinglish (no stiff IAST diacritics).
 * - Preserves English words, technical terms, acronyms (`API`, `JSON`, `5G`), numbers, URLs, emails,
 *   abbreviations (`Dr.`, `Mr.`), and punctuation completely untouched.
 * - Never translates sentence meaning.
 * - Reverse Roman -> Devanagari conversion converts ONLY high-confidence unambiguous Roman Hindi words
 *   when canonical text has no Devanagari, strictly protecting English vocabulary.
 */
object HinglishScriptConverter {

    private val TOKEN_REGEX = Regex("""(\s+|[^\s]+)""")
    private val PUNCT_WRAP_REGEX = Regex("""^([^\p{L}\p{N}\u0900-\u097F]*)(.*?)([^\p{L}\p{N}\u0900-\u097F]*)$""")
    private val PROTECTED_PATTERN = Regex(
        """^(https?://\S+|www\.\S+|\S+@\S+\.\S+|[@#]\S+|\d+([.,:/-]\d+)*[a-zA-Z%]*|[A-Z]{2,}[0-9]*)$""",
    )

    fun convert(canonicalText: String): HinglishTranscript {
        val clean = canonicalText.trim()
        if (clean.isEmpty()) return HinglishTranscript("", "", "")
        val hasDevanagari = containsDevanagari(clean)
        val roman = if (hasDevanagari) toRomanHinglish(clean) else clean
        val devanagari = if (hasDevanagari) normalizeDevanagariHinglish(clean) else toDevanagariHinglish(clean)
        return HinglishTranscript(
            canonicalText = clean,
            romanHinglish = roman,
            devanagariHinglish = devanagari,
        )
    }

    fun containsDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }

    /**
     * Converts mixed-script Devanagari Hinglish into natural Roman Hinglish while leaving
     * English words, numbers, abbreviations, URLs, and punctuation untouched.
     */
    fun toRomanHinglish(input: String): String {
        if (input.isBlank()) return ""
        val sb = StringBuilder(input.length + 16)
        var capitalizeNext = true

        for (match in TOKEN_REGEX.findAll(input)) {
            val token = match.value
            if (token.isBlank()) {
                sb.append(token)
                continue
            }

            if (!containsDevanagari(token)) {
                // Pure Latin / English / numeric / symbol token: preserve completely untouched
                sb.append(token)
                capitalizeNext = endsSentence(token)
                continue
            }

            // Convert Devanagari danda punctuation cleanly
            val normalizedToken = token.replace('।', '.').replace('॥', '.')
            val parts = PUNCT_WRAP_REGEX.matchEntire(normalizedToken)
            if (parts == null) {
                sb.append(transliterateDevanagariWord(normalizedToken))
                capitalizeNext = endsSentence(normalizedToken)
                continue
            }

            val leading = parts.groupValues[1]
            val core = parts.groupValues[2]
            val trailing = parts.groupValues[3]

            sb.append(leading)
            if (core.isNotEmpty()) {
                val converted = if (containsDevanagari(core)) {
                    transliterateMixedCore(core)
                } else {
                    core
                }
                val finalWord = if (capitalizeNext && converted.isNotEmpty() && converted[0] in 'a'..'z') {
                    converted[0].uppercaseChar() + converted.substring(1)
                } else {
                    converted
                }
                sb.append(finalWord)
            }
            sb.append(trailing)
            capitalizeNext = endsSentence(trailing) || (trailing.isEmpty() && endsSentence(core))
        }
        return sb.toString().trim()
    }

    /**
     * Converts pure Roman Hinglish into mixed Devanagari Hinglish by converting ONLY
     * unambiguous Roman Hindi words while keeping all English, technical, and numeric words in Latin.
     */
    fun toDevanagariHinglish(input: String): String {
        if (input.isBlank()) return ""
        if (containsDevanagari(input)) return normalizeDevanagariHinglish(input)

        val sb = StringBuilder(input.length + 16)
        for (match in TOKEN_REGEX.findAll(input)) {
            val token = match.value
            if (token.isBlank() || PROTECTED_PATTERN.matches(token)) {
                sb.append(token)
                continue
            }
            val parts = PUNCT_WRAP_REGEX.matchEntire(token)
            if (parts == null) {
                sb.append(token)
                continue
            }
            val leading = parts.groupValues[1]
            val core = parts.groupValues[2]
            val trailing = parts.groupValues[3]

            val lower = core.lowercase()
            val mapped = if (core.isNotEmpty() && lower !in PROTECTED_ENGLISH_WORDS && !isCamelOrTech(core)) {
                ROMAN_TO_DEVANAGARI_HINDI[lower]
            } else {
                null
            }
            sb.append(leading)
            sb.append(mapped ?: core)
            sb.append(trailing)
        }
        return sb.toString().trim()
    }

    private fun normalizeDevanagariHinglish(input: String): String =
        input.replace(Regex("\\s+([,.?!।])"), "$1").trim()

    private fun transliterateMixedCore(core: String): String {
        // Handle hyphenated/slash compounds like "दिल्ली-NCR" or "10-बजे" piece-by-piece
        val out = StringBuilder()
        var i = 0
        while (i < core.length) {
            val c = core[i]
            if (c in '\u0900'..'\u097F') {
                val start = i
                while (i < core.length && core[i] in '\u0900'..'\u097F') i++
                val seg = core.substring(start, i)
                out.append(transliterateDevanagariWord(seg))
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /**
     * Transliterates a single Devanagari word into colloquial Roman Hinglish using:
     * 1. High-frequency Hindi/Hinglish/UP-Bihar dictionary overrides.
     * 2. Phonetic Devanagari G2P with Hindi schwa-deletion phonology and homorganic anusvara.
     */
    internal fun transliterateDevanagariWord(word: String): String {
        if (word.isEmpty()) return ""
        // Pure Devanagari digits
        if (word.all { it in '\u0966'..'\u096F' }) {
            return word.map { ('0'.code + (it.code - 0x0966)).toChar() }.joinToString("")
        }
        DEVANAGARI_TO_ROMAN_LEXICON[word]?.let { return it }

        // Tokenize Devanagari word into syllable units (independent vowel / consonant cluster + matra/modifier)
        val units = parseSyllableUnits(word)
        if (units.isEmpty()) return word

        val sb = StringBuilder(word.length * 2)
        for (idx in units.indices) {
            val u = units[idx]
            if (u.literal != null) {
                sb.append(u.literal)
                continue
            }
            sb.append(u.consonantRoman)

            val isLast = idx == units.lastIndex
            val next = units.getOrNull(idx + 1)

            if (u.hasHalant) {
                // Explicit virama suppresses inherent vowel completely
            } else if (u.matraRoman != null) {
                sb.append(u.matraRoman)
            } else {
                // Inherent schwa ('a') — apply Hindi schwa-deletion rules:
                // 1. Suppress word-final schwa on multi-unit words unless preceded by a conjunct/cluster
                //    or special ending (e.g. 'य' -> 'ya' in कार्य/मुख्य).
                // 2. Suppress medial schwa in open CVCəCV environments (e.g. करना -> karna, सकता -> sakta).
                val keepSchwa = when {
                    units.size == 1 -> true
                    isLast -> u.precededByHalant && u.rawConsonant in setOf('य', 'र', 'व', 'ल')
                    shouldDeleteMedialSchwa(units, idx, next) -> false
                    else -> true
                }
                if (keepSchwa) sb.append('a')
            }

            // Modifiers (anusvara, chandrabindu, visarga)
            when (u.modifier) {
                '\u0902' -> { // Anusvara (ं)
                    val labialNext = next?.rawConsonant in setOf('प', 'फ', 'ब', 'भ', 'म')
                    sb.append(if (labialNext) 'm' else 'n')
                }
                '\u0901' -> sb.append('n') // Chandrabindu (ँ)
                '\u0903' -> sb.append('h') // Visarga (ः)
            }
        }
        return sb.toString()
    }

    private data class SyllableUnit(
        val literal: String? = null,
        val rawConsonant: Char? = null,
        val consonantRoman: String = "",
        val matraRoman: String? = null,
        val hasHalant: Boolean = false,
        val modifier: Char? = null,
        val precededByHalant: Boolean = false,
    )

    private fun parseSyllableUnits(word: String): List<SyllableUnit> {
        val units = ArrayList<SyllableUnit>(word.length)
        var i = 0
        var prevHadHalant = false

        while (i < word.length) {
            val ch = word[i]
            when {
                ch in '\u0966'..'\u096F' -> {
                    units += SyllableUnit(literal = ('0'.code + (ch.code - 0x0966)).toChar().toString())
                    prevHadHalant = false
                    i++
                }
                ch in INDEPENDENT_VOWELS -> {
                    var mod: Char? = null
                    if (i + 1 < word.length && word[i + 1] in MODIFIERS) {
                        mod = word[++i]
                    }
                    val base = INDEPENDENT_VOWELS.getValue(ch)
                    val suffix = when (mod) {
                        '\u0902' -> "n"
                        '\u0901' -> "n"
                        '\u0903' -> "h"
                        else -> ""
                    }
                    units += SyllableUnit(literal = base + suffix)
                    prevHadHalant = false
                    i++
                }
                ch in CONSONANTS -> {
                    var cBase = ch
                    var hasNukta = false
                    if (i + 1 < word.length && word[i + 1] == '\u093C') {
                        hasNukta = true
                        i++
                    }
                    val romanC = if (hasNukta) {
                        NUKTA_CONSONANTS[cBase] ?: CONSONANTS.getValue(cBase)
                    } else {
                        CONSONANTS.getValue(cBase)
                    }

                    var halant = false
                    var matra: String? = null
                    var mod: Char? = null

                    if (i + 1 < word.length) {
                        val peek = word[i + 1]
                        if (peek == '\u094D') {
                            halant = true
                            i++
                        } else if (peek in MATRAS) {
                            matra = MATRAS.getValue(peek)
                            i++
                        }
                    }
                    if (i + 1 < word.length && word[i + 1] in MODIFIERS) {
                        mod = word[++i]
                    }

                    // Special conjunct क्ष (ksh), त्र (tr), ज्ञ (gy)
                    if (halant && cBase == 'क' && i + 1 < word.length && word[i + 1] == 'ष') {
                        // Let standard k + sh flow cleanly ("ksh")
                    }

                    units += SyllableUnit(
                        rawConsonant = cBase,
                        consonantRoman = romanC,
                        matraRoman = matra,
                        hasHalant = halant,
                        modifier = mod,
                        precededByHalant = prevHadHalant,
                    )
                    prevHadHalant = halant
                    i++
                }
                else -> {
                    // Any remaining Devanagari punctuation/marks
                    if (ch == '।') units += SyllableUnit(literal = ".")
                    prevHadHalant = false
                    i++
                }
            }
        }
        return units
    }

    private fun shouldDeleteMedialSchwa(
        units: List<SyllableUnit>,
        idx: Int,
        next: SyllableUnit?,
    ): Boolean {
        if (idx == 0 || next == null) return false
        val cur = units[idx]
        if (cur.precededByHalant || cur.modifier != null) return false
        if (next.literal != null || next.hasHalant) return false
        // Next syllable carries a full vowel matra (e.g. कर+ना -> karna, सक+ते -> sakte, मिल+ता -> milta)
        val prev = units[idx - 1]
        val prevHasVowel = prev.literal != null || !prev.hasHalant
        return prevHasVowel && next.matraRoman != null && idx < units.lastIndex
    }

    private fun endsSentence(s: String): Boolean =
        s.endsWith('.') || s.endsWith('?') || s.endsWith('!') || s.endsWith('।')

    private fun isCamelOrTech(word: String): Boolean {
        if (word.any { it.isDigit() }) return true
        if (word.length >= 2 && word.all { it.isUpperCase() }) return true
        return word.drop(1).any { it.isUpperCase() }
    }

    private val MODIFIERS = setOf('\u0901', '\u0902', '\u0903')

    private val INDEPENDENT_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee",
        'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri", 'ए' to "e",
        'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ॲ' to "a",
    )

    private val MATRAS = mapOf(
        '\u093E' to "a",  // ा (colloquial Hinglish prefers single 'a' in most positions; lexicon overrides 'aa' words)
        '\u093F' to "i",  // ि
        '\u0940' to "i",  // ी (colloquial Hinglish: "bhai", "nahi", "karungi", "ladki")
        '\u0941' to "u",  // ु
        '\u0942' to "oo", // ू
        '\u0943' to "ri", // ृ
        '\u0947' to "e",  // े
        '\u0948' to "ai", // ै
        '\u094B' to "o",  // ो
        '\u094C' to "au", // ौ
        '\u0949' to "o",  // ॉ
        '\u0945' to "e",  // ॅ
    )

    private val CONSONANTS: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l",
        '\u0958' to "q",  // precomposed क़
        '\u0959' to "kh", // precomposed ख़
        '\u095A' to "gh", // precomposed ग़
        '\u095B' to "z",  // precomposed ज़
        '\u095C' to "d",  // precomposed ड़
        '\u095D' to "dh", // precomposed ढ़
        '\u095E' to "f",  // precomposed फ़
        '\u095F' to "y",  // precomposed य़
    )

    private val NUKTA_CONSONANTS: Map<Char, String> = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z",
        'ड' to "d", 'ढ' to "dh", 'फ' to "f",
    )

    /** High-frequency colloquial Hinglish & regional (UP/Bihar) dictionary overrides. */
    private val DEVANAGARI_TO_ROMAN_LEXICON: Map<String, String> = mapOf(
        // Pronouns & Core Grammar
        "मैं" to "main", "मै" to "main", "मेरा" to "mera", "मेरी" to "meri", "मेरे" to "mere",
        "मुझे" to "mujhe", "मुझको" to "mujhko", "हम" to "hum", "हमारा" to "hamara", "हमारी" to "hamari",
        "हमारे" to "hamare", "हमें" to "hamein", "तुम" to "tum", "तुम्हारा" to "tumhara", "तुम्हारी" to "tumhari",
        "तुम्हारे" to "tumhare", "तुम्हें" to "tumhein", "तू" to "tu", "तेरा" to "tera", "तेरी" to "teri",
        "आप" to "aap", "आपका" to "aapka", "आपकी" to "aapki", "आपके" to "aapke", "आपको" to "aapko",
        "यह" to "yeh", "ये" to "ye", "वह" to "woh", "वो" to "woh", "वे" to "ve", "उस" to "us",
        "उसका" to "uska", "उसकी" to "uski", "उसके" to "uske", "उसको" to "usko", "उसे" to "use",
        "उन्हें" to "unhein", "उनका" to "unka", "उनकी" to "unki", "उनके" to "unke",
        "इस" to "is", "इसका" to "iska", "इसकी" to "iski", "इसके" to "iske", "इसको" to "isko",
        "इसे" to "ise", "इन" to "in", "इनका" to "inka", "अपना" to "apna", "अपनी" to "apni", "अपने" to "apne",
        "कौन" to "kaun", "क्या" to "kya", "क्यों" to "kyon", "क्योंकि" to "kyonki", "कब" to "kab",
        "कहाँ" to "kahan", "कहां" to "kahan", "कैसे" to "kaise", "कैसा" to "kaisa", "कैसी" to "kaisi",
        "कितना" to "kitna", "कितनी" to "kitni", "कितने" to "kitne", "कोई" to "koi", "कुछ" to "kuch",
        "सब" to "sab", "सभी" to "sabhi", "जो" to "jo", "जिस" to "jis", "जिसे" to "jise", "जिसका" to "jiska",

        // Postpositions & Conjunctions
        "है" to "hai", "हैं" to "hain", "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho",
        "था" to "tha", "थी" to "thi", "थे" to "the", "थीं" to "thin",
        "में" to "mein", "मे" to "mein", "पर" to "par", "पे" to "pe", "से" to "se",
        "को" to "ko", "का" to "ka", "की" to "ki", "के" to "ke", "ने" to "ne", "कि" to "ki",
        "और" to "aur", "या" to "ya", "लेकिन" to "lekin", "मगर" to "magar", "परंतु" to "parantu",
        "तो" to "toh", "भी" to "bhi", "ही" to "hi", "तक" to "tak", "लिए" to "liye", "लिये" to "liye",
        "साथ" to "saath", "बाद" to "baad", "पहले" to "pehle", "ऊपर" to "upar", "नीचे" to "neeche",
        "अंदर" to "andar", "बाहर" to "bahar", "पास" to "paas", "दूर" to "door", "बीच" to "beech",
        "अगर" to "agar", "यदि" to "yadi", "जब" to "jab", "तब" to "tab", "जैसे" to "jaise", "वैसे" to "waise",
        "ताकि" to "taaki", "बल्कि" to "balki", "फिर" to "phir", "वरना" to "warna",

        // Negation, Affirmation & Conversational Hinglish
        "नहीं" to "nahi", "नही" to "nahi", "ना" to "na", "मत" to "mat",
        "हाँ" to "haan", "हां" to "haan", "जी" to "ji", "ठीक" to "theek", "अच्छा" to "accha",
        "अच्छी" to "acchi", "अच्छे" to "acche", "बढ़िया" to "badhiya", "सही" to "sahi", "गलत" to "galat",
        "भाई" to "bhai", "भइया" to "bhaiya", "भैया" to "bhaiya", "यार" to "yaar", "दोस्त" to "dost",
        "सर" to "sir", "मैडम" to "madam", "अरे" to "arey", "ओके" to "okay", "बिल्कुल" to "bilkul",
        "शायद" to "shayad", "ज़रूर" to "zaroor", "जरूर" to "zaroor", "ज़रूरी" to "zaroori", "जरूरी" to "zaroori",
        "मतलब" to "matlab", "बस" to "bas", "सिर्फ" to "sirf", "केवल" to "keval", "थोड़ा" to "thoda",
        "थोड़ी" to "thodi", "थोड़े" to "thode", "बहुत" to "bahut", "ज्यादा" to "zyada", "ज़्यादा" to "zyada",
        "कम" to "kam", "पूरा" to "poora", "पूरी" to "poori", "पूरे" to "poore", "अलग" to "alag",
        "नया" to "naya", "नई" to "nayi", "नये" to "naye", "पुराना" to "purana", "बड़ा" to "bada", "छोटा" to "chhota",

        // Time & Place
        "आज" to "aaj", "कल" to "kal", "परसों" to "parson", "अभी" to "abhi", "अब" to "ab",
        "सुबह" to "subah", "शाम" to "shaam", "रात" to "raat", "दिन" to "din", "वक्त" to "waqt",
        "वक़्त" to "waqt", "समय" to "samay", "घंटा" to "ghanta", "घंटे" to "ghante", "मिनट" to "minute",
        "साल" to "saal", "महीना" to "mahina", "हफ्ता" to "hafta", "तारीख" to "tareekh", "बजे" to "baje",
        "यहाँ" to "yahan", "यहां" to "yahan", "वहाँ" to "wahan", "वहां" to "wahan", "इधर" to "idhar", "उधर" to "udhar",
        "घर" to "ghar", "ऑफिस" to "office", "दुकान" to "dukaan", "बाज़ार" to "bazaar", "शहर" to "shehar",
        "गाँव" to "gaon", "देश" to "desh", "भारत" to "Bharat", "दिल्ली" to "Delhi", "मुंबई" to "Mumbai",
        "पटना" to "Patna", "लखनऊ" to "Lucknow", "बेंगलुरु" to "Bengaluru", "बिहार" to "Bihar",

        // Common Verbs
        "कर" to "kar", "करना" to "karna", "करते" to "karte", "करता" to "karta", "करती" to "karti",
        "करो" to "karo", "करें" to "karein", "किया" to "kiya", "किए" to "kiye", "करूंगा" to "karunga",
        "करूँगा" to "karunga", "करेंगे" to "karenge", "करना" to "karna",
        "होना" to "hona", "होता" to "hota", "होती" to "hoti", "होते" to "hote", "हुआ" to "hua",
        "हुई" to "hui", "हुए" to "hue", "होगा" to "hoga", "होगी" to "hogi", "होंगे" to "honge",
        "जा" to "jaa", "जाना" to "jaana", "जाता" to "jaata", "जाती" to "jaati", "जाते" to "jaate",
        "जाओ" to "jaao", "गया" to "gaya", "गई" to "gayi", "गए" to "gaye", "जाएगा" to "jayega",
        "आ" to "aa", "आना" to "aana", "आता" to "aata", "आती" to "aati", "आते" to "aate",
        "आया" to "aaya", "आई" to "aayi", "आए" to "aaye", "आएगा" to "aayega", "आयेगा" to "aayega",
        "दे" to "de", "देना" to "dena", "देता" to "deta", "देती" to "deti", "देते" to "dete",
        "दो" to "do", "दिया" to "diya", "दी" to "di", "दिए" to "diye", "दूँगा" to "dunga", "देंगे" to "denge",
        "ले" to "le", "लेना" to "lena", "लेता" to "leta", "लिया" to "liya", "ली" to "li", "लो" to "lo",
        "भेज" to "bhej", "भेजना" to "bhejna", "भेजा" to "bheja", "भेजो" to "bhejo", "भेजूंगा" to "bhejunga",
        "बोल" to "bol", "बोलना" to "bolna", "बोला" to "bola", "बोलो" to "bolo",
        "कह" to "keh", "कहना" to "kehna", "कहा" to "kaha", "बता" to "bata", "बताना" to "batana",
        "बताओ" to "batao", "बताया" to "bataya", "समझ" to "samajh", "समझना" to "samajhna", "समझा" to "samjha",
        "देख" to "dekh", "देखना" to "dekhna", "देखा" to "dekha", "देखो" to "dekho", "दिख" to "dikh",
        "सुन" to "sun", "सुनना" to "sunna", "सुना" to "suna", "सुनो" to "suno",
        "मिल" to "mil", "मिलना" to "milna", "मिला" to "mila", "मिलेगा" to "milega", "मिलते" to "milte",
        "चल" to "chal", "चलना" to "chalna", "चलो" to "chalo", "चला" to "chala",
        "रख" to "rakh", "रखना" to "rakhna", "रखा" to "rakha", "रहो" to "raho", "रहना" to "rehna",
        "रहा" to "raha", "रही" to "rahi", "रहे" to "rahe",
        "सकता" to "sakta", "सकती" to "sakti", "सकते" to "sakte",
        "चाहिए" to "chahiye", "चाहिये" to "chahiye", "चाहता" to "chahta", "चाहती" to "chahti", "चाहते" to "chahte",
        "पड़" to "pad", "पड़ता" to "padta", "पड़ेगा" to "padega", "लग" to "lag", "लगता" to "lagta",
        "लगती" to "lagti", "लगते" to "lagte", "लगा" to "laga", "लिख" to "likh", "लिखना" to "likhna",
        "पढ़" to "padh", "पढ़ना" to "padhna", "काम" to "kaam", "बात" to "baat", "नाम" to "naam",
        "पैसा" to "paisa", "पैसे" to "paise", "रुपये" to "rupaye", "रुपए" to "rupaye", "लोग" to "log",

        // Numbers
        "एक" to "ek", "दो" to "do", "तीन" to "teen", "चार" to "chaar", "पाँच" to "paanch", "पांच" to "paanch",
        "छह" to "chhe", "सात" to "saat", "आठ" to "aath", "नौ" to "nau", "दस" to "das",
        "ग्यारह" to "gyarah", "बारह" to "barah", "पंद्रह" to "pandrah", "बीस" to "bees", "पच्चीस" to "pachis",
        "तीस" to "tees", "पचास" to "pachaas", "सौ" to "sau", "हज़ार" to "hazaar", "हजार" to "hazaar", "लाख" to "lakh",

        // Regional Bihar / UP colloquial forms
        "बा" to "ba", "बानी" to "baani", "हमार" to "hamar", "तोहर" to "tohar", "रउआ" to "raua",
        "काहे" to "kaahe", "कइसे" to "kaise", "हौ" to "hau", "बाटे" to "baate", "गइल" to "gail",
    )

    /**
     * Strictly unambiguous Roman Hindi -> Devanagari reverse table (used ONLY when ASR emits pure Roman).
     * Never includes words that collide with English words (e.g. `do`, `in`, `is`, `to`, `me`, `na`, `he`, `us`, `use`, `hum`, `log`, `par`, `ab`, `ba`).
     */
    private val ROMAN_TO_DEVANAGARI_HINDI: Map<String, String> = mapOf(
        "bhai" to "भाई", "bhaiya" to "भैया", "yaar" to "यार", "dost" to "दोस्त",
        "kal" to "कल", "aaj" to "आज", "abhi" to "अभी", "subah" to "सुबह", "shaam" to "शाम", "raat" to "रात",
        "hai" to "है", "hain" to "हैं", "hoon" to "हूँ", "tha" to "था", "thi" to "थी",
        "nahi" to "नहीं", "nahin" to "नहीं", "haan" to "हाँ", "theek" to "ठीक", "accha" to "अच्छा",
        "achha" to "अच्छा", "badhiya" to "बढ़िया", "sahi" to "सही", "galat" to "गलत",
        "tum" to "तुम", "tumhara" to "तुम्हारा", "tumhari" to "तुम्हारी", "tumhare" to "तुम्हारे",
        "aap" to "आप", "aapka" to "आपका", "aapki" to "आपकी", "aapke" to "आपके", "aapko" to "आपको",
        "mera" to "मेरा", "meri" to "मेरी", "mere" to "मेरे", "mujhe" to "मुझे",
        "hamara" to "हमारा", "hamari" to "हमारी", "hamare" to "हमारे",
        "yeh" to "यह", "woh" to "वह", "uska" to "उसका", "uski" to "उसकी", "uske" to "उसके",
        "kya" to "क्या", "kyon" to "क्यों", "kyun" to "क्यों", "kyonki" to "क्योंकि", "kyunki" to "क्योंकि",
        "kab" to "कब", "kahan" to "कहाँ", "kaise" to "कैसे", "kaun" to "कौन", "kitna" to "कितना", "kitne" to "कितने",
        "kuch" to "कुछ", "sab" to "सब", "sabhi" to "सभी", "apna" to "अपना", "apni" to "अपनी", "apne" to "अपने",
        "mein" to "में", "aur" to "और", "lekin" to "लेकिन", "magar" to "मगर", "toh" to "तो", "bhi" to "भी",
        "liye" to "लिए", "saath" to "साथ", "baad" to "बाद", "pehle" to "पहले", "agar" to "अगर",
        "jaise" to "जैसे", "waise" to "वैसे", "phir" to "फिर", "fir" to "फिर", "bilkul" to "बिल्कुल",
        "shayad" to "शायद", "zaroor" to "ज़रूर", "zaroori" to "ज़रूरी", "matlab" to "मतलब",
        "bahut" to "बहुत", "bohot" to "बहुत", "zyada" to "ज़्यादा", "jyada" to "ज़्यादा", "thoda" to "थोड़ा",
        "bhej" to "भेज", "bhejna" to "भेजना", "bheja" to "भेजा", "bhejo" to "भेजो",
        "dena" to "देना", "dedo" to "दे दो", "diya" to "दिया", "dunga" to "दूँगा", "denge" to "देंगे",
        "lena" to "लेना", "liya" to "लिया",
        "kar" to "कर", "karna" to "करना", "karte" to "करते", "karta" to "करता", "karti" to "करती",
        "karo" to "करो", "karein" to "करें", "kiya" to "किया", "karunga" to "करूँगा", "karenge" to "करेंगे",
        "hona" to "होना", "hota" to "होता", "hoti" to "होती", "hote" to "होते", "hua" to "हुआ", "hoga" to "होगा",
        "jaana" to "जाना", "gaya" to "गया", "gayi" to "गई", "gaye" to "गए", "jayega" to "जाएगा",
        "aana" to "आना", "aaya" to "आया", "aayi" to "आई", "aaye" to "आए", "aayega" to "आएगा",
        "bolna" to "बोलना", "bola" to "बोला", "bolo" to "बोलो", "bata" to "बता", "batana" to "बताना", "batao" to "बताओ",
        "dekh" to "देख", "dekhna" to "देखना", "dekha" to "देखा", "dekho" to "देखो",
        "samajh" to "समझ", "samajhna" to "समझना", "samjha" to "समझा",
        "raha" to "रहा", "rahi" to "रही", "rahe" to "रहे", "rehna" to "रहना",
        "sakta" to "सकता", "sakti" to "सकती", "sakte" to "सकते", "chahiye" to "चाहिए",
        "lagta" to "लगता", "lagti" to "लगती", "lagte" to "लगते", "kaam" to "काम", "baat" to "बात", "naam" to "नाम",
        "ghar" to "घर", "paise" to "पैसे", "rupaye" to "रुपये", "yahan" to "यहाँ", "wahan" to "वहाँ",
        "paanch" to "पाँच", "chaar" to "चार", "teen" to "तीन", "hazaar" to "हज़ार", "baje" to "बजे",
        "hamar" to "हमार", "tohar" to "तोहर", "raua" to "रउआ", "baani" to "बानी",
    )

    /** Common English words that must NEVER be converted even if they overlap phonetically. */
    private val PROTECTED_ENGLISH_WORDS: Set<String> = setOf(
        "main", "meeting", "presentation", "workspace", "tabs", "order", "time", "car", "ok", "okay",
        "the", "in", "is", "to", "do", "me", "on", "up", "no", "go", "so", "or", "as", "at", "by",
        "he", "we", "us", "use", "log", "par", "hum", "sir", "madam", "office", "minute", "file",
        "code", "app", "model", "server", "api", "json", "android", "phone", "audio", "speech", "text",
        "email", "link", "check", "update", "build", "deploy", "test", "bug", "fix", "pull", "push",
        "commit", "branch", "merge", "database", "cloud", "offline", "online", "wifi", "network",
        "call", "message", "report", "slide", "slides", "doc", "docs", "sheet", "project", "deadline",
    )
}
