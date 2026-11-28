package com.uoai.providerapi

import com.uoai.asr.AsrEngine
import com.uoai.asr.AsrModelCatalog
import com.uoai.asr.AsrModelManager
import com.uoai.asr.AsrModelNotReadyException
import com.uoai.asr.AsrRequest
import com.uoai.asr.AsrRequestCancelledException
import com.uoai.asr.AsrSettings
import com.uoai.asr.AsrStreamEvent
import com.uoai.asr.audio.AudioDecoder
import com.uoai.asr.audio.DecodedAudio
import com.uoai.asr.audio.WavPcmDecoder
import com.uoai.asr.engines.AsrEngineKind
import com.uoai.asr.engines.HinglishModelCatalog
import com.uoai.asr.engines.HinglishModelManager
import com.uoai.asr.engines.HinglishScriptConverter
import com.uoai.asr.engines.SpeechEngineRegistry
import com.uoai.clients.ClientRegistry
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceSettings
import com.uoai.inference.ModelNotReadyException
import com.uoai.inference.RequestCancelledException
import com.uoai.providerapi.dto.*
import com.uoai.providerapi.external.ExternalAudioProviderClient
import com.uoai.providerapi.external.ExternalAudioProviderConfig
import com.uoai.providerapi.external.ExternalAudioProviderRegistry
import com.uoai.providerapi.external.ExternalAudioRequest
import com.uoai.providerapi.external.ExternalProviderType
import com.uoai.providerapi.external.Gemma4AudioTranscriber
import com.uoai.providerapi.external.HttpExternalAudioProviderClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

typealias AsrAuditLogger = suspend (clientId: String, action: String, resource: String?, status: String, details: String?) -> Unit

private data class ParsedAudioUpload(
    val tempFile: File,
    val bytes: Long,
    val contentType: String?,
    val fileName: String?,
    val model: String?,
    val provider: String?,
    val language: String?,
    val prompt: String?,
    val script: String?,
    val responseFormat: String,
    val temperature: Float,
    val translate: Boolean,
    val timestamps: Boolean,
    val pcmSampleRate: Int,
    val pcmChannels: Int,
)

private sealed interface ResolvedAudioTarget {
    data class LocalWhisper(val requestedModel: String?) : ResolvedAudioTarget
    data object LocalGemma4 : ResolvedAudioTarget
    data class LocalCactus(val kind: AsrEngineKind) : ResolvedAudioTarget
    data class External(val config: ExternalAudioProviderConfig, val model: String) : ResolvedAudioTarget
}

private data class FormattedScriptText(
    val displayText: String,
    val romanHinglish: String?,
    val devanagariHinglish: String?,
)

/**
 * Registers shared multi-app audio & external inference endpoints under `/v1/audio/...`.
 * Preserves 100% backward compatibility for existing Whisper clients while adding direct
 * multimodal Gemma 4 transcription, Hinglish Cactus engines (AksharSTT v4 / Lite / Shruti),
 * and configurable external inference providers.
 */
internal fun Route.asrRoutes(
    asrEngine: AsrEngine,
    asrModelManager: AsrModelManager,
    inferenceEngine: InferenceEngine,
    inferenceSettingsProvider: () -> InferenceSettings,
    registry: ClientRegistry,
    config: ApiConfig,
    owners: ConcurrentHashMap<String, String>,
    audioDecoder: AudioDecoder,
    tempStore: AsrTempFileStore,
    asrSettingsProvider: () -> AsrSettings,
    auditLogger: AsrAuditLogger?,
    speechEngineRegistry: SpeechEngineRegistry? = null,
    hinglishModelManager: HinglishModelManager? = null,
    externalProviderRegistry: ExternalAudioProviderRegistry = ExternalAudioProviderRegistry(),
    externalProviderClient: ExternalAudioProviderClient = HttpExternalAudioProviderClient(),
) {
    // Bound simultaneous audio file decodes so burst uploads do not spike JVM heap.
    val decodePermits = Semaphore(permits = 4)

    route("/audio") {
        get("/models") {
            call.authenticateClient(registry)
            val status = asrModelManager.status.value
            val models = buildList {
                // 1. Existing Whisper models (preserved identically for backward compatibility)
                status.profileSummaries().forEach { s ->
                    add(
                        AudioModelDto(
                            id = s.preset.id,
                            profile = s.preset.profile.apiId,
                            name = s.preset.displayName,
                            fileName = s.preset.fileName,
                            format = s.preset.format,
                            quantization = s.preset.quantization,
                            multilingual = s.preset.multilingual,
                            sizeBytes = s.installedSizeBytes ?: s.preset.exactSizeBytes,
                            approxRamMb = s.preset.approxRamMb,
                            installed = s.installed,
                            selected = s.selected,
                            loaded = s.loaded,
                            recommended = s.preset.recommended,
                            provider = "whisper",
                            engineType = "local_asr",
                        ),
                    )
                }

                // 2. Direct Gemma 4 multimodal audio transcription (preferred when audio-capable Gemma 4 is loaded)
                val llmSt = inferenceEngine.modelStatus.value
                val llmModel = llmSt.model
                val gemma4Ready = Gemma4AudioTranscriber.isReady(inferenceEngine)
                if (llmModel != null) {
                    add(
                        AudioModelDto(
                            id = llmModel.id,
                            profile = "gemma4-direct",
                            name = "${llmModel.name} (Direct Multimodal Audio)",
                            fileName = "${llmModel.name}.${llmModel.format ?: "litertlm"}",
                            format = llmModel.format ?: "litertlm",
                            quantization = llmModel.quantization ?: "QAT-INT4",
                            multilingual = true,
                            sizeBytes = llmModel.fileSizeBytes ?: 0L,
                            approxRamMb = 2800,
                            installed = true,
                            selected = gemma4Ready,
                            loaded = gemma4Ready,
                            recommended = gemma4Ready,
                            provider = "gemma4",
                            engineType = "local_multimodal_llm",
                        ),
                    )
                }

                // 3. Local Hinglish Cactus engines (AksharSTT v4, AksharSTT Lite v4, Shruti)
                val hSt = hinglishModelManager?.status?.value
                if (hSt != null) {
                    HinglishModelCatalog.ALL_SPECS.forEach { spec ->
                        val installed = hSt.isInstalled(spec.kind)
                        val loaded = hSt.activeLoadedKind == spec.kind
                        add(
                            AudioModelDto(
                                id = spec.kind.apiId,
                                profile = spec.kind.apiId,
                                name = spec.kind.displayName,
                                fileName = spec.fileName,
                                format = "cact",
                                quantization = spec.quantization,
                                multilingual = false,
                                sizeBytes = hSt.installedSizes[spec.kind] ?: spec.exactSizeBytes,
                                approxRamMb = spec.approxRamMb,
                                installed = installed,
                                selected = loaded,
                                loaded = loaded,
                                recommended = false,
                                provider = "cactus",
                                engineType = "local_hinglish_asr",
                            ),
                        )
                    }
                }

                // 4. Configured external inference providers
                externalProviderRegistry.list().filter { it.enabled }.forEach { ext ->
                    ext.allModels().forEach { modelId ->
                        add(
                            AudioModelDto(
                                id = modelId,
                                profile = ext.id,
                                ownedBy = ext.id,
                                name = "${ext.displayName} · $modelId",
                                fileName = ext.baseUrl,
                                format = ext.type.apiId,
                                quantization = "remote",
                                multilingual = true,
                                sizeBytes = 0L,
                                approxRamMb = 0,
                                installed = true,
                                selected = modelId == ext.defaultModel,
                                loaded = true,
                                recommended = false,
                                provider = ext.id,
                                engineType = ext.type.apiId,
                            ),
                        )
                    }
                }
            }
            call.respond(
                AudioModelsResponse(
                    activeProfile = status.selectedProfile.apiId,
                    preferredProvider = if (Gemma4AudioTranscriber.isReady(inferenceEngine)) "gemma4" else "whisper",
                    data = models,
                ),
            )
        }

        get("/providers") {
            call.authenticateClient(registry)
            val gemma4Ready = Gemma4AudioTranscriber.isReady(inferenceEngine)
            val llmModel = inferenceEngine.modelStatus.value.model
            val whisperSt = asrModelManager.status.value
            val hinglishSt = hinglishModelManager?.status?.value

            val list = buildList {
                add(
                    AudioProviderDto(
                        id = "gemma4",
                        name = "Gemma 4 Direct Multimodal (On-Device)",
                        kind = "local_multimodal_llm",
                        builtin = true,
                        enabled = true,
                        ready = gemma4Ready,
                        recommended = true,
                        defaultModel = llmModel?.id ?: "gemma-4",
                        models = listOfNotNull(llmModel?.id, "gemma-4", "gemma-4-audio").distinct(),
                        supportsStreaming = true,
                        note = if (gemma4Ready) {
                            "Recommended: direct multimodal audio-to-text with dialect & Hinglish awareness."
                        } else {
                            "Load a multimodal Gemma 4 E2B or E4B (.litertlm) model in UOAI Provider to enable."
                        },
                    ),
                )
                add(
                    AudioProviderDto(
                        id = "whisper",
                        name = "Whisper (whisper.cpp On-Device)",
                        kind = "local_asr",
                        builtin = true,
                        enabled = true,
                        ready = whisperSt.isLoaded || whisperSt.isSelectedInstalled,
                        recommended = !gemma4Ready,
                        defaultModel = whisperSt.activePreset.id,
                        models = whisperSt.profileSummaries().map { it.preset.id } + "whisper-1",
                        supportsStreaming = true,
                        note = "99-language dedicated on-device Whisper ASR (tiny/base/small Q5_1).",
                    ),
                )
                if (hinglishSt != null) {
                    val installedHinglish = HinglishModelCatalog.ALL_SPECS
                        .filter { hinglishSt.isInstalled(it.kind) }
                        .map { it.kind.apiId }
                    add(
                        AudioProviderDto(
                            id = "cactus",
                            name = "Hinglish Cactus ASR (AksharSTT v4 / Shruti)",
                            kind = "local_hinglish_asr",
                            builtin = true,
                            enabled = true,
                            ready = installedHinglish.isNotEmpty(),
                            recommended = false,
                            defaultModel = installedHinglish.firstOrNull() ?: AsrEngineKind.AKSHAR_V4.apiId,
                            models = HinglishModelCatalog.ALL_SPECS.map { it.kind.apiId },
                            supportsStreaming = true,
                            note = "Specialized lightweight 4-bit Hindi/Hinglish on-device models.",
                        ),
                    )
                }
                externalProviderRegistry.list().forEach { ext ->
                    add(ext.toDto(hasApiKey = externalProviderRegistry.hasApiKey(ext.id)))
                }
            }
            call.respond(
                AudioProvidersResponse(
                    preferredProvider = if (gemma4Ready) "gemma4" else "whisper",
                    data = list,
                ),
            )
        }

        post("/providers") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.asrModelControl) throw permissionDenied("asr_model_control")

            val req = call.readOptionalJson<RegisterExternalProviderRequest>(config.maxRequestBytes)
                ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "missing_body", "JSON provider configuration is required")

            val providerType = ExternalProviderType.parseOrNull(req.type)
                ?: throw ApiException(
                    HttpStatusCode.BadRequest,
                    "invalid_request_error",
                    "invalid_provider_type",
                    "Unsupported provider type '${req.type}' (allowed: openai_compatible_audio, openai_compatible_chat_audio, gemini_multimodal)",
                )

            val saved = try {
                externalProviderRegistry.upsert(
                    config = ExternalAudioProviderConfig(
                        id = req.id,
                        displayName = req.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: req.id,
                        type = providerType,
                        baseUrl = req.baseUrl,
                        defaultModel = req.defaultModel,
                        supportedModels = req.supportedModels,
                        apiKeyHeader = req.apiKeyHeader?.trim()?.takeIf { it.isNotEmpty() } ?: providerType.defaultAuthHeader(),
                        apiKeyPrefix = req.apiKeyPrefix ?: providerType.defaultAuthPrefix(),
                        customHeaders = req.customHeaders,
                        enabled = req.enabled,
                        timeoutMs = req.timeoutMs,
                    ),
                    apiKey = req.apiKey,
                    updateApiKey = req.apiKey != null,
                )
            } catch (e: IllegalArgumentException) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "invalid_provider_config", e.message ?: "Invalid external provider configuration")
            }

            auditLogger?.invoke(client.id, "audio.provider.upsert", saved.id, "OK", "type=${saved.type.apiId},model=${saved.defaultModel}")
            call.respond(saved.toDto(hasApiKey = externalProviderRegistry.hasApiKey(saved.id)))
        }

        delete("/providers/{id}") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.asrModelControl) throw permissionDenied("asr_model_control")

            val id = call.parameters["id"]?.trim().orEmpty()
            if (id.isEmpty()) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "missing_provider_id", "Provider id is required")
            }
            val removed = try {
                externalProviderRegistry.remove(id)
            } catch (e: IllegalArgumentException) {
                throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "reserved_provider", e.message ?: "Cannot remove built-in provider")
            }
            if (!removed) {
                throw ApiException(HttpStatusCode.NotFound, "invalid_request_error", "provider_not_found", "External provider '$id' was not found")
            }
            auditLogger?.invoke(client.id, "audio.provider.delete", id, "OK", null)
            call.respond(DeleteExternalProviderResponse(id = id.lowercase(), deleted = true))
        }

        get("/status") {
            call.authenticateClient(registry)
            val s = asrModelManager.status.value
            val q = asrEngine.queueState.value
            val cfg = asrSettingsProvider()
            val gemma4Ready = Gemma4AudioTranscriber.isReady(inferenceEngine)
            call.respond(
                AudioStatusResponse(
                    state = s.state.name,
                    modelLoaded = s.isLoaded,
                    activeModelId = s.activePreset.id,
                    activeProfile = s.selectedProfile.apiId,
                    autoLoadEnabled = cfg.autoLoadOnRequest,
                    defaultLanguage = cfg.defaultLanguage,
                    queueDepth = q.queued,
                    runningRequestId = q.runningRequestId,
                    downloadProgress = s.downloadProgress,
                    error = s.errorMessage,
                    maxUploadBytes = config.maxAudioUploadBytes,
                    maxAudioDurationSec = config.maxAudioDurationSec,
                    gemma4AudioReady = gemma4Ready,
                    preferredProvider = if (gemma4Ready) "gemma4" else "whisper",
                    externalProvidersCount = externalProviderRegistry.list().count { it.enabled },
                ),
            )
        }

        post("/transcriptions") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.speechToText) throw permissionDenied("speech_to_text")

            val parsed = call.parseAudioUpload(tempStore, config.maxAudioUploadBytes)
            val id = "asr-" + UUID.randomUUID()
            call.response.header("X-Request-Id", id)
            owners[id] = client.id

            try {
                val target = resolveAudioTarget(
                    providerParam = parsed.provider,
                    modelParam = parsed.model,
                    asrModelManager = asrModelManager,
                    asrSettings = asrSettingsProvider(),
                    inferenceEngine = inferenceEngine,
                    hinglishModelManager = hinglishModelManager,
                    externalRegistry = externalProviderRegistry,
                )

                val decoded = decodePermits.withPermit {
                    withContext(Dispatchers.IO) {
                        audioDecoder.decode(
                            file = parsed.tempFile,
                            contentType = parsed.contentType,
                            fileName = parsed.fileName,
                            maxDurationSec = config.maxAudioDurationSec,
                            pcmSampleRate = parsed.pcmSampleRate,
                            pcmChannels = parsed.pcmChannels,
                        )
                    }
                }
                tempStore.deleteQuietly(parsed.tempFile)

                when (target) {
                    is ResolvedAudioTarget.LocalWhisper -> {
                        val req = AsrRequest(
                            requestId = id,
                            clientId = client.id,
                            requestedModel = target.requestedModel,
                            audio = decoded,
                            language = parsed.language,
                            translate = parsed.translate,
                            prompt = parsed.prompt,
                            temperature = parsed.temperature,
                            timestamps = parsed.timestamps,
                            priority = client.permissions.priority,
                            sourceBytes = parsed.bytes,
                        )
                        val result = withTimeout(config.asrRequestTimeoutMs) {
                            asrEngine.transcribe(req)
                        }
                        respondTranscription(
                            id = result.requestId,
                            rawText = result.text,
                            modelId = result.modelId,
                            providerId = "whisper",
                            language = result.language,
                            durationSec = result.durationSec,
                            elapsedMs = result.elapsedMs,
                            segments = result.segments.map { seg ->
                                TranscriptionSegmentDto(
                                    id = seg.id,
                                    start = seg.startSec,
                                    end = seg.endSec,
                                    text = seg.text,
                                    noSpeechProb = seg.noSpeechProb,
                                )
                            },
                            parsed = parsed,
                        )
                    }

                    ResolvedAudioTarget.LocalGemma4 -> {
                        val result = withTimeout(config.asrRequestTimeoutMs) {
                            Gemma4AudioTranscriber.transcribe(
                                engine = inferenceEngine,
                                requestId = id,
                                clientId = client.id,
                                decoded = decoded,
                                language = parsed.language,
                                prompt = parsed.prompt,
                                temperature = parsed.temperature,
                                translate = parsed.translate,
                                priority = client.permissions.priority,
                                settings = inferenceSettingsProvider(),
                            )
                        }
                        respondTranscription(
                            id = result.requestId,
                            rawText = result.text,
                            modelId = result.modelId,
                            providerId = "gemma4",
                            language = result.language,
                            durationSec = result.durationSec,
                            elapsedMs = result.elapsedMs,
                            segments = result.segments.map { seg ->
                                TranscriptionSegmentDto(
                                    id = seg.id,
                                    start = seg.startSec,
                                    end = seg.endSec,
                                    text = seg.text,
                                )
                            },
                            parsed = parsed,
                        )
                    }

                    is ResolvedAudioTarget.LocalCactus -> {
                        val reg = speechEngineRegistry ?: throw ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "model_unavailable",
                            "hinglish_engine_unavailable",
                            "Hinglish ASR registry is not configured on this server",
                        )
                        val engine = reg.get(target.kind)
                        if (!engine.isAvailable()) {
                            throw AsrModelNotReadyException(
                                "${target.kind.displayName} is not installed. Download it in UOAI Provider -> Models -> Speech.",
                            )
                        }
                        val startedAt = System.currentTimeMillis()
                        val res = withTimeout(config.asrRequestTimeoutMs) {
                            engine.initialize()
                            engine.transcribeFloat(
                                audio16kMono = decoded.samples,
                                language = parsed.language ?: asrSettingsProvider().defaultLanguage,
                            )
                        }
                        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                        respondTranscription(
                            id = id,
                            rawText = res.text,
                            modelId = target.kind.apiId,
                            providerId = "cactus",
                            language = res.language ?: parsed.language ?: "hi",
                            durationSec = decoded.durationSec,
                            elapsedMs = elapsed,
                            segments = listOf(
                                TranscriptionSegmentDto(
                                    id = 0,
                                    start = 0.0,
                                    end = decoded.durationSec,
                                    text = res.text,
                                ),
                            ),
                            parsed = parsed,
                        )
                    }

                    is ResolvedAudioTarget.External -> {
                        val wavBytes = WavPcmDecoder.encodeWavPcm16(decoded.samples, decoded.sampleRate)
                        val extRes = withTimeout(target.config.timeoutMs.coerceAtMost(config.asrRequestTimeoutMs)) {
                            externalProviderClient.transcribe(
                                config = target.config,
                                apiKey = externalProviderRegistry.getApiKey(target.config.id),
                                request = ExternalAudioRequest(
                                    requestId = id,
                                    wavBytes = wavBytes,
                                    fileName = parsed.fileName ?: "audio.wav",
                                    contentType = "audio/wav",
                                    durationSec = decoded.durationSec,
                                    model = target.model,
                                    language = parsed.language,
                                    prompt = parsed.prompt,
                                    temperature = parsed.temperature,
                                    translate = parsed.translate,
                                ),
                            )
                        }
                        auditLogger?.invoke(
                            client.id,
                            "audio.external.transcribe",
                            "${target.config.id}/${extRes.model}",
                            "OK",
                            "elapsed_ms=${extRes.elapsedMs}",
                        )
                        respondTranscription(
                            id = id,
                            rawText = extRes.text,
                            modelId = extRes.model,
                            providerId = extRes.providerId,
                            language = extRes.language,
                            durationSec = extRes.durationSec,
                            elapsedMs = extRes.elapsedMs,
                            segments = extRes.segments.map { seg ->
                                TranscriptionSegmentDto(
                                    id = seg.id,
                                    start = seg.startSec,
                                    end = seg.endSec,
                                    text = seg.text,
                                )
                            },
                            parsed = parsed,
                        )
                    }
                }
            } finally {
                tempStore.deleteQuietly(parsed.tempFile)
                owners.remove(id)
            }
        }

        post("/stream") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.speechToText) throw permissionDenied("speech_to_text")
            if (!client.permissions.streaming) throw permissionDenied("streaming")

            val parsed = call.parseAudioUpload(tempStore, config.maxAudioUploadBytes)
            val id = "asr-" + UUID.randomUUID()
            call.response.header("X-Request-Id", id)
            owners[id] = client.id

            try {
                val target = resolveAudioTarget(
                    providerParam = parsed.provider,
                    modelParam = parsed.model,
                    asrModelManager = asrModelManager,
                    asrSettings = asrSettingsProvider(),
                    inferenceEngine = inferenceEngine,
                    hinglishModelManager = hinglishModelManager,
                    externalRegistry = externalProviderRegistry,
                )

                val decoded = decodePermits.withPermit {
                    withContext(Dispatchers.IO) {
                        audioDecoder.decode(
                            file = parsed.tempFile,
                            contentType = parsed.contentType,
                            fileName = parsed.fileName,
                            maxDurationSec = config.maxAudioDurationSec,
                            pcmSampleRate = parsed.pcmSampleRate,
                            pcmChannels = parsed.pcmChannels,
                        )
                    }
                }
                tempStore.deleteQuietly(parsed.tempFile)

                when (target) {
                    is ResolvedAudioTarget.LocalWhisper -> {
                        val req = AsrRequest(
                            requestId = id,
                            clientId = client.id,
                            requestedModel = target.requestedModel,
                            audio = decoded,
                            language = parsed.language,
                            translate = parsed.translate,
                            prompt = parsed.prompt,
                            temperature = parsed.temperature,
                            timestamps = true,
                            priority = client.permissions.priority,
                            sourceBytes = parsed.bytes,
                        )
                        val status = asrModelManager.status.value
                        val cfg = asrSettingsProvider()
                        if (!status.isLoaded && (!cfg.autoLoadOnRequest || !status.isSelectedInstalled)) {
                            throw AsrModelNotReadyException(
                                "ASR model '${status.activePreset.displayName}' is not ready (state=${status.state})",
                            )
                        }
                        streamAsrResponse(asrEngine, req, config, parsed.script)
                    }

                    ResolvedAudioTarget.LocalGemma4 -> {
                        Gemma4AudioTranscriber.requireAudioReady(inferenceEngine)
                        streamGemma4Response(
                            engine = inferenceEngine,
                            requestId = id,
                            clientId = client.id,
                            decoded = decoded,
                            parsed = parsed,
                            priority = client.permissions.priority,
                            settings = inferenceSettingsProvider(),
                            config = config,
                        )
                    }

                    is ResolvedAudioTarget.LocalCactus -> {
                        val reg = speechEngineRegistry ?: throw ApiException(
                            HttpStatusCode.ServiceUnavailable,
                            "model_unavailable",
                            "hinglish_engine_unavailable",
                            "Hinglish ASR registry is not configured on this server",
                        )
                        val engine = reg.get(target.kind)
                        if (!engine.isAvailable()) {
                            throw AsrModelNotReadyException("${target.kind.displayName} is not installed")
                        }
                        streamSingleShotAsrResponse(
                            requestId = id,
                            providerId = "cactus",
                            modelId = target.kind.apiId,
                            initialLanguage = parsed.language ?: "hi",
                            durationSec = decoded.durationSec,
                            script = parsed.script,
                            timeoutMs = config.asrRequestTimeoutMs,
                        ) {
                            engine.initialize()
                            val res = engine.transcribeFloat(decoded.samples, parsed.language ?: "hi")
                            res.text to (res.language ?: parsed.language ?: "hi")
                        }
                    }

                    is ResolvedAudioTarget.External -> {
                        val wavBytes = WavPcmDecoder.encodeWavPcm16(decoded.samples, decoded.sampleRate)
                        streamSingleShotAsrResponse(
                            requestId = id,
                            providerId = target.config.id,
                            modelId = target.model,
                            initialLanguage = parsed.language ?: "auto",
                            durationSec = decoded.durationSec,
                            script = parsed.script,
                            timeoutMs = target.config.timeoutMs.coerceAtMost(config.asrRequestTimeoutMs),
                        ) {
                            val res = externalProviderClient.transcribe(
                                config = target.config,
                                apiKey = externalProviderRegistry.getApiKey(target.config.id),
                                request = ExternalAudioRequest(
                                    requestId = id,
                                    wavBytes = wavBytes,
                                    fileName = parsed.fileName ?: "audio.wav",
                                    contentType = "audio/wav",
                                    durationSec = decoded.durationSec,
                                    model = target.model,
                                    language = parsed.language,
                                    prompt = parsed.prompt,
                                    temperature = parsed.temperature,
                                    translate = parsed.translate,
                                ),
                            )
                            res.text to res.language
                        }
                    }
                }
            } finally {
                tempStore.deleteQuietly(parsed.tempFile)
                owners.remove(id)
            }
        }

        post("/models/load") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.asrModelControl) throw permissionDenied("asr_model_control")

            val req = call.readOptionalJson<AudioModelLoadRequest>(config.maxRequestBytes) ?: AudioModelLoadRequest()
            val raw = req.model?.trim()?.lowercase()

            // Support loading Hinglish Cactus models via /v1/audio/models/load too
            val cactusKind = raw?.let { resolveExplicitCactusKindOrNull(it) }
            if (cactusKind != null) {
                val hMgr = hinglishModelManager ?: throw ApiException(
                    HttpStatusCode.ServiceUnavailable,
                    "model_unavailable",
                    "hinglish_engine_unavailable",
                    "Hinglish ASR model manager is not available",
                )
                if (!hMgr.status.value.isInstalled(cactusKind)) {
                    throw ApiException(
                        HttpStatusCode.NotFound,
                        "invalid_request_error",
                        "model_not_found",
                        "Hinglish ASR model '${cactusKind.displayName}' is not downloaded. Download it in UOAI Provider first.",
                    )
                }
                val loadMs = hMgr.load(cactusKind)
                auditLogger?.invoke(client.id, "asr.model.load", cactusKind.apiId, "OK", "load_time_ms=$loadMs")
                call.respond(
                    AudioModelLoadResponse(
                        loaded = hMgr.status.value.activeLoadedKind == cactusKind,
                        modelId = cactusKind.apiId,
                        profile = cactusKind.apiId,
                        state = hMgr.status.value.state.name,
                        loadTimeMs = loadMs,
                    ),
                )
                return@post
            }

            val targetPreset = when (raw) {
                null, "", "auto", "default" -> asrModelManager.status.value.activePreset
                else -> AsrModelCatalog.resolveOrNull(raw)
                    ?: throw ApiException(HttpStatusCode.NotFound, "invalid_request_error", "model_not_found", "Unknown ASR model '${req.model}'")
            }

            if (!asrModelManager.status.value.installedProfiles.containsKey(targetPreset.profile)) {
                throw ApiException(
                    HttpStatusCode.NotFound,
                    "invalid_request_error",
                    "model_not_found",
                    "ASR model '${targetPreset.displayName}' is not downloaded. Download it in UOAI Provider first.",
                )
            }

            if (asrEngine.queueState.value.runningRequestId != null &&
                asrModelManager.status.value.loadedProfile != targetPreset.profile
            ) {
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "conflict_error",
                    "asr_busy",
                    "Cannot switch ASR model while another transcription is running",
                )
            }

            val loadMs = asrModelManager.load(targetPreset.profile)
            auditLogger?.invoke(client.id, "asr.model.load", targetPreset.id, "OK", "load_time_ms=$loadMs")
            val next = asrModelManager.status.value
            call.respond(
                AudioModelLoadResponse(
                    loaded = next.isLoaded,
                    modelId = targetPreset.id,
                    profile = targetPreset.profile.apiId,
                    state = next.state.name,
                    loadTimeMs = loadMs,
                ),
            )
        }

        post("/models/unload") {
            val client = call.authenticateClient(registry)
            if (!client.permissions.asrModelControl) throw permissionDenied("asr_model_control")

            val req = call.readOptionalJson<AudioModelUnloadRequest>(config.maxRequestBytes) ?: AudioModelUnloadRequest()
            val q = asrEngine.queueState.value
            if ((q.runningRequestId != null || q.queued > 0) && !req.force) {
                throw ApiException(
                    HttpStatusCode.Conflict,
                    "conflict_error",
                    "asr_busy",
                    "ASR engine has active or queued requests; pass {\"force\":true} to cancel and unload",
                )
            }
            if (req.force) {
                q.runningRequestId?.let { asrEngine.cancel(it) }
            }
            asrModelManager.unload()
            hinglishModelManager?.unload()
            auditLogger?.invoke(client.id, "asr.model.unload", asrModelManager.status.value.activePreset.id, "OK", "force=${req.force}")
            call.respond(
                AudioModelUnloadResponse(
                    unloaded = true,
                    state = asrModelManager.status.value.state.name,
                ),
            )
        }
    }
}

private fun ExternalAudioProviderConfig.toDto(hasApiKey: Boolean) = AudioProviderDto(
    id = id,
    name = displayName,
    kind = type.apiId,
    builtin = false,
    enabled = enabled,
    ready = enabled,
    recommended = false,
    baseUrl = baseUrl,
    defaultModel = defaultModel,
    models = allModels(),
    hasApiKey = hasApiKey,
    supportsStreaming = true,
    note = type.label,
)

private fun resolveAudioTarget(
    providerParam: String?,
    modelParam: String?,
    asrModelManager: AsrModelManager,
    asrSettings: AsrSettings,
    inferenceEngine: InferenceEngine,
    hinglishModelManager: HinglishModelManager?,
    externalRegistry: ExternalAudioProviderRegistry,
): ResolvedAudioTarget {
    val normProvider = providerParam?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "auto" && it != "default" }
    val normModel = modelParam?.trim()?.takeIf { it.isNotEmpty() }
    val lowerModel = normModel?.lowercase()

    // 1. Explicit provider always wins
    if (normProvider != null) {
        return when (normProvider) {
            "gemma4", "gemma-4", "local-gemma4", "llm", "llm-direct" -> ResolvedAudioTarget.LocalGemma4
            "whisper", "local-whisper" -> ResolvedAudioTarget.LocalWhisper(normModel)
            "cactus", "hinglish", "local-hinglish", "akshar", "shruti" -> {
                val kind = resolveExplicitCactusKindOrNull(lowerModel)
                    ?: resolveExplicitCactusKindOrNull(normProvider)
                    ?: hinglishModelManager?.status?.value?.activeLoadedKind
                    ?: HinglishModelCatalog.ALL_SPECS.firstOrNull { hinglishModelManager?.status?.value?.isInstalled(it.kind) == true }?.kind
                    ?: AsrEngineKind.AKSHAR_V4
                ResolvedAudioTarget.LocalCactus(kind)
            }
            else -> {
                val ext = externalRegistry.get(normProvider)
                    ?: throw ApiException(
                        HttpStatusCode.NotFound,
                        "invalid_request_error",
                        "provider_not_found",
                        "Unknown audio inference provider '$providerParam'",
                    )
                if (!ext.enabled) {
                    throw ApiException(
                        HttpStatusCode.BadRequest,
                        "invalid_request_error",
                        "provider_disabled",
                        "External audio provider '${ext.id}' is currently disabled",
                    )
                }
                val chosenModel = if (lowerModel == null || lowerModel == "auto" || lowerModel == "default") {
                    ext.defaultModel
                } else {
                    normModel
                }
                ResolvedAudioTarget.External(ext, chosenModel)
            }
        }
    }

    // 2. Implicit provider: infer from explicit model name
    if (lowerModel != null && lowerModel != "auto" && lowerModel != "default") {
        val activeLlmId = inferenceEngine.modelStatus.value.model?.id?.lowercase()
        if (lowerModel == "gemma-4" ||
            lowerModel == "gemma4" ||
            lowerModel == "gemma-4-audio" ||
            lowerModel == "llm-direct" ||
            lowerModel.startsWith("gemma-4-") ||
            (activeLlmId != null && lowerModel == activeLlmId)
        ) {
            return ResolvedAudioTarget.LocalGemma4
        }

        resolveExplicitCactusKindOrNull(lowerModel)?.let { return ResolvedAudioTarget.LocalCactus(it) }

        if (lowerModel == "whisper" || lowerModel == "whisper-1" || AsrModelCatalog.resolveOrNull(lowerModel) != null) {
            return ResolvedAudioTarget.LocalWhisper(normModel)
        }

        externalRegistry.findEnabledForModel(normModel)?.let { ext ->
            return ResolvedAudioTarget.External(ext, normModel)
        }

        // Preserve existing Whisper model-mismatch / unknown-model error contract
        return ResolvedAudioTarget.LocalWhisper(normModel)
    }

    // 3. Both provider & model are "auto"/omitted:
    //    Preserve 100% backward compatibility with Whisper when Whisper is loaded or ready to auto-load;
    //    gracefully fall back to direct Gemma 4 multimodal when Whisper isn't ready and Gemma 4 audio is ready.
    val whisperStatus = asrModelManager.status.value
    val whisperReady = whisperStatus.isLoaded || (asrSettings.autoLoadOnRequest && whisperStatus.isSelectedInstalled)
    if (whisperReady) {
        return ResolvedAudioTarget.LocalWhisper(normModel)
    }
    if (Gemma4AudioTranscriber.isReady(inferenceEngine)) {
        return ResolvedAudioTarget.LocalGemma4
    }
    return ResolvedAudioTarget.LocalWhisper(normModel)
}

private fun resolveExplicitCactusKindOrNull(raw: String?): AsrEngineKind? = when (raw?.trim()?.lowercase()) {
    "akshar-v4", "akshar", "aksharstt", "aksharstt-v4" -> AsrEngineKind.AKSHAR_V4
    "akshar-lite-v4", "akshar-lite", "aksharstt-lite-v4" -> AsrEngineKind.AKSHAR_LITE_V4
    "shruti-hi", "shruti", "shruti-hindi" -> AsrEngineKind.SHRUTI_HI
    else -> null
}

private fun formatScriptText(rawText: String, scriptParam: String?): FormattedScriptText {
    val norm = scriptParam?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val hasDevanagari = rawText.any { it in '\u0900'..'\u097F' }
    if (norm == null && !hasDevanagari) {
        return FormattedScriptText(displayText = rawText, romanHinglish = null, devanagariHinglish = null)
    }
    val converted = HinglishScriptConverter.convert(rawText)
    val display = when (norm) {
        "roman", "roman_hinglish" -> converted.romanHinglish
        "devanagari", "devanagari_hinglish" -> converted.devanagariHinglish
        "both" -> if (converted.devanagariHinglish == converted.romanHinglish) {
            converted.canonicalText
        } else {
            "${converted.devanagariHinglish}\n${converted.romanHinglish}"
        }
        else -> converted.canonicalText
    }
    return FormattedScriptText(
        displayText = display,
        romanHinglish = converted.romanHinglish,
        devanagariHinglish = converted.devanagariHinglish,
    )
}

private suspend fun RoutingContext.respondTranscription(
    id: String,
    rawText: String,
    modelId: String,
    providerId: String,
    language: String,
    durationSec: Double,
    elapsedMs: Long,
    segments: List<TranscriptionSegmentDto>,
    parsed: ParsedAudioUpload,
) {
    val formatted = formatScriptText(rawText, parsed.script)
    when (parsed.responseFormat) {
        "text" -> call.respondText(formatted.displayText, ContentType.Text.Plain)
        "verbose_json" -> call.respond(
            VerboseTranscriptionResponse(
                id = id,
                task = if (parsed.translate) "translate" else "transcribe",
                model = modelId,
                language = language,
                duration = durationSec,
                text = formatted.displayText,
                segments = segments,
                elapsedMs = elapsedMs,
                provider = providerId,
                romanHinglish = formatted.romanHinglish,
                devanagariHinglish = formatted.devanagariHinglish,
            ),
        )
        else -> call.respond(
            TranscriptionResponse(
                id = id,
                text = formatted.displayText,
                model = modelId,
                language = language,
                provider = providerId,
                romanHinglish = formatted.romanHinglish,
                devanagariHinglish = formatted.devanagariHinglish,
            ),
        )
    }
}

private suspend fun RoutingContext.streamAsrResponse(
    engine: AsrEngine,
    req: AsrRequest,
    config: ApiConfig,
    script: String?,
) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(ContentType.Text.EventStream) {
        suspend fun send(data: String) {
            write("data: $data\n\n")
            flush()
        }
        try {
            withTimeout(config.asrRequestTimeoutMs) {
                engine.stream(req).collect { ev ->
                    when (ev) {
                        is AsrStreamEvent.Started -> send(
                            ApiJson.encodeToString(
                                AudioStreamEventDto(
                                    id = ev.requestId,
                                    obj = "audio.transcription.start",
                                    model = ev.modelId,
                                    language = ev.language,
                                    provider = "whisper",
                                ),
                            ),
                        )
                        is AsrStreamEvent.Segment -> {
                            val formatted = formatScriptText(ev.segment.text, script)
                            send(
                                ApiJson.encodeToString(
                                    AudioStreamEventDto(
                                        id = ev.requestId,
                                        obj = "audio.transcription.segment",
                                        index = ev.segment.id,
                                        start = ev.segment.startSec,
                                        end = ev.segment.endSec,
                                        text = formatted.displayText,
                                        language = ev.language,
                                        isFinal = true,
                                        provider = "whisper",
                                        romanHinglish = formatted.romanHinglish,
                                        devanagariHinglish = formatted.devanagariHinglish,
                                    ),
                                ),
                            )
                        }
                        is AsrStreamEvent.Completed -> {
                            val formatted = formatScriptText(ev.result.text, script)
                            send(
                                ApiJson.encodeToString(
                                    AudioStreamEventDto(
                                        id = ev.result.requestId,
                                        obj = "audio.transcription.done",
                                        model = ev.result.modelId,
                                        language = ev.result.language,
                                        duration = ev.result.durationSec,
                                        text = formatted.displayText,
                                        finishReason = "stop",
                                        provider = "whisper",
                                        romanHinglish = formatted.romanHinglish,
                                        devanagariHinglish = formatted.devanagariHinglish,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            send("[DONE]")
        } catch (_: TimeoutCancellationException) {
            engine.cancel(req.requestId)
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody("Transcription timed out", "timeout", "request_timeout", req.requestId))))
        } catch (e: AsrModelNotReadyException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "ASR model not ready", "model_unavailable", "asr_model_not_ready", req.requestId))))
        } catch (_: AsrRequestCancelledException) {
            send(
                ApiJson.encodeToString(
                    AudioStreamEventDto(
                        id = req.requestId,
                        obj = "audio.transcription.done",
                        finishReason = "cancelled",
                        provider = "whisper",
                    ),
                ),
            )
            send("[DONE]")
        } catch (e: IOException) {
            engine.cancel(req.requestId)
            throw e
        } catch (e: CancellationException) {
            engine.cancel(req.requestId)
            throw e
        } catch (e: Exception) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Transcription failed", "server_error", "transcription_failed", req.requestId))))
        }
    }
}

private suspend fun RoutingContext.streamGemma4Response(
    engine: InferenceEngine,
    requestId: String,
    clientId: String,
    decoded: DecodedAudio,
    parsed: ParsedAudioUpload,
    priority: Int,
    settings: InferenceSettings,
    config: ApiConfig,
) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(ContentType.Text.EventStream) {
        suspend fun send(data: String) {
            write("data: $data\n\n")
            flush()
        }
        try {
            withTimeout(config.asrRequestTimeoutMs) {
                Gemma4AudioTranscriber.transcribeStream(
                    engine = engine,
                    requestId = requestId,
                    clientId = clientId,
                    decoded = decoded,
                    language = parsed.language,
                    prompt = parsed.prompt,
                    temperature = parsed.temperature,
                    translate = parsed.translate,
                    priority = priority,
                    settings = settings,
                ).collect { ev ->
                    when (ev) {
                        is AsrStreamEvent.Started -> send(
                            ApiJson.encodeToString(
                                AudioStreamEventDto(
                                    id = ev.requestId,
                                    obj = "audio.transcription.start",
                                    model = ev.modelId,
                                    language = ev.language,
                                    provider = "gemma4",
                                ),
                            ),
                        )
                        is AsrStreamEvent.Segment -> {
                            val formatted = formatScriptText(ev.segment.text, parsed.script)
                            send(
                                ApiJson.encodeToString(
                                    AudioStreamEventDto(
                                        id = ev.requestId,
                                        obj = "audio.transcription.segment",
                                        index = ev.segment.id,
                                        start = ev.segment.startSec,
                                        end = ev.segment.endSec,
                                        text = formatted.displayText,
                                        language = ev.language,
                                        isFinal = true,
                                        provider = "gemma4",
                                        romanHinglish = formatted.romanHinglish,
                                        devanagariHinglish = formatted.devanagariHinglish,
                                    ),
                                ),
                            )
                        }
                        is AsrStreamEvent.Completed -> {
                            val formatted = formatScriptText(ev.result.text, parsed.script)
                            send(
                                ApiJson.encodeToString(
                                    AudioStreamEventDto(
                                        id = ev.result.requestId,
                                        obj = "audio.transcription.done",
                                        model = ev.result.modelId,
                                        language = ev.result.language,
                                        duration = ev.result.durationSec,
                                        text = formatted.displayText,
                                        finishReason = "stop",
                                        provider = "gemma4",
                                        romanHinglish = formatted.romanHinglish,
                                        devanagariHinglish = formatted.devanagariHinglish,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            send("[DONE]")
        } catch (_: TimeoutCancellationException) {
            engine.cancel(requestId)
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody("Transcription timed out", "timeout", "request_timeout", requestId))))
        } catch (e: ModelNotReadyException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Gemma 4 not ready", "model_unavailable", "model_not_ready", requestId))))
        } catch (_: RequestCancelledException) {
            send(
                ApiJson.encodeToString(
                    AudioStreamEventDto(
                        id = requestId,
                        obj = "audio.transcription.done",
                        finishReason = "cancelled",
                        provider = "gemma4",
                    ),
                ),
            )
            send("[DONE]")
        } catch (e: IOException) {
            engine.cancel(requestId)
            throw e
        } catch (e: CancellationException) {
            engine.cancel(requestId)
            throw e
        } catch (e: Exception) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Gemma 4 audio transcription failed", "server_error", "transcription_failed", requestId))))
        }
    }
}

private suspend fun RoutingContext.streamSingleShotAsrResponse(
    requestId: String,
    providerId: String,
    modelId: String,
    initialLanguage: String,
    durationSec: Double,
    script: String?,
    timeoutMs: Long,
    block: suspend () -> Pair<String, String>,
) {
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(ContentType.Text.EventStream) {
        suspend fun send(data: String) {
            write("data: $data\n\n")
            flush()
        }
        try {
            send(
                ApiJson.encodeToString(
                    AudioStreamEventDto(
                        id = requestId,
                        obj = "audio.transcription.start",
                        model = modelId,
                        language = initialLanguage,
                        provider = providerId,
                    ),
                ),
            )
            val (rawText, lang) = withTimeout(timeoutMs) { block() }
            val formatted = formatScriptText(rawText, script)
            if (formatted.displayText.isNotBlank()) {
                send(
                    ApiJson.encodeToString(
                        AudioStreamEventDto(
                            id = requestId,
                            obj = "audio.transcription.segment",
                            index = 0,
                            start = 0.0,
                            end = durationSec,
                            text = formatted.displayText,
                            language = lang,
                            isFinal = true,
                            provider = providerId,
                            romanHinglish = formatted.romanHinglish,
                            devanagariHinglish = formatted.devanagariHinglish,
                        ),
                    ),
                )
            }
            send(
                ApiJson.encodeToString(
                    AudioStreamEventDto(
                        id = requestId,
                        obj = "audio.transcription.done",
                        model = modelId,
                        language = lang,
                        duration = durationSec,
                        text = formatted.displayText,
                        finishReason = "stop",
                        provider = providerId,
                        romanHinglish = formatted.romanHinglish,
                        devanagariHinglish = formatted.devanagariHinglish,
                    ),
                ),
            )
            send("[DONE]")
        } catch (_: TimeoutCancellationException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody("Transcription timed out", "timeout", "request_timeout", requestId))))
        } catch (e: ApiException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Provider error", e.type, e.code, requestId))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Transcription failed", "server_error", "transcription_failed", requestId))))
        }
    }
}

private suspend fun ApplicationCall.parseAudioUpload(
    tempStore: AsrTempFileStore,
    maxBytes: Long,
): ParsedAudioUpload {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) {
        throw ApiException(
            HttpStatusCode.PayloadTooLarge,
            "invalid_request_error",
            "audio_too_large",
            "Audio upload exceeds maximum allowed $maxBytes bytes",
        )
    }

    val ct = request.contentType()
    if (ct.match(ContentType.MultiPart.FormData)) {
        var tempFile: File? = null
        var fileBytes = 0L
        var fileContentType: String? = null
        var fileName: String? = null
        var model: String? = null
        var provider: String? = null
        var language: String? = null
        var prompt: String? = null
        var script: String? = null
        var responseFormat = "json"
        var temperature = 0.0f
        var translate = false
        var timestamps = true
        var pcmRate = DecodedAudio.TARGET_SAMPLE_RATE
        var pcmChannels = 1

        try {
            val multipart = receiveMultipart(formFieldLimit = 64 * 1024)
            multipart.forEachPart { part ->
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            if (part.name == "file" || tempFile == null) {
                                tempStore.deleteQuietly(tempFile)
                                val (f, size) = tempStore.writeChannelBounded(part.provider(), maxBytes)
                                tempFile = f
                                fileBytes = size
                                fileContentType = part.contentType?.toString()
                                fileName = part.originalFileName
                            }
                        }
                        is PartData.FormItem -> {
                            when (part.name) {
                                "model" -> model = part.value.trim().takeIf { it.isNotEmpty() }
                                "provider" -> provider = part.value.trim().takeIf { it.isNotEmpty() }
                                "language" -> language = part.value.trim().takeIf { it.isNotEmpty() }
                                "prompt" -> prompt = part.value
                                "script" -> script = part.value.trim().takeIf { it.isNotEmpty() }
                                "response_format" -> responseFormat = part.value.trim().lowercase().ifEmpty { "json" }
                                "temperature" -> temperature = part.value.toFloatOrNull()
                                    ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "invalid_parameter", "Invalid temperature '${part.value}'")
                                "translate" -> translate = part.value.equals("true", ignoreCase = true) || part.value == "1"
                                "timestamps" -> timestamps = !part.value.equals("false", ignoreCase = true)
                                "timestamp_granularities[]" -> if (part.value.isNotBlank()) timestamps = true
                                "sample_rate" -> pcmRate = part.value.toIntOrNull() ?: DecodedAudio.TARGET_SAMPLE_RATE
                                "channels" -> pcmChannels = part.value.toIntOrNull() ?: 1
                            }
                        }
                        else -> Unit
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (t: Throwable) {
            tempStore.deleteQuietly(tempFile)
            throw t
        }

        val finalFile = tempFile ?: throw ApiException(
            HttpStatusCode.BadRequest,
            "invalid_request_error",
            "missing_audio_file",
            "Multipart field 'file' is required",
        )
        validateResponseFormat(responseFormat, finalFile, tempStore)
        validateScriptParam(script, finalFile, tempStore)
        return ParsedAudioUpload(
            tempFile = finalFile,
            bytes = fileBytes,
            contentType = fileContentType,
            fileName = fileName,
            model = model,
            provider = provider,
            language = language,
            prompt = prompt,
            script = script,
            responseFormat = responseFormat,
            temperature = temperature,
            translate = translate,
            timestamps = timestamps,
            pcmSampleRate = pcmRate,
            pcmChannels = pcmChannels,
        )
    }

    // Raw binary audio upload (audio/wav, audio/pcm, audio/mpeg, audio/mp4, etc.)
    val (file, bytes) = tempStore.writeChannelBounded(receiveChannel(), maxBytes)
    if (bytes <= 0L) {
        tempStore.deleteQuietly(file)
        throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "missing_audio_file", "Audio request body is empty")
    }
    val qp = request.queryParameters
    val responseFormat = qp["response_format"]?.trim()?.lowercase()?.ifEmpty { "json" } ?: "json"
    val script = qp["script"]?.trim()?.takeIf { it.isNotEmpty() }
    validateResponseFormat(responseFormat, file, tempStore)
    validateScriptParam(script, file, tempStore)

    val tempParam = qp["temperature"]?.let { raw ->
        raw.toFloatOrNull() ?: run {
            tempStore.deleteQuietly(file)
            throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "invalid_parameter", "Invalid temperature '$raw'")
        }
    } ?: 0.0f

    val rateFromCt = ct.parameter("rate")?.toIntOrNull()
    val chFromCt = ct.parameter("channels")?.toIntOrNull()
    val pcmRate = qp["sample_rate"]?.toIntOrNull() ?: rateFromCt ?: DecodedAudio.TARGET_SAMPLE_RATE
    val pcmChannels = qp["channels"]?.toIntOrNull() ?: chFromCt ?: 1

    return ParsedAudioUpload(
        tempFile = file,
        bytes = bytes,
        contentType = ct.withoutParameters().toString(),
        fileName = qp["filename"],
        model = qp["model"]?.trim()?.takeIf { it.isNotEmpty() },
        provider = qp["provider"]?.trim()?.takeIf { it.isNotEmpty() },
        language = qp["language"]?.trim()?.takeIf { it.isNotEmpty() },
        prompt = qp["prompt"],
        script = script,
        responseFormat = responseFormat,
        temperature = tempParam,
        translate = qp["translate"].equals("true", ignoreCase = true) || qp["translate"] == "1",
        timestamps = !qp["timestamps"].equals("false", ignoreCase = true),
        pcmSampleRate = pcmRate,
        pcmChannels = pcmChannels,
    )
}

private fun validateResponseFormat(fmt: String, tempFile: File, store: AsrTempFileStore) {
    if (fmt !in setOf("json", "verbose_json", "text")) {
        store.deleteQuietly(tempFile)
        throw ApiException(
            HttpStatusCode.BadRequest,
            "invalid_request_error",
            "invalid_parameter",
            "Unsupported response_format '$fmt' (allowed: json, verbose_json, text)",
        )
    }
}

private fun validateScriptParam(script: String?, tempFile: File, store: AsrTempFileStore) {
    val norm = script?.trim()?.lowercase() ?: return
    if (norm !in setOf("canonical", "devanagari", "devanagari_hinglish", "roman", "roman_hinglish", "both")) {
        store.deleteQuietly(tempFile)
        throw ApiException(
            HttpStatusCode.BadRequest,
            "invalid_request_error",
            "invalid_parameter",
            "Unsupported script '$script' (allowed: canonical, devanagari, roman, both)",
        )
    }
}
