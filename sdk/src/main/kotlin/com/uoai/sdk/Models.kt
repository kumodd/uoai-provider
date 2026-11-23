package com.uoai.sdk

import android.content.Intent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** FR-07 client configuration. */
@Serializable
data class ClientConfig(
    val provider: String = "universal.offline.ai",
    val connection: String = "http",
    val baseUrl: String = "http://127.0.0.1:8765/v1",
    val model: String = "auto",
    val features: List<String> = listOf("chat", "streaming", "tool-calling"),
    val providerPackage: String = "com.uoai.provider",
    val requestTimeoutMs: Int = 300_000,
)

@Serializable
data class ClientToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object describing arguments. */
    val parameters: JsonObject,
)

@Serializable
data class ClientToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
)

data class GenerationResult(
    val text: String,
    val requestId: String?,
    val toolCalls: List<ClientToolCall> = emptyList(),
)

@Serializable data class ProviderHealth(
    val status: String,
    @SerialName("model_state") val modelState: String,
    @SerialName("model_loaded") val modelLoaded: Boolean,
    @SerialName("model_error") val modelError: String? = null,
) { val isReady get() = modelLoaded }

@Serializable data class ModelInfo(
    val id: String, val name: String? = null, val format: String? = null, val quantization: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null, val ready: Boolean = false,
    @SerialName("context_window") val contextWindow: Int? = null,
)

@Serializable data class Capabilities(
    val features: List<String>,
    @SerialName("max_request_bytes") val maxRequestBytes: Int,
    @SerialName("max_tokens_limit") val maxTokensLimit: Int,
    @SerialName("concurrent_generations") val concurrentGenerations: Int,
    val embeddings: Boolean,
    @SerialName("provider_tools") val providerTools: List<String> = emptyList(),
    @SerialName("context_window_tokens") val contextWindowTokens: Int? = null,
    @SerialName("default_max_tokens") val defaultMaxTokens: Int? = null,
)

sealed interface RegistrationResult {
    data object Approved : RegistrationResult
    data class Denied(val code: String, val message: String) : RegistrationResult
}

sealed interface ConnectionState {
    data object ProviderNotInstalled : ConnectionState
    data class NeedsRegistration(val registrationIntent: Intent) : ConnectionState
    data class ProviderNotRunning(val launchProviderIntent: Intent?, val message: String) : ConnectionState
    data class ModelNotReady(
        val modelState: String,
        val launchProviderIntent: Intent?,
        val message: String,
    ) : ConnectionState
    data class Connected(
        val health: ProviderHealth,
        val activeModel: ModelInfo?,
        val capabilities: Capabilities,
    ) : ConnectionState
    data class Error(val message: String, val cause: Throwable? = null) : ConnectionState
}
