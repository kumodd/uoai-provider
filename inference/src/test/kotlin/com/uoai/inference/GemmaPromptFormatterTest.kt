package com.uoai.inference

import com.uoai.inference.runtime.GemmaPromptFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class GemmaPromptFormatterTest {
    @Test fun foldsSystemIntoFirstUserTurn() {
        val out = GemmaPromptFormatter.format(listOf(ChatMessage("system", "Be brief."), ChatMessage("user", "Hi")))
        assertEquals("<start_of_turn>user\nBe brief.\n\nHi<end_of_turn>\n<start_of_turn>model\n", out)
    }
}
