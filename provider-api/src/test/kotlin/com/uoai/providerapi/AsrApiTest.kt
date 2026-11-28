package com.uoai.providerapi

import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrProfile
import com.uoai.asr.DefaultAsrEngine
import com.uoai.asr.FileAsrModelRepository
import com.uoai.asr.FileAsrSettingsRepository
import com.uoai.asr.GgmlModelValidator
import com.uoai.asr.runtime.MockAsrRuntime
import com.uoai.clients.AuthenticatedClient
import com.uoai.clients.ClientPermissions
import com.uoai.clients.ClientRegistry
import com.uoai.inference.DefaultInferenceEngine
import com.uoai.inference.FileModelRepository
import com.uoai.inference.ModelManager
import com.uoai.inference.runtime.MockInferenceRuntime
import io.ktor.client.request.*
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.*
import io.ktor.http.*
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

class AsrApiTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }
    @get:Rule val tmp = TemporaryFolder()

    private var c1Permissions = ClientPermissions(speechToText = true, asrModelControl = false)
    private var c2Permissions = ClientPermissions(speechToText = true, asrModelControl = true)

    private val registry = object : ClientRegistry {
        override suspend fun authenticate(bearerToken: String) = when (bearerToken) {
            "token1" -> AuthenticatedClient("client-1", c1Permissions)
            "token2" -> AuthenticatedClient("client-2", c2Permissions)
            else -> null
        }
    }

    private fun fakeGgml(name: String): File {
        val f = tmp.newFile(name)
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GgmlModelValidator.GGML_MAGIC_LE)
            putInt(51865); putInt(1500); putInt(512); putInt(8); putInt(6)
            putInt(448); putInt(512); putInt(8); putInt(6); putInt(80); putInt(1)
        }.array()
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(1_100_000L)
            raf.seek(0)
            raf.write(header)
        }
        return f
    }

    private fun sampleWavBytes(durationSamples: Int = 8000): ByteArray {
        val dataSize = durationSamples * 2
        val bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(1) // mono
        bb.putInt(16_000)
        bb.putInt(32_000)
        bb.putShort(2)
        bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize)
        return bb.array()
    }

    private data class TestEnv(
        val llmEngine: DefaultInferenceEngine,
        val asrManager: AsrModelManager,
        val asrEngine: DefaultAsrEngine,
        val asrSettings: FileAsrSettingsRepository,
        val tempDir: File,
        val tempStore: AsrTempFileStore,
    )

    private fun createEnv(loadAsr: Boolean = true): TestEnv {
        val llmRt = MockInferenceRuntime()
        val llmMgr = ModelManager(FileModelRepository(tmp.newFolder()), llmRt)
        val asrRt = MockAsrRuntime(segmentDelayMs = 10L, segmentCount = 2)
        val asrRepo = FileAsrModelRepository(tmp.newFolder())
        val asrSettings = FileAsrSettingsRepository(File(tmp.newFolder(), "asr_settings.json"))
        val asrMgr = AsrModelManager(asrRepo, asrRt, asrSettings, scope, verifySha256OnDownload = false)
        runBlocking {
            llmMgr.importModel("gemma-4-E2B-it.litertlm", ByteArrayInputStream(ByteArray(11 * 1024 * 1024)))
            llmMgr.load()
            asrMgr.installLocalFile(AsrProfile.FAST, fakeGgml("tiny.bin"))
            asrMgr.installLocalFile(AsrProfile.BALANCED, fakeGgml("base.bin"))
            if (loadAsr) asrMgr.load(AsrProfile.BALANCED)
        }
        val llmEngine = DefaultInferenceEngine(llmMgr, llmRt, scope)
        val asrEngine = DefaultAsrEngine(asrMgr, asrRt, asrSettings, scope)
        val tempDir = tmp.newFolder("asr_uploads")
        return TestEnv(llmEngine, asrMgr, asrEngine, asrSettings, tempDir, AsrTempFileStore(tempDir))
    }

    private fun app(
        env: TestEnv,
        maxAudioBytes: Long = 1024 * 1024,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            providerApi(
                engine = env.llmEngine,
                registry = registry,
                config = ApiConfig(maxAudioUploadBytes = maxAudioBytes),
                asrEngine = env.asrEngine,
                asrModelManager = env.asrManager,
                asrSettingsProvider = { env.asrSettings.settings.value },
                asrTempStore = env.tempStore,
            )
        }
        block()
    }

    private fun HttpRequestBuilder.auth(token: String = "token1") = header(HttpHeaders.Authorization, "Bearer $token")

    @Test fun audioModelsStatusAndCapabilities() {
        val env = createEnv(loadAsr = true)
        app(env) {
            // Unauthenticated rejected
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/audio/models").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/audio/status").status)

            val models = client.get("/v1/audio/models") { auth() }
            assertEquals(HttpStatusCode.OK, models.status)
            val mBody = models.bodyAsText()
            assertTrue(mBody.contains("\"whisper-tiny-q5\""))
            assertTrue(mBody.contains("\"whisper-base-q5\""))
            assertTrue(mBody.contains("\"whisper-small-q5\""))
            assertTrue(mBody.contains("\"active_profile\":\"balanced\""))

            val status = client.get("/v1/audio/status") { auth() }.bodyAsText()
            assertTrue(status.contains("\"state\":\"READY\""))
            assertTrue(status.contains("\"model_loaded\":true"))

            val caps = client.get("/v1/capabilities") { auth() }.bodyAsText()
            assertTrue(caps.contains("\"asr\""))
            assertTrue(caps.contains("\"whisper-base-q5\""))
        }
    }

    @Test fun multipartTranscriptionsEnglishHindiVerboseAndTextAndCleansTempFiles() {
        val env = createEnv(loadAsr = true)
        val wav = sampleWavBytes()
        app(env) {
            // 1. Default JSON + OpenAI "whisper-1" alias
            val r1 = client.post("/v1/audio/transcriptions") {
                auth()
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("model", "whisper-1")
                            append("language", "en")
                            append("file", wav, Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"clip.wav\"")
                            })
                        },
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertTrue(r1.headers["X-Request-Id"]!!.startsWith("asr-"))
            assertTrue(r1.bodyAsText().contains("hello offline world"))

            // 2. Verbose JSON + Hindi UTF-8 + timestamps
            val r2 = client.post("/v1/audio/transcriptions") {
                auth()
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("model", "auto")
                            append("language", "hi")
                            append("response_format", "verbose_json")
                            append("file", wav, Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"hindi.wav\"")
                            })
                        },
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, r2.status)
            val b2 = r2.bodyAsText()
            assertTrue(b2.contains("\"language\":\"hi\""))
            assertTrue(b2.contains("नमस्ते"))
            assertTrue(b2.contains("\"segments\""))

            // 3. Plain text raw WAV fast path
            val r3 = client.post("/v1/audio/transcriptions?response_format=text&language=en") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(wav)
            }
            assertEquals(HttpStatusCode.OK, r3.status)
            assertTrue(r3.bodyAsText().contains("hello offline world"))

            // Zero temp upload files leaked
            assertEquals(0, env.tempDir.listFiles()?.size ?: 0)
        }
    }

    @Test fun streamingSseEmitsStartSegmentsDone() {
        val env = createEnv(loadAsr = true)
        app(env) {
            val r = client.post("/v1/audio/stream?language=en") {
                auth()
                contentType(ContentType.parse("audio/wav"))
                setBody(sampleWavBytes())
            }
            assertEquals(HttpStatusCode.OK, r.status)
            assertTrue(r.contentType()!!.match(ContentType.Text.EventStream))
            val text = r.bodyAsText()
            assertTrue(text.contains("audio.transcription.start"))
            assertTrue(text.contains("audio.transcription.segment"))
            assertTrue(text.contains("audio.transcription.done"))
            assertTrue(text.trim().endsWith("data: [DONE]"))
        }
    }

    @Test fun enforcesPermissionsUploadSizeAndModelControl() {
        val env = createEnv(loadAsr = true)
        app(env, maxAudioBytes = 1024) {
            // 413 when audio exceeds maxAudioUploadBytes + temp file cleaned up
            val big = client.post("/v1/audio/transcriptions") {
                auth("token1")
                contentType(ContentType.parse("audio/wav"))
                setBody(sampleWavBytes(4000)) // ~8 KB > 1 KB limit
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
            assertTrue(big.bodyAsText().contains("audio_too_large"))
            assertEquals(0, env.tempDir.listFiles()?.size ?: 0)

            // 403 when speechToText permission disabled
            c1Permissions = ClientPermissions(speechToText = false, asrModelControl = false)
            val denied = client.post("/v1/audio/transcriptions") {
                auth("token1")
                contentType(ContentType.parse("audio/wav"))
                setBody(ByteArray(100))
            }
            assertEquals(HttpStatusCode.Forbidden, denied.status)
            c1Permissions = ClientPermissions(speechToText = true, asrModelControl = false)

            // 403 on load/unload for client without asrModelControl
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/v1/audio/models/unload") { auth("token1"); contentType(ContentType.Application.Json); setBody("{}") }.status,
            )

            // 200 on unload & load for privileged client2
            val unloadResp = client.post("/v1/audio/models/unload") {
                auth("token2")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, unloadResp.status)
            assertTrue(unloadResp.bodyAsText().contains("\"unloaded\":true"))

            val loadFast = client.post("/v1/audio/models/load") {
                auth("token2")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"fast"}""")
            }
            assertEquals(HttpStatusCode.OK, loadFast.status)
            assertTrue(loadFast.bodyAsText().contains("\"model_id\":\"whisper-tiny-q5\""))
        }
    }

    @Test fun llmAndAsrCoexistOnSameServerAndPreserveBackwardCompatibility() {
        val env = createEnv(loadAsr = true)
        app(env) {
            val llmResp = client.post("/v1/chat/completions") {
                auth("token1")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"auto","messages":[{"role":"user","content":"ping"}]}""")
            }
            assertEquals(HttpStatusCode.OK, llmResp.status)
            assertTrue(llmResp.bodyAsText().contains("\"object\":\"chat.completion\""))

            val asrResp = client.post("/v1/audio/transcriptions") {
                auth("token2")
                contentType(ContentType.parse("audio/wav"))
                setBody(sampleWavBytes())
            }
            assertEquals(HttpStatusCode.OK, asrResp.status)
            assertTrue(asrResp.bodyAsText().contains("hello offline world"))

            // Model mismatch returns 409
            val mismatch = client.post("/v1/audio/transcriptions?model=fast") {
                auth("token1")
                contentType(ContentType.parse("audio/wav"))
                setBody(sampleWavBytes())
            }
            assertEquals(HttpStatusCode.Conflict, mismatch.status)
            assertTrue(mismatch.bodyAsText().contains("model_mismatch"))
        }
    }
}

