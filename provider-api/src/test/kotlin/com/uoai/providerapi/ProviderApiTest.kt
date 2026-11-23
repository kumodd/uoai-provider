package com.uoai.providerapi

import com.uoai.clients.AuthenticatedClient
import com.uoai.clients.ClientPermissions
import com.uoai.clients.ClientRegistry
import com.uoai.inference.*
import com.uoai.inference.runtime.MockInferenceRuntime
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class ProviderApiTest {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @org.junit.After fun tearDown() { scope.cancel() }
    @get:Rule val tmp = TemporaryFolder()
    private var permissions = ClientPermissions()
    private val registry = object : ClientRegistry {
        override suspend fun authenticate(bearerToken: String) =
            if (bearerToken == "secret") AuthenticatedClient("c1", permissions) else null
    }

    private fun ready(load: Boolean = true): InferenceEngine {
        val rt = MockInferenceRuntime()
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt)
        runBlocking {
            mgr.importModel("gemma3-1b-it-int4.task", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
            if (load) mgr.load()
        }
        return DefaultInferenceEngine(mgr, rt, scope)
    }

    private fun app(engine: InferenceEngine, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { providerApi(engine, registry, ApiConfig(maxRequestBytes = 2048)) }
        block()
    }

    private val chatBody = """{"model":"gemma-3","messages":[{"role":"user","content":"hello"}]}"""
    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer secret")
    private fun HttpRequestBuilder.json(b: String) { contentType(ContentType.Application.Json); setBody(b) }

    @Test fun healthReflectsRealState() {
        app(ready()) { assertTrue(client.get("/v1/health").bodyAsText().contains("\"model_state\":\"READY\"")) }
        app(ready(load = false)) { assertTrue(client.get("/v1/health").bodyAsText().contains("\"status\":\"degraded\"")) }
    }

    @Test fun rejectsMissingAndWrongToken() = app(ready()) {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/models").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/chat/completions") { json(chatBody); header(HttpHeaders.Authorization, "Bearer nope") }.status)
    }

    @Test fun nonStreamingChat() = app(ready()) {
        val r = client.post("/v1/chat/completions") { auth(); json(chatBody) }
        assertEquals(HttpStatusCode.OK, r.status)
        assertNotNull(r.headers["X-Request-Id"])
        assertTrue(r.bodyAsText().contains("\"object\":\"chat.completion\""))
    }

    @Test fun streamingChatEndsWithDone() = app(ready()) {
        val r = client.post("/v1/chat/completions") { auth(); json(chatBody.replace("}]}", "}],\"stream\":true}")) }
        val t = r.bodyAsText()
        assertTrue(r.contentType()!!.match(ContentType.Text.EventStream))
        assertTrue(t.contains("chat.completion.chunk"))
        assertTrue(t.trim().endsWith("data: [DONE]"))
    }

    @Test fun modelNotReadyIs503() = app(ready(load = false)) {
        val r = client.post("/v1/chat/completions") { auth(); json(chatBody) }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("model_not_ready"))
    }

    @Test fun validation() = app(ready()) {
        suspend fun code(b: String) = client.post("/v1/chat/completions") { auth(); json(b) }.status
        assertEquals(HttpStatusCode.BadRequest, code("{not json"))
        assertEquals(HttpStatusCode.BadRequest, code("""{"messages":[]}"""))
        assertEquals(HttpStatusCode.BadRequest, code("""{"messages":[{"role":"user","content":"x"}],"temperature":9}"""))
        assertEquals(HttpStatusCode.NotFound, code("""{"model":"gpt-4","messages":[{"role":"user","content":"x"}]}"""))
        assertEquals(HttpStatusCode.PayloadTooLarge, code("""{"messages":[{"role":"user","content":"${"a".repeat(4000)}"}]}"""))
    }

    @Test fun cancelUnknownIdReturnsFalse() = app(ready()) {
        val r = client.post("/v1/cancel") { auth(); json("""{"request_id":"nope"}""") }
        assertTrue(r.bodyAsText().contains("\"cancelled\":false"))
    }

    @Test fun modelsAndCapabilities() = app(ready()) {
        assertTrue(client.get("/v1/models") { auth() }.bodyAsText().contains("\"id\":\"gemma-3\""))
        assertTrue(client.get("/v1/capabilities") { auth() }.bodyAsText().contains("\"embeddings\":false"))
    }

    @Test fun permissionsAreEnforced() {
        permissions = ClientPermissions(chat = true, streaming = false)
        app(ready()) {
            val r = client.post("/v1/chat/completions") { auth(); json(chatBody.replace("}]}", "}],\"stream\":true}")) }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }
        permissions = ClientPermissions(chat = false)
        app(ready()) { assertEquals(HttpStatusCode.Forbidden, client.post("/v1/chat/completions") { auth(); json(chatBody) }.status) }
        permissions = ClientPermissions()
    }

    @Test fun clientToolCallRequiresPermissionAndParsesToolCalls() {
        val toolBody = """{"model":"gemma-3","messages":[{"role":"user","content":"call <tool_call>{\"name\":\"read_doc\",\"arguments\":{\"id\":\"42\"}}</tool_call>"}],"tools":[{"type":"function","function":{"name":"read_doc","description":"Read local doc"}}]}"""
        // Without toolCalling permission -> 403
        permissions = ClientPermissions(chat = true, toolCalling = false)
        app(ready()) {
            assertEquals(HttpStatusCode.Forbidden, client.post("/v1/chat/completions") { auth(); json(toolBody) }.status)
        }
        // With toolCalling permission -> parses tool_calls from output
        permissions = ClientPermissions(chat = true, toolCalling = true)
        app(ready()) {
            val r = client.post("/v1/chat/completions") { auth(); json(toolBody) }
            assertEquals(HttpStatusCode.OK, r.status)
            val txt = r.bodyAsText()
            assertTrue(txt.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(txt.contains("\"name\":\"read_doc\""))
        }
        permissions = ClientPermissions()
    }

    @Test fun supports32kCapabilitiesAndRejectsAbove32k() {
        permissions = ClientPermissions(chat = true, maxTokens = 32_768)
        app(ready()) {
            val caps = client.get("/v1/capabilities") { auth() }.bodyAsText()
            assertTrue(caps.contains("\"max_tokens_limit\":32768"))
            assertTrue(caps.contains("\"context_window_tokens\":4096"))

            val models = client.get("/v1/models") { auth() }.bodyAsText()
            assertTrue(models.contains("\"context_window\":4096"))

            val ok32k = client.post("/v1/chat/completions") {
                auth()
                json("""{"model":"gemma-3","messages":[{"role":"user","content":"hello"}],"max_tokens":32768}""")
            }
            assertEquals(HttpStatusCode.OK, ok32k.status)

            val overLimit = client.post("/v1/chat/completions") {
                auth()
                json("""{"model":"gemma-3","messages":[{"role":"user","content":"hello"}],"max_tokens":40000}""")
            }
            assertEquals(HttpStatusCode.BadRequest, overLimit.status)
        }
        permissions = ClientPermissions()
    }

    @Test fun gemma4DynamicModelRoutingAndAliases() {
        val rt = MockInferenceRuntime()
        val mgr = ModelManager(FileModelRepository(tmp.newFolder()), rt)
        runBlocking {
            mgr.importModel("gemma-4-E2B-it.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
            mgr.load()
        }
        val engine = DefaultInferenceEngine(mgr, rt, scope)
        app(engine) {
            val models = client.get("/v1/models") { auth() }.bodyAsText()
            assertTrue(models.contains("\"id\":\"gemma-4-e2b\""))

            for (alias in listOf("gemma-4-e2b", "gemma-4", "auto")) {
                val r = client.post("/v1/chat/completions") {
                    auth()
                    json("""{"model":"$alias","messages":[{"role":"user","content":"hi"}]}""")
                }
                assertEquals(HttpStatusCode.OK, r.status)
                assertTrue(r.bodyAsText().contains("\"model\":\"gemma-4-e2b\""))
            }

            val unknown = client.post("/v1/chat/completions") {
                auth()
                json("""{"model":"gpt-4","messages":[{"role":"user","content":"hi"}]}""")
            }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }
    }
}
