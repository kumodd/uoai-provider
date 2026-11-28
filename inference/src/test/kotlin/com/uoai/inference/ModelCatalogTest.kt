package com.uoai.inference

import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun gemma4CatalogIncludesAllFiveModelTiersAndAudioFlags() {
        val gemma4 = ModelCatalog.byFamily(ModelFamily.GEMMA_4)
        assertEquals(ModelCatalog.ALL_PRESETS.size, gemma4.size)
        val tiers = gemma4.map { it.tier }.toSet()
        assertEquals(setOf("E2B", "E4B", "12B", "26B-A4B", "31B"), tiers)
        assertTrue(gemma4.all { !it.gated })
        assertTrue(gemma4.all { it.runtime == ModelRuntimeKind.LITERT_LM })
        assertTrue(gemma4.all { it.maxContextTokens == 32_768 })
        assertTrue(gemma4.filter { it.tier == "E2B" || it.tier == "E4B" }.all { it.supportsAudioInput })
        assertFalse(ModelCatalog.requiresHfToken("litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"))
    }

    @Test fun inferModelIdMapsAllGemma4VariantsCorrectly() {
        assertEquals("gemma-4-e2b", ModelCatalog.inferModelId("gemma-4-E2B-it.litertlm"))
        assertEquals("gemma-4-e2b", ModelCatalog.inferModelId("gemma-4-E2B-it_Google_Tensor_G5.litertlm"))
        assertEquals("gemma-4-e4b", ModelCatalog.inferModelId("gemma-4-E4B-it-gpu.litertlm"))
        assertEquals("gemma-4-12b", ModelCatalog.inferModelId("gemma-4-12B-it.litertlm"))
        assertEquals("gemma-4-26b-a4b", ModelCatalog.inferModelId("gemma-4-26B-A4B-it-gpu.litertlm"))
        assertEquals("gemma-4-31b", ModelCatalog.inferModelId("gemma-4-31B-it-gpu.litertlm"))
    }
}

