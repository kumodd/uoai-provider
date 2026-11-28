package com.uoai.inference

import com.uoai.inference.runtime.GenerationParams
import com.uoai.inference.runtime.MockInferenceRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.Collections

class SchedulerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private val started = Collections.synchronizedList(mutableListOf<String>())
    private val records = Collections.synchronizedList(mutableListOf<RequestRecord>())

    private fun engine(delayMs: Long = 40, limits: QueueLimits = QueueLimits()): DefaultInferenceEngine {
        val rt = MockInferenceRuntime(tokenDelayMs = delayMs)
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt)
        runBlocking {
            mgr.importModel("gemma-4-E2B-it.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
            mgr.load()
        }
        return DefaultInferenceEngine(mgr, rt, scope, { r, _ -> records.add(r); if (r.status == RequestStatus.RUNNING) started.add(r.requestId) }, limits)
    }

    private fun req(id: String, client: String = "c", prio: Int = 0, text: String = "a b c d e") =
        InferenceRequest(id, client, "gemma-4-e2b", listOf(ChatMessage("user", text)), GenerationParams(), prio)

    @Test fun runsOneAtATimeInPriorityOrder() = runBlocking {
        val e = engine()
        val first = async { e.generate(req("first")) }
        delay(80) // "first" is running; the rest queue behind it
        val low = async { e.generate(req("low", prio = 0)) }
        delay(10)
        val high = async { e.generate(req("high", client = "other", prio = 5)) }
        listOf(first, low, high).awaitAll()
        assertEquals(listOf("first", "high", "low"), started.toList())
    }

    @Test fun twoClientsShareOneEngine() = runBlocking {
        val e = engine(delayMs = 5)
        val a = async { e.generate(req("a", "clientA")) }
        val b = async { e.generate(req("b", "clientB")) }
        assertTrue(a.await().contains("a b c")); assertTrue(b.await().contains("a b c"))
        assertEquals(setOf("clientA", "clientB"), records.map { it.clientId }.toSet())
    }

    @Test fun cancelQueuedRequestDoesNotAffectOthers() = runBlocking {
        val e = engine()
        val running = async { e.generate(req("run")) }
        delay(80)
        val queued = async { runCatching { e.generate(req("q")) } }
        delay(30)
        assertTrue(e.cancel("q"))
        assertTrue(queued.await().exceptionOrNull() is RequestCancelledException)
        assertTrue(running.await().contains("a b c"))
        assertTrue(records.any { it.requestId == "q" && it.status == RequestStatus.CANCELLED })
    }

    @Test fun cancelRunningRequestLetsNextProceed() = runBlocking {
        val e = engine(delayMs = 100)
        val a = async { runCatching { e.generate(req("a", text = "w ".repeat(40))) } }
        delay(150)
        val b = async { e.generate(req("b", text = "x y")) }
        delay(50)
        assertTrue(e.cancel("a"))
        assertTrue(a.await().exceptionOrNull() is RequestCancelledException)
        assertTrue(b.await().contains("x y"))
        assertEquals(ModelState.READY, e.modelStatus.value.state)
    }

    @Test fun collectorCancellationCancelsRequest() = runBlocking {
        val e = engine(delayMs = 100)
        val job = launch { e.stream(req("gone", text = "w ".repeat(40))).toList() }
        delay(200)
        job.cancelAndJoin()
        delay(300)
        assertTrue(records.any { it.requestId == "gone" && it.status == RequestStatus.CANCELLED })
        assertEquals(0, e.queueState.value.queued)
    }

    @Test fun queueLimitsAreEnforced() = runBlocking {
        val e = engine(delayMs = 100, limits = QueueLimits(maxQueued = 2, maxPendingPerClient = 2))
        val jobs = (1..2).map { i -> launch { runCatching { e.generate(req("p$i", "same")) } } }
        delay(50)
        try { e.generate(req("p3", "same")); fail("expected QueueFullException") } catch (_: QueueFullException) {}
        jobs.forEach { e.cancel("p${jobs.indexOf(it) + 1}") }
        jobs.joinAll()
    }

    @Test fun audioRequestRoutesToAudioCapableRuntime() = runBlocking {
        val rt = MockInferenceRuntime(tokenDelayMs = 5, audioCapable = true)
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt)
        mgr.importModel("gemma-4-E2B-it.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
        mgr.load()
        assertTrue(mgr.status.value.supportsAudioInput)

        val e = DefaultInferenceEngine(mgr, rt, scope)
        val wav = ByteArray(128) { 1 }
        val out = e.generate(
            InferenceRequest(
                requestId = "aud-1",
                clientId = "dashboard",
                modelId = "gemma-4-e2b",
                messages = listOf(ChatMessage("user", "Transcribe this")),
                audioWavBytes = wav,
            ),
        )
        assertTrue(out.contains("[mock-audio 128B]"))
        assertTrue(out.contains("Transcribe"))
    }

    @Test fun audioRequestFailsCleanlyOnTextOnlyRuntime() = runBlocking {
        val e = engine(delayMs = 5)
        assertFalse(e.modelStatus.value.supportsAudioInput)
        val err = runCatching {
            e.generate(
                InferenceRequest(
                    requestId = "aud-fail",
                    clientId = "dashboard",
                    modelId = "gemma-4-12b",
                    messages = listOf(ChatMessage("user", "Transcribe")),
                    audioWavBytes = ByteArray(64),
                ),
            )
        }.exceptionOrNull()
        assertTrue(err is UnsupportedOperationException)
        // Subsequent text requests still succeed normally
        assertTrue(e.generate(req("after-fail", text = "still ok")).contains("still ok"))
    }
}
