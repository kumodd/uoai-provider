package com.uoai.inference

import com.uoai.inference.runtime.GenerationParams
import com.uoai.inference.runtime.MockInferenceRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockInferenceRuntimeTest {
    @Test fun generatesOnlyWhenLoaded() = runTest {
        val rt = MockInferenceRuntime()
        rt.load("x")
        val out = rt.generate("hi there", GenerationParams()).toList().joinToString("")
        assertTrue(out.contains("hi there"))
        rt.unload()
        assertEquals(false, rt.isLoaded)
    }
}
