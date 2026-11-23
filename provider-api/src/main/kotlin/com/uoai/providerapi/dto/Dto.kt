package com.uoai.providerapi.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FunctionSpecDto(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class ToolSpecDto(
    val type: String = "function",
    val function: FunctionSpecDto,
)

@Serializable
data class FunctionCallDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: FunctionCallDto,
)

@Serializable
data class MessageDto(
    val role: String,
    val content: String = "",
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    val stream: Boolean = false,
    /** Client-supplied tool definitions (FR-08A). */
    val tools: List<ToolSpecDto>? = null,
    /** Opt-in to also allow enabled provider-owned tools (FR-08B). */
    @SerialName("use_provider_tools") val useProviderTools: Boolean = false,
)

@Serializable
data class ChoiceDto(
    val index: Int = 0,
    val message: MessageDto? = null,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable data class DeltaDto(val role: String? = null, val content: String? = null)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val obj: String,
    val created: Long,
    val model: String,
    val choices: List<ChoiceDto>,
)

@Serializable data class ErrorBody(val message: String, val type: String, val code: String, @SerialName("request_id") val requestId: String? = null)
@Serializable data class ErrorResponse(val error: ErrorBody)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("model_state") val modelState: String,
    @SerialName("model_loaded") val modelLoaded: Boolean,
    @SerialName("model_error") val modelError: String? = null,
    @SerialName("api_version") val apiVersion: String = "v1",
)

@Serializable
data class ModelDto(
    val id: String,
    @SerialName("object") val obj: String = "model",
    @SerialName("owned_by") val ownedBy: String = "universal-offline-ai",
    val name: String? = null, val format: String? = null, val quantization: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null, val ready: Boolean,
    @SerialName("context_window") val contextWindow: Int? = null,
)
@Serializable data class ModelsResponse(@SerialName("object") val obj: String = "list", val data: List<ModelDto>)

@Serializable
data class CapabilitiesResponse(
    val features: List<String>,
    @SerialName("max_request_bytes") val maxRequestBytes: Int,
    @SerialName("max_tokens_limit") val maxTokensLimit: Int,
    @SerialName("concurrent_generations") val concurrentGenerations: Int,
    val embeddings: Boolean,
    @SerialName("provider_tools") val providerTools: List<String> = emptyList(),
    @SerialName("context_window_tokens") val contextWindowTokens: Int = 4096,
    @SerialName("default_max_tokens") val defaultMaxTokens: Int = 2048,
)

@Serializable data class CancelRequest(@SerialName("request_id") val requestId: String? = null)
@Serializable data class CancelResponse(@SerialName("request_id") val requestId: String, val cancelled: Boolean)
