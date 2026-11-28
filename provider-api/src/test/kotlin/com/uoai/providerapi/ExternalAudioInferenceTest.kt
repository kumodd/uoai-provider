package com.uoai.providerapi

import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrProfile
import com.uoai.asr.DefaultAsrEngine
import com.uoai.asr.FileAsrModelRepository
import com.uoai.asr.FileAsrSettingsRepository
import com.uoai.asr.GgmlModelValidator
import com.uoai.asr.engines.AksharEngine
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.CactModelValidator
import com.uoai.asr.engines.ExistingWhisperEngine
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.ShrutiEngine
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.asr.runtime.CactusSpeechBackend
import com.uoai.asr.runtime.CactusTranscribeOutput
import com.uoai.asr.runtime.MockAsrRuntime
import com.uoai.clients.AuthenticatedClient
import com.uoai.clients.ClientPermissions
import com.uoai.clients.ClientRegistry
import com.uoai.inference.DefaultInferenceEngine
import com.uoai.inference.FileModelRepository
import com.uoai.inference.ModelManager
import com.uoai.inference.runtime.MockInferenceRuntime
import com.uoai.providerapi.external.ExternalAudioProviderClient
import com.uoai.providerapi.external.ExternalAudioProviderConfig
import com.uoai.providerapi.external.ExternalAudioProviderRegistry
import com.uoai.providerapi.external.ExternalAudioRequest
import com.uoai.providerapi.external.ExternalAudioResult
import com.uoai.providerapi.external.ExternalAudioSegment
import com.uoai.providerapi.external.ExternalProviderType
import com.uoai.providerapi.external.HttpExternalAudioProviderClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class ExternalAudioInferenceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }
    @get:Rule val tmp = TemporaryFolder()

    private var userPermissions = ClientPermissions(speechToText = true, streaming = true, asrModelControl = false)
    private var adminPermissions = ClientPermissions(speechToText = true, streaming = true, asrModelControl = true)

    private val registry = object : ClientRegistry {
        override suspend fun authenticate(bearerToken: String) = when (bearerToken) {
            "user-tok" -> AuthenticatedClient("client-user", userPermissions)
            "admin-tok" -> AuthenticatedClient("client-admin", adminPermissions)
            else -> null
        }
    }

    private class FakeCactusBackend : CactusSpeechBackend {
        override var loadedModelPath: String? = null
        override val isSpeechLoaded: Boolean get() = loadedModelPath != null
        override suspend fun loadModel(modelPath: String): Long {
            loadedModelPath = modelPath
            return 12L
        }
        override suspend fun reset() { loadedModelPath = null }
        override suspend fun transcribe(pcm16kMono: FloatArray, language: String?, keywords: String?) = CactusTranscribeOutput(
            text = "भाई कल meeting है, तुम presentation भेज देना।",
            language = language ?: "hi",
            decodeTps = 80.0,
            elapsedMs = 45L,
        )
    }

    private fun fakeGgml(name: String): File {
        val f = File(tmp.newFolder(), name)
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GgmlModelValidator.GGML_MAGIC_LE)
            putInt(51865); putInt(1500); putInt(512); putInt(8); putInt(6)
            putInt(448); putInt(512); putInt(8); putInt(6); putInt(80); putInt(1)
        }.array()
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(1_100_000L); raf.seek(0); raf.write(header)
        }
        return f
    }

    private fun fakeCact(name: String): File {
        val f = File(tmp.newFolder(), name)
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CactModelValidator.CACT_MAGIC_LE)
            putInt(1); putInt(0); putInt(0)
        }.array()
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(2_000_000L); raf.seek(0); raf.write(header)
        }
        return f
    }

    private fun voicedWav(samples: Int = 16_000): ByteArray {
        val dataSize = samples * 2
        val bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(16_000); bb.putInt(32_000); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        for (i in 0 until samples) {
            val s = (0.3 * sin(2.0 * PI * 440.0 * i / 16_000.0) * 32767.0).toInt().toShort()
            bb.putShort(s)
        }
        return bb.array()
    }

    private data class Env(
        val llmEngine: DefaultInferenceEngine,
        val asrManager: AsrModelManager,
        val asrEngine: DefaultAsrEngine,
        val asrSettings: FileAsrSettingsRepository,
        val speechRegistry: SpeechEngineRegistry,
        val hinglishMgr: HinglishModelManager,
        val extRegistry: ExternalAudioProviderRegistry,
        val tempStore: AsrTempFileStore,
    )

    private fun buildEnv(
        gemmaAudioCapable: Boolean = true,
        loadWhisper: Boolean = true,
        installHinglish: Boolean = true,
    ): Env {
        val llmRt = MockInferenceRuntime(tokenDelayMs = 2, audioCapable = gemmaAudioCapable)
        val llmMgr = ModelManager(FileModelRepository(tmp.newFolder()), llmRt)
        val asrRt = MockAsrRuntime(segmentDelayMs = 5L, segmentCount = 1)
        val asrRepo = FileAsrModelRepository(tmp.newFolder())
        val asrSettings = FileAsrSettingsRepository(File(tmp.newFolder(), "asr_settings.json"))
        val asrMgr = AsrModelManager(asrRepo, asrRt, asrSettings, scope, verifySha256OnDownload = false)
        val cactusBackend = FakeCactusBackend()
        val hinglishMgr = HinglishModelManager(tmp.newFolder(), cactusBackend, scope, verifySha256OnDownload = false)
        runBlocking {
            llmMgr.importModel("gemma-4-E2B-it-gpu.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
            llmMgr.load()
            if (loadWhisper) {
                asrMgr.installLocalFile(AsrProfile.BALANCED, fakeGgml("base.bin"))
                asrMgr.load(AsrProfile.BALANCED)
            }
            if (installHinglish) {
                hinglishMgr.installLocalFile(AsrEngineKind.AKSHAR_V4, fakeCact("akshar.cact"))
                hinglishMgr.installLocalFile(AsrEngineKind.SHRUTI_HI, fakeCact("shruti.cact"))
            }
        }
        val llmEngine = DefaultInferenceEngine(llmMgr, llmRt, scope)
        val asrEngine = DefaultAsrEngine(asrMgr, asrRt, asrSettings, scope)
        val speechReg = SpeechEngineRegistry(
            whisper = ExistingWhisperEngine(asrMgr, asrEngine),
            aksharV4 = AksharEngine(AsrEngineKind.AKSHAR_V4, hinglishMgr, cactusBackend),
            aksharLiteV4 = AksharEngine(AsrEngineKind.AKSHAR_LITE_V4, hinglishMgr, cactusBackend),
            shrutiHi = ShrutiEngine(hinglishMgr, cactusBackend),
        )
        val extRegistry = ExternalAudioProviderRegistry(File(tmp.newFolder(), "providers.json"))
        return Env(llmEngine, asrMgr, asrEngine, asrSettings, speechReg, hinglishMgr, extRegistry, AsrTempFileStore(tmp.newFolder()))
    }

    private fun testApp(
        env: Env,
        extClient: ExternalAudioProviderClient = HttpExternalAudioProviderClient(),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            providerApi(
                engine = env.llmEngine,
                registry = registry,
                asrEngine = env.asrEngine,
                asrModelManager = env.asrManager,
                asrSettingsProvider = { env.asrSettings.settings.value },
                asrTempStore = env.tempStore,
                speechEngineRegistry = env.speechRegistry,
                hinglishModelManager = env.hinglishMgr,
                externalProviderRegistry = env.extRegistry,
                externalProviderClient = extClient,
            )
        }
        block()
    }

    private fun HttpRequestBuilder.auth(tok: String = "user-tok") = header(HttpHeaders.Authorization, "Bearer $tok")

    @Test fun gemma4DirectAudioTranscriptionAndStreamingViaApi() {
        val env = buildEnv(gemmaAudioCapable = true, loadWhisper = true)
        testApp(env) {
            // 1. Explicit provider=gemma4 routes to Gemma 4 direct multimodal audio
            val r1 = client.post("/v1/audio/transcriptions") {
                auth()
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("provider", "gemma4")
                            append("model", "gemma-4")
                            append("language", "en,hi")
                            append("response_format", "verbose_json")
                            append("file", voicedWav(), Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"clip.wav\"")
                            })
                        },
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, r1.status)
            val body1 = r1.bodyAsText()
            assertTrue(body1.contains("\"provider\":\"gemma4\""))
            assertTrue(body1.contains("\"model\":\"gemma-4-e2b\""))
            assertTrue(body1.contains("[mock-audio"))

            // 2. Streaming SSE via model=gemma-4-e2b
            val r2 = client.post("/v1/audio/stream?model=gemma-4-e2b&language=hi") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.OK, r2.status)
            val sse = r2.bodyAsText()
            assertTrue(sse.contains("audio.transcription.start"))
            assertTrue(sse.contains("\"provider\":\"gemma4\""))
            assertTrue(sse.contains("audio.transcription.done"))
            assertTrue(sse.trim().endsWith("data: [DONE]"))

            // 3. Capabilities & /audio/providers advertise Gemma 4 audio as preferred
            val caps = client.get("/v1/capabilities") { auth() }.bodyAsText()
            assertTrue(caps.contains("\"gemma4-audio\""))
            assertTrue(caps.contains("\"external-inference\""))
            assertTrue(caps.contains("\"gemma4_audio_ready\":true"))

            val providers = client.get("/v1/audio/providers") { auth() }.bodyAsText()
            assertTrue(providers.contains("\"id\":\"gemma4\""))
            assertTrue(providers.contains("\"id\":\"whisper\""))
            assertTrue(providers.contains("\"id\":\"cactus\""))
        }
    }

    @Test fun textOnlyGemma4RejectsDirectAudioCleanlyAndAutoFallbackWorks() {
        val envTextOnly = buildEnv(gemmaAudioCapable = false, loadWhisper = true)
        testApp(envTextOnly) {
            val res = client.post("/v1/audio/transcriptions?provider=gemma4") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertTrue(res.bodyAsText().contains("gemma4_audio_unsupported"))
        }

        // When Whisper is NOT installed, provider=auto seamlessly falls back to Gemma 4 direct audio
        val envGemmaOnly = buildEnv(gemmaAudioCapable = true, loadWhisper = false)
        testApp(envGemmaOnly) {
            val res = client.post("/v1/audio/transcriptions") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.OK, res.status)
            assertTrue(res.bodyAsText().contains("\"provider\":\"gemma4\""))
        }
    }

    @Test fun cactusHinglishRoutingAndDualScriptFormattingViaApi() {
        val env = buildEnv(gemmaAudioCapable = true, loadWhisper = true, installHinglish = true)
        testApp(env) {
            val res = client.post("/v1/audio/transcriptions?model=akshar-v4&script=roman&language=hi") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.OK, res.status)
            val txt = res.bodyAsText()
            assertTrue(txt.contains("\"provider\":\"cactus\""))
            assertTrue(txt.contains("\"model\":\"akshar-v4\""))
            assertTrue(txt.contains("Bhai kal meeting hai, tum presentation bhej dena."))
            assertTrue(txt.contains("\"roman_hinglish\":\"Bhai kal meeting hai, tum presentation bhej dena.\""))
            assertTrue(txt.contains("\"devanagari_hinglish\":\"भाई कल meeting है, तुम presentation भेज देना।\""))
        }
    }

    @Test fun externalProviderCrudPermissionsSecretMaskingAndRouting() {
        val env = buildEnv(gemmaAudioCapable = true, loadWhisper = true)
        var capturedKey: String? = null
        var capturedModel: String? = null
        val fakeClient = object : ExternalAudioProviderClient {
            override suspend fun transcribe(
                config: ExternalAudioProviderConfig,
                apiKey: String?,
                request: ExternalAudioRequest,
            ): ExternalAudioResult {
                capturedKey = apiKey
                capturedModel = request.model
                return ExternalAudioResult(
                    text = "External transcript via ${config.id}/${request.model}",
                    model = request.model,
                    providerId = config.id,
                    language = request.language ?: "en",
                    durationSec = request.durationSec,
                    elapsedMs = 42L,
                    segments = listOf(ExternalAudioSegment(0, 0.0, request.durationSec, "External transcript via ${config.id}/${request.model}")),
                )
            }
        }

        testApp(env, extClient = fakeClient) {
            val regJson = """
                {
                  "id": "remote-gemma4",
                  "display_name": "Self-Hosted Gemma 4",
                  "type": "openai_compatible_chat_audio",
                  "base_url": "https://inference.example.com/v1",
                  "default_model": "gemma-4-remote-e4b",
                  "supported_models": ["gemma-4-remote-e4b", "whisper-large-v3"],
                  "api_key": "sk-top-secret-123"
                }
            """.trimIndent()

            // Non-admin client rejected with 403
            val forbidden = client.post("/v1/audio/providers") {
                auth("user-tok"); contentType(ContentType.Application.Json); setBody(regJson)
            }
            assertEquals(HttpStatusCode.Forbidden, forbidden.status)

            // Admin client registers successfully and response NEVER leaks api_key
            val created = client.post("/v1/audio/providers") {
                auth("admin-tok"); contentType(ContentType.Application.Json); setBody(regJson)
            }
            assertEquals(HttpStatusCode.OK, created.status)
            val createdBody = created.bodyAsText()
            assertTrue(createdBody.contains("\"id\":\"remote-gemma4\""))
            assertTrue(createdBody.contains("\"has_api_key\":true"))
            assertFalse(createdBody.contains("sk-top-secret-123"))

            // Route by explicit provider ID
            val r1 = client.post("/v1/audio/transcriptions?provider=remote-gemma4") {
                auth("user-tok"); contentType(ContentType.parse("audio/wav")); setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals("sk-top-secret-123", capturedKey)
            assertEquals("gemma-4-remote-e4b", capturedModel)
            assertTrue(r1.bodyAsText().contains("External transcript via remote-gemma4/gemma-4-remote-e4b"))

            // Route automatically by configured external model ID
            val r2 = client.post("/v1/audio/transcriptions?model=whisper-large-v3") {
                auth("user-tok"); contentType(ContentType.parse("audio/wav")); setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.OK, r2.status)
            assertEquals("whisper-large-v3", capturedModel)
            assertTrue(r2.bodyAsText().contains("\"provider\":\"remote-gemma4\""))

            // Built-in local provider cannot be deleted (400)
            val delBuiltIn = client.delete("/v1/audio/providers/gemma4") { auth("admin-tok") }
            assertEquals(HttpStatusCode.BadRequest, delBuiltIn.status)

            // Deleting external provider succeeds and removes it from routing
            val delOk = client.delete("/v1/audio/providers/remote-gemma4") { auth("admin-tok") }
            assertEquals(HttpStatusCode.OK, delOk.status)
            assertTrue(delOk.bodyAsText().contains("\"deleted\":true"))

            val afterDel = client.post("/v1/audio/transcriptions?provider=remote-gemma4") {
                auth("user-tok"); contentType(ContentType.parse("audio/wav")); setBody(voicedWav())
            }
            assertEquals(HttpStatusCode.NotFound, afterDel.status)
        }
    }

    @Test fun httpExternalClientSupportsAllThreeWireProtocols() = runBlocking {
        var lastAuthHeader: String? = null
        var lastGoogHeader: String? = null
        var sawInputAudio = false
        var sawInlineData = false

        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                post("/v1/audio/transcriptions") {
                    lastAuthHeader = call.request.headers["Authorization"]
                    call.receiveText()
                    call.respondText(
                        """{"text":"hello from openai whisper","language":"en","duration":1.0,"segments":[{"id":0,"start":0.0,"end":1.0,"text":"hello from openai whisper"}]}""",
                        ContentType.Application.Json,
                    )
                }
                post("/v1/chat/completions") {
                    lastAuthHeader = call.request.headers["Authorization"]
                    val bodyStr = call.receiveText()
                    sawInputAudio = bodyStr.contains("\"input_audio\"")
                    call.respondText(
                        """{"model":"gemma-4-remote","choices":[{"message":{"role":"assistant","content":"Bhai kal meeting hai."}}]}""",
                        ContentType.Application.Json,
                    )
                }
                post("/v1beta/models/gemini-2.5-flash:generateContent") {
                    lastGoogHeader = call.request.headers["x-goog-api-key"]
                    val bodyStr = call.receiveText()
                    sawInlineData = bodyStr.contains("\"inline_data\"")
                    call.respondText(
                        """{"candidates":[{"content":{"parts":[{"text":"Namaste from Gemini"}]}}]}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }
        server.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        try {
            val httpClient = HttpExternalAudioProviderClient()
            val wav = voicedWav(4000)

            // 1. OpenAI-compatible audio multipart
            val res1 = httpClient.transcribe(
                config = ExternalAudioProviderConfig(
                    id = "oai-audio",
                    displayName = "OpenAI Audio",
                    type = ExternalProviderType.OPENAI_COMPATIBLE_AUDIO,
                    baseUrl = "http://127.0.0.1:$port/v1",
                    defaultModel = "whisper-1",
                ),
                apiKey = "key-oai",
                request = ExternalAudioRequest("r1", wav, durationSec = 0.25, model = "whisper-1", language = "en"),
            )
            assertEquals("Bearer key-oai", lastAuthHeader)
            assertEquals("hello from openai whisper", res1.text)
            assertEquals(1, res1.segments.size)

            // 2. OpenAI-compatible multimodal chat (remote Gemma 4)
            val res2 = httpClient.transcribe(
                config = ExternalAudioProviderConfig(
                    id = "oai-chat",
                    displayName = "Remote Gemma 4",
                    type = ExternalProviderType.OPENAI_COMPATIBLE_CHAT_AUDIO,
                    baseUrl = "http://127.0.0.1:$port/v1",
                    defaultModel = "gemma-4-remote",
                ),
                apiKey = "key-chat",
                request = ExternalAudioRequest("r2", wav, durationSec = 0.25, model = "gemma-4-remote", language = "en,hi"),
            )
            assertTrue("Expected input_audio payload", sawInputAudio)
            assertEquals("Bearer key-chat", lastAuthHeader)
            assertEquals("Bhai kal meeting hai.", res2.text)

            // 3. Gemini / Gemma REST API
            val res3 = httpClient.transcribe(
                config = ExternalAudioProviderConfig(
                    id = "gem-api",
                    displayName = "Gemini API",
                    type = ExternalProviderType.GEMINI_MULTIMODAL,
                    baseUrl = "http://127.0.0.1:$port/v1beta",
                    defaultModel = "gemini-2.5-flash",
                ),
                apiKey = "key-goog",
                request = ExternalAudioRequest("r3", wav, durationSec = 0.25, model = "gemini-2.5-flash", language = "hi"),
            )
            assertTrue("Expected inline_data audio payload", sawInlineData)
            assertEquals("key-goog", lastGoogHeader)
            assertEquals("Namaste from Gemini", res3.text)
        } finally {
            server.stop(200, 1000)
        }
    }
}
