package com.uoai.asr

import org.junit.Assert.*
import org.junit.Test

class AsrLanguageSpecTest {

    @Test
    fun validatesSingleCodesAutoAndConstrainedAllowlists() {
        assertTrue(AsrModelCatalog.isValidLanguageSpec("auto"))
        assertTrue(AsrModelCatalog.isValidLanguageSpec("en"))
        assertTrue(AsrModelCatalog.isValidLanguageSpec("hi"))
        assertTrue(AsrModelCatalog.isValidLanguageSpec("en,hi"))
        assertTrue(AsrModelCatalog.isValidLanguageSpec(" EN , hi , mr "))

        assertFalse(AsrModelCatalog.isValidLanguageSpec("zz"))
        assertFalse(AsrModelCatalog.isValidLanguageSpec("en,zz"))
        assertFalse(AsrModelCatalog.isValidLanguageSpec("auto,en"))
        assertFalse(AsrModelCatalog.isValidLanguageSpec(",,,"))
    }

    @Test
    fun normalizesAndPreservesAllowlistInSettings() {
        assertEquals("en,hi", AsrModelCatalog.normalizeLanguageSpec(" EN , hi , en "))
        assertEquals("auto", AsrModelCatalog.normalizeLanguageSpec(""))
        assertEquals("auto", AsrModelCatalog.normalizeLanguageSpec("invalid_code"))

        val sanitized = AsrSettings(defaultLanguage = "EN,hi").sanitized()
        assertEquals("en,hi", sanitized.defaultLanguage)
    }

    @Test
    fun fullLanguageDirectoryContainsAllSupportedLanguagesAndPresets() {
        val allCodes = AsrModelCatalog.ALL_LANGUAGES.map { it.first }.toSet()
        assertTrue("en,hi" in allCodes)
        for (code in AsrModelCatalog.SUPPORTED_LANGUAGE_CODES) {
            assertTrue("Missing language '$code' in ALL_LANGUAGES", code in allCodes)
        }
        assertEquals("EN + HI (Hinglish)", AsrModelCatalog.displayNameForLanguage("en,hi"))
        assertEquals("Hindi (हिन्दी)", AsrModelCatalog.displayNameForLanguage("hi"))
    }
}
