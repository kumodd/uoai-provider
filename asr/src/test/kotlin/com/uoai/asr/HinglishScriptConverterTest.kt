package com.uoai.asr

import com.uoai.asr.engines.AsrOutputScript
import com.uoai.asr.engines.HinglishScriptConverter
import org.junit.Assert.*
import org.junit.Test

/**
 * Automated Hinglish transliteration, code-switching, and script-preservation tests
 * backed by a multi-category Hinglish evaluation dataset (conversational Hinglish,
 * Bihar/UP regional accents, Indian English, technical vocabulary, names/locations,
 * numbers/dates, and punctuation).
 */
class HinglishScriptConverterTest {

    data class HinglishDatasetCase(
        val category: String,
        val canonicalMixed: String,
        val expectedRomanMustContain: List<String>,
        val preservedEnglishTokens: List<String>,
    )

    private val dataset = listOf(
        HinglishDatasetCase(
            category = "conversational_hinglish",
            canonicalMixed = "भाई कल meeting है, तुम presentation भेज देना।",
            expectedRomanMustContain = listOf("Bhai", "kal", "meeting", "hai,", "tum", "presentation", "bhej", "dena."),
            preservedEnglishTokens = listOf("meeting", "presentation"),
        ),
        HinglishDatasetCase(
            category = "akshar_canonical_example",
            canonicalMixed = "जैसे कि आप देख सकते हैं workspace में पांच tabs हैं",
            expectedRomanMustContain = listOf("Jaise", "ki", "aap", "dekh", "sakte", "hain", "workspace", "mein", "paanch", "tabs", "hain"),
            preservedEnglishTokens = listOf("workspace", "tabs"),
        ),
        HinglishDatasetCase(
            category = "shruti_canonical_example",
            canonicalMixed = "मेरा order कब आएगा?",
            expectedRomanMustContain = listOf("Mera", "order", "kab", "aayega?"),
            preservedEnglishTokens = listOf("order"),
        ),
        HinglishDatasetCase(
            category = "bihar_up_regional",
            canonicalMixed = "भइया हमार phone का battery charge नहीं बा, काहे wifi बंद बा?",
            expectedRomanMustContain = listOf("Bhaiya", "hamar", "phone", "battery", "charge", "nahi", "ba,", "kaahe", "wifi"),
            preservedEnglishTokens = listOf("phone", "battery", "charge", "wifi"),
        ),
        HinglishDatasetCase(
            category = "technical_vocabulary",
            canonicalMixed = "API का JSON response 200 OK आ रहा है, लेकिन Kotlin coroutine में JNI error है।",
            expectedRomanMustContain = listOf("API", "JSON", "response", "200", "OK", "Kotlin", "coroutine", "JNI", "error"),
            preservedEnglishTokens = listOf("API", "JSON", "response", "200", "OK", "Kotlin", "coroutine", "JNI", "error"),
        ),
        HinglishDatasetCase(
            category = "names_and_locations",
            canonicalMixed = "Dr. Sharma पटना से दिल्ली-NCR जा रहे हैं, Mumbai flight 10:30 बजे है।",
            expectedRomanMustContain = listOf("Dr.", "Sharma", "Patna", "Delhi-NCR", "Mumbai", "flight", "10:30", "baje"),
            preservedEnglishTokens = listOf("Dr.", "Sharma", "NCR", "Mumbai", "flight", "10:30"),
        ),
        HinglishDatasetCase(
            category = "numbers_dates_currency",
            canonicalMixed = "28 Nov 2026 को ₹1,500 UPI से transfer कर देना, 5G speed 98.5% ठीक है।",
            expectedRomanMustContain = listOf("28", "Nov", "2026", "₹1,500", "UPI", "transfer", "5G", "speed", "98.5%", "theek"),
            preservedEnglishTokens = listOf("28", "Nov", "2026", "₹1,500", "UPI", "transfer", "5G", "speed", "98.5%"),
        ),
        HinglishDatasetCase(
            category = "indian_english_pure",
            canonicalMixed = "Please revert back on the PR before EOD, we will deploy v1.0.2 to staging.",
            expectedRomanMustContain = listOf("Please", "revert", "PR", "EOD,", "v1.0.2", "staging."),
            preservedEnglishTokens = listOf("Please", "revert", "PR", "EOD,", "deploy", "v1.0.2", "staging."),
        ),
    )

    @Test
    fun convertsFullHinglishDatasetWhilePreservingCanonicalAndEnglishTokens() {
        for (tc in dataset) {
            val res = HinglishScriptConverter.convert(tc.canonicalMixed)
            // Canonical transcript must be preserved untouched
            assertEquals("[${tc.category}] canonical mismatch", tc.canonicalMixed, res.canonicalText)
            // Roman Hinglish must contain no leftover Devanagari characters
            assertFalse("[${tc.category}] Devanagari leaked into Roman: ${res.romanHinglish}", HinglishScriptConverter.containsDevanagari(res.romanHinglish))
            for (expected in tc.expectedRomanMustContain) {
                assertTrue(
                    "[${tc.category}] Roman '${res.romanHinglish}' missing '$expected'",
                    res.romanHinglish.contains(expected),
                )
            }
            for (en in tc.preservedEnglishTokens) {
                assertTrue(
                    "[${tc.category}] Devanagari '${res.devanagariHinglish}' altered English token '$en'",
                    res.devanagariHinglish.contains(en),
                )
            }
        }
    }

    @Test
    fun exactPromptExampleProducesNaturalRomanAndDevanagari() {
        val canonical = "भाई कल meeting है, तुम presentation भेज देना।"
        val tr = HinglishScriptConverter.convert(canonical)
        assertEquals("Bhai kal meeting hai, tum presentation bhej dena.", tr.romanHinglish)
        assertEquals(canonical, tr.devanagariHinglish)
        assertTrue(tr.forScript(AsrOutputScript.BOTH).contains(canonical))
        assertTrue(tr.forScript(AsrOutputScript.BOTH).contains("Bhai kal meeting hai"))
    }

    @Test
    fun reverseConversionFromPureRomanPreservesEnglishAndTechTerms() {
        val romanInput = "Bhai kal meeting hai, API JSON check kar dena at 10:30."
        val tr = HinglishScriptConverter.convert(romanInput)
        assertEquals(romanInput, tr.romanHinglish)
        assertTrue(tr.devanagariHinglish.contains("भाई"))
        assertTrue(tr.devanagariHinglish.contains("कल"))
        assertTrue(tr.devanagariHinglish.contains("meeting"))
        assertTrue(tr.devanagariHinglish.contains("API"))
        assertTrue(tr.devanagariHinglish.contains("JSON"))
        assertTrue(tr.devanagariHinglish.contains("10:30."))
    }

    @Test
    fun preservesUrlsEmailsAbbreviationsAndDevanagariDigits() {
        val mixed = "लिंक https://akshar.byteknotlabs.com पर है, मेल dev@example.com करो, कुल २५० रुपये।"
        val roman = HinglishScriptConverter.toRomanHinglish(mixed)
        assertTrue(roman.contains("https://akshar.byteknotlabs.com"))
        assertTrue(roman.contains("dev@example.com"))
        assertTrue(roman.contains("250"))
        assertFalse(HinglishScriptConverter.containsDevanagari(roman))
    }
}
