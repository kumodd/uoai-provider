package com.uoai.inference

import com.uoai.inference.runtime.GenerationParams
import com.uoai.inference.runtime.MockInferenceRuntime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(): Triple<ModelManager, MockInferenceRuntime, DefaultInferenceEngine> {
        val rt = MockInferenceRuntime(tokenDelayMs = 10)
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt)
        return Triple(mgr, rt, DefaultInferenceEngine(mgr, rt, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)))
    }

    private fun bigStream() = ByteArrayInputStream(ByteArray(11 * 1024 * 1024))
    private fun req(id: String = "r1") =
        InferenceRequest(id, "c", "gemma-3", listOf(ChatMessage("user", "hello world")), GenerationParams())

    @Test fun rejectsTinyFile() = runTest {
        val (m, _, _) = fixture()
        m.importModel("x.task", ByteArrayInputStream(ByteArray(10)))
        assertEquals(ModelState.ERROR, m.status.value.state)
        assertFalse(m.isInstalled)
    }

    @Test fun importLoadUnloadLifecycle() = runTest {
        val (m, rt, _) = fixture()
        m.importModel("gemma3-1b-it-int4.task", bigStream())
        assertTrue(m.isInstalled)
        assertEquals("int4", m.installedInfo()?.quantization)
        m.load(); assertEquals(ModelState.READY, m.status.value.state)
        m.load(); assertTrue(rt.isLoaded) // second load is a no-op
        m.unload(); assertFalse(rt.isLoaded)
    }

    @Test fun loadWithoutModelIsError() = runTest {
        val (m, _, _) = fixture()
        m.load()
        assertEquals(ModelState.ERROR, m.status.value.state)
    }

    @Test fun engineRefusesWhenNotReady() = runTest {
        val (_, _, e) = fixture()
        try { e.generate(req()); fail() } catch (ex: ModelNotReadyException) { }
    }

    @Test fun engineGenerates() = runBlocking {
        val (m, _, e) = fixture()
        m.importModel("a.task", bigStream()); m.load()
        assertTrue(e.generate(req()).contains("hello world"))
    }
}
