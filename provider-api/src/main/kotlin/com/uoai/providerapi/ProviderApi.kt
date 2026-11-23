package com.uoai.providerapi

import com.uoai.clients.AuthenticatedClient
import com.uoai.clients.ClientRegistry
import com.uoai.inference.ChatMessage
import com.uoai.inference.InferenceEngine
import com.uoai.inference.InferenceRequest
import com.uoai.inference.InferenceSettings
import com.uoai.inference.ModelNotReadyException
import com.uoai.inference.ModelState
import com.uoai.inference.QueueFullException
import com.uoai.inference.RequestCancelledException
import com.uoai.inference.runtime.GenerationParams
import com.uoai.plugins.PluginRegistry
import com.uoai.plugins.models.ToolCallRequest
import com.uoai.providerapi.dto.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val ApiJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

data class ApiConfig(
    val publicModelId: String = "gemma-3",
    val maxRequestBytes: Int = 2 * 1024 * 1024,
    val maxTokensLimit: Int = InferenceSettings.MAX_TOKENS_32K,
    val requestTimeoutMs: Long = 300_000,
)

/** Installs the whole provider API. Talks only to [InferenceEngine], [ClientRegistry], and optional [PluginRegistry]. */
fun Application.providerApi(
    engine: InferenceEngine,
    registry: ClientRegistry,
    config: ApiConfig = ApiConfig(),
    plugins: PluginRegistry? = null,
    settingsProvider: () -> InferenceSettings = { InferenceSettings() },
) {
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondError(e.status, e.type, e.code, e.message ?: "") }
        exception<ModelNotReadyException> { call, e ->
            call.respondError(HttpStatusCode.ServiceUnavailable, "model_unavailable", "model_not_ready", e.message ?: "Model not ready")
        }
        exception<QueueFullException> { call, e ->
            call.respondError(HttpStatusCode.TooManyRequests, "rate_limit_error", "queue_full", e.message ?: "Queue full")
        }
        exception<RequestCancelledException> { call, e ->
            call.respondError(HttpStatusCode.Conflict, "request_cancelled", "request_cancelled", e.message ?: "Cancelled")
        }
        exception<IllegalArgumentException> { call, e ->
            call.respondError(HttpStatusCode.BadRequest, "invalid_request_error", "invalid_request", e.message ?: "Invalid request")
        }
        exception<TimeoutCancellationException> { call, _ ->
            call.respondError(HttpStatusCode.GatewayTimeout, "timeout", "request_timeout", "Inference timed out")
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled API error", e)
            call.respondError(HttpStatusCode.InternalServerError, "server_error", "internal_error", e.message ?: "Internal error")
        }
    }

    // request id -> owning client, so one client cannot cancel another's request
    val owners = ConcurrentHashMap<String, String>()

    routing {
        route("/v1") {
            get("/health") {
                val s = engine.modelStatus.value
                val ok = s.state == ModelState.READY || s.state == ModelState.GENERATING
                call.respond(HealthResponse(if (ok) "ok" else "degraded", s.state.name, ok, s.errorMessage))
            }
            get("/models") {
                call.authenticate(registry)
                val s = engine.modelStatus.value
                val ready = s.state == ModelState.READY || s.state == ModelState.GENERATING
                val ctx = s.loadedConfig?.contextWindowTokens ?: settingsProvider().contextWindowTokens
                val models = s.model?.let {
                    listOf(
                        ModelDto(
                            id = it.id,
                            name = it.name,
                            format = it.format,
                            quantization = it.quantization,
                            sizeBytes = it.fileSizeBytes,
                            ready = ready,
                            contextWindow = ctx,
                        ),
                    )
                } ?: emptyList()
                call.respond(ModelsResponse(data = models))
            }
            get("/capabilities") {
                call.authenticate(registry)
                val providerTools = plugins?.enabledTools()?.map { it.toolName }.orEmpty()
                val settings = settingsProvider()
                val activeCtx = engine.modelStatus.value.loadedConfig?.contextWindowTokens ?: settings.contextWindowTokens
                call.respond(
                    CapabilitiesResponse(
                        features = listOf("chat", "streaming", "cancellation", "tool-calling"),
                        maxRequestBytes = config.maxRequestBytes,
                        maxTokensLimit = config.maxTokensLimit,
                        concurrentGenerations = 1,
                        embeddings = false,
                        providerTools = providerTools,
                        contextWindowTokens = activeCtx,
                        defaultMaxTokens = settings.maxOutputTokens,
                    ),
                )
            }
            post("/chat/completions") {
                val client = call.authenticate(registry)
                val clientId = client.id
                val req = call.readJson<ChatCompletionRequest>(config.maxRequestBytes)
                val activeModel = engine.modelStatus.value.model
                val activeModelId = activeModel?.id ?: config.publicModelId
                validateChat(req, config, activeModel)
                if (!client.permissions.chat) throw denied("chat")
                if (req.stream && !client.permissions.streaming) throw denied("streaming")
                val wantsTools = !req.tools.isNullOrEmpty() || req.useProviderTools
                if (wantsTools && !client.permissions.toolCalling) throw denied("tool_calling")

                val clientTools = req.tools.orEmpty()
                val providerTools = if (req.useProviderTools) plugins?.enabledTools().orEmpty() else emptyList()
                val messages = ToolCallingSupport.buildMessages(req.messages, clientTools, providerTools)

                val id = "chatcmpl-" + UUID.randomUUID()
                call.response.header("X-Request-Id", id)
                owners[id] = clientId
                val settings = settingsProvider()
                val effectiveMaxTokens = minOf(
                    req.maxTokens ?: settings.maxOutputTokens,
                    client.permissions.maxTokens,
                    config.maxTokensLimit,
                ).coerceAtLeast(1)
                val params = GenerationParams(
                    temperature = req.temperature ?: settings.temperature,
                    maxTokens = effectiveMaxTokens,
                    topP = req.topP ?: settings.topP,
                    topK = req.topK ?: settings.topK,
                )
                val inference = InferenceRequest(
                    requestId = id, clientId = clientId, modelId = activeModelId,
                    messages = messages, params = params, priority = client.permissions.priority,
                )
                val created = System.currentTimeMillis() / 1000
                try {
                    if (req.stream) streamResponse(engine, inference, activeModelId, config, created)
                    else {
                        val text = withTimeout(config.requestTimeoutMs) { engine.generate(inference) }
                        val clientNames = clientTools.map { it.function.name }.toSet()
                        val providerNames = providerTools.map { it.toolName }.toSet()
                        val parsed = ToolCallingSupport.tryParseToolCall(text, clientNames + providerNames)
                        if (parsed != null && parsed.name in clientNames) {
                            // FR-08A: Client-owned tool — return tool_calls for the client app to execute locally.
                            call.respond(
                                ChatCompletionResponse(
                                    id, "chat.completion", created, activeModelId,
                                    listOf(ChoiceDto(message = MessageDto("assistant", "", toolCalls = listOf(ToolCallingSupport.toDto(parsed))), finishReason = "tool_calls")),
                                ),
                            )
                        } else if (parsed != null && parsed.name in providerNames && plugins != null) {
                            // FR-08B: Provider-owned safe tool — execute in PluginRegistry and synthesize final answer.
                            val toolRes = plugins.executeProviderTool(clientId, ToolCallRequest(parsed.id, parsed.name, parsed.arguments))
                            val followUp = InferenceRequest(
                                requestId = "$id-tool", clientId = clientId, modelId = activeModelId,
                                messages = messages + listOf(
                                    ChatMessage("assistant", text),
                                    ChatMessage("user", "[Tool result for ${parsed.name}]: ${toolRes.content}"),
                                ),
                                params = params, priority = client.permissions.priority,
                            )
                            val finalAnswer = withTimeout(config.requestTimeoutMs) { engine.generate(followUp) }
                            call.respond(
                                ChatCompletionResponse(
                                    id, "chat.completion", created, activeModelId,
                                    listOf(ChoiceDto(message = MessageDto("assistant", finalAnswer), finishReason = "stop")),
                                ),
                            )
                        } else {
                            call.respond(
                                ChatCompletionResponse(
                                    id, "chat.completion", created, activeModelId,
                                    listOf(ChoiceDto(message = MessageDto("assistant", text), finishReason = "stop")),
                                ),
                            )
                        }
                    }
                } finally { owners.remove(id) }
            }
            post("/cancel") {
                val clientId = call.authenticate(registry).id
                val req = call.readJson<CancelRequest>(config.maxRequestBytes)
                val rid = req.requestId?.takeIf { it.isNotBlank() }
                    ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "missing_request_id", "request_id is required")
                val cancelled = owners[rid] == clientId && engine.cancel(rid)
                call.respond(CancelResponse(rid, cancelled))
            }
        }
    }
}

private fun denied(what: String) =
    ApiException(HttpStatusCode.Forbidden, "permission_error", "permission_denied", "This client is not permitted to use $what")

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, type: String, code: String, message: String) {
    respond(status, ErrorResponse(ErrorBody(message, type, code)))
}

private suspend fun ApplicationCall.authenticate(registry: ClientRegistry): AuthenticatedClient {
    val header = request.headers[HttpHeaders.Authorization]
    val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    if (token.isNullOrEmpty()) throw ApiException(HttpStatusCode.Unauthorized, "authentication_error", "missing_token", "Bearer token required")
    return registry.authenticate(token)
        ?: throw ApiException(HttpStatusCode.Unauthorized, "authentication_error", "invalid_token", "Invalid or revoked token")
}

private suspend inline fun <reified T> ApplicationCall.readJson(maxBytes: Int): T {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes)
        throw ApiException(HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds $maxBytes bytes")
    val packet = receiveChannel().readRemaining(maxBytes.toLong() + 1)
    val bytes = packet.readByteArray()
    if (bytes.size > maxBytes)
        throw ApiException(HttpStatusCode.PayloadTooLarge, "invalid_request_error", "request_too_large", "Body exceeds $maxBytes bytes")
    return try { ApiJson.decodeFromString<T>(bytes.decodeToString()) }
    catch (e: Exception) { throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", "invalid_json", "Malformed JSON: ${e.message?.lineSequence()?.first()}") }
}

private fun isAcceptedModelName(
    requested: String?,
    config: ApiConfig,
    activeModel: com.uoai.inference.ModelInfo?,
): Boolean {
    if (requested == null || requested == "auto" || requested == "default") return true
    if (requested == config.publicModelId) return activeModel == null || activeModel.id.startsWith("gemma-3")
    if (activeModel == null) return false
    if (requested == activeModel.id || requested == activeModel.name) return true
    if (requested == "gemma-4" && activeModel.id.startsWith("gemma-4")) return true
    if (requested == "gemma-3" && activeModel.id.startsWith("gemma-3")) return true
    return false
}

private fun validateChat(
    r: ChatCompletionRequest,
    c: ApiConfig,
    activeModel: com.uoai.inference.ModelInfo?,
) {
    fun bad(code: String, msg: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request_error", code, msg)
    if (!isAcceptedModelName(r.model, c, activeModel))
        throw ApiException(HttpStatusCode.NotFound, "invalid_request_error", "model_not_found", "Unknown model '${r.model}'")
    if (r.messages.isEmpty()) bad("missing_messages", "messages must not be empty")
    if (r.messages.any { it.role !in setOf("system", "user", "assistant", "tool") })
        bad("invalid_role", "role must be system, user, assistant or tool")
    if (r.messages.none { it.role == "user" || it.role == "tool" })
        bad("missing_user_message", "at least one user or tool message is required")
    if (r.temperature != null && r.temperature !in 0f..2f) bad("invalid_temperature", "temperature must be within 0..2")
    if (r.maxTokens != null && r.maxTokens !in 1..c.maxTokensLimit) bad("invalid_max_tokens", "max_tokens must be within 1..${c.maxTokensLimit}")
}

private suspend fun RoutingContext.streamResponse(
    engine: InferenceEngine,
    req: InferenceRequest,
    modelId: String,
    config: ApiConfig,
    created: Long,
) {
    val s = engine.modelStatus.value.state
    if (s != ModelState.READY && s != ModelState.GENERATING) throw ModelNotReadyException("Model is not ready (state=$s)")
    call.response.header(HttpHeaders.CacheControl, "no-cache")
    call.respondTextWriter(ContentType.Text.EventStream) {
        fun chunk(delta: DeltaDto, finish: String? = null) =
            ApiJson.encodeToString(ChatCompletionResponse(req.requestId, "chat.completion.chunk", created, modelId,
                listOf(ChoiceDto(delta = delta, finishReason = finish))))
        suspend fun send(data: String) { write("data: $data\n\n"); flush() }
        try {
            withTimeout(config.requestTimeoutMs) {
                send(chunk(DeltaDto(role = "assistant")))
                engine.stream(req).collect { send(chunk(DeltaDto(content = it))) }
            }
            send(chunk(DeltaDto(), "stop"))
            send("[DONE]")
        } catch (e: TimeoutCancellationException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody("Inference timed out", "timeout", "request_timeout", req.requestId))))
        } catch (e: ModelNotReadyException) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Model not ready", "model_unavailable", "model_not_ready", req.requestId))))
        } catch (e: RequestCancelledException) {
            send(chunk(DeltaDto(), "cancelled"))
            send("[DONE]")
        } catch (e: java.io.IOException) {
            engine.cancel(req.requestId)
            throw e
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ApiJson.encodeToString(ErrorResponse(ErrorBody(e.message ?: "Inference failed", "server_error", "inference_failed", req.requestId))))
        }
    }
}
