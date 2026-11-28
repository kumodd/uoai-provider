package com.uoai.asr

import com.uoai.asr.runtime.AsrSegment
import com.uoai.asr.runtime.TranscriptSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptSanitizerTest {
    @Test
    fun collapsesWhisperRepetitionLoops() {
        val hallucinated =
            "back, then, when I would come back, then, when I would come back, then, when I would come " +
                "back, then, when I would come back, then, when I would come back and as he became a man " +
                "and I started working and I started working and I started working and I started working"
        val cleaned = TranscriptSanitizer.collapseRepetitionLoops(hallucinated)
        assertEquals(
            "back, then, when I would come back and as he became a man and I started working",
            cleaned,
        )
    }

    @Test
    fun filtersHighNoSpeechProbAndArtifacts() {
        assertEquals("", TranscriptSanitizer.cleanSegment("Thank you.", 0.52f))
        assertEquals("", TranscriptSanitizer.cleanSegment("[BLANK_AUDIO]", 0.05f))
        assertEquals("", TranscriptSanitizer.cleanSegment("Random silence hallucination", 0.78f))
        assertEquals("Thank you.", TranscriptSanitizer.cleanSegment("Thank you.", 0.10f))
    }

    @Test
    fun preservesNormalNaturalSpeechAndCodeSwitching() {
        val speech = "Hello bhai, aaj kal local AI models phone par bahut fast chal rahe hain."
        assertEquals(speech, TranscriptSanitizer.cleanSegment(speech, 0.08f))
    }

    @Test
    fun deduplicatesRepeatedConsecutiveSegments() {
        val segs = listOf(
            AsrSegment(0, 0.0, 2.0, "Hello everyone.", 0.05f),
            AsrSegment(1, 2.0, 4.0, "Hello everyone.", 0.05f),
            AsrSegment(2, 4.0, 6.0, "[MUSIC]", 0.10f),
            AsrSegment(3, 6.0, 8.0, "Welcome back.", 0.04f),
        )
        assertEquals("Hello everyone. Welcome back.", TranscriptSanitizer.cleanJoinedSegments(segs))
    }
}
