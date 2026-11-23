package com.uoai.plugins.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema object describing arguments. */
    val parameters: JsonObject,
)

@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    /** Raw JSON arguments object or stringified JSON. */
    val arguments: JsonObject,
)

@Serializable
data class ToolCallResult(
    @SerialName("tool_call_id") val toolCallId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
)

enum class ConfirmationPolicy { NONE, LOG_ONLY, REQUIRE_OPT_IN }

data class RegisteredPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val adapterType: String,
    val enabled: Boolean,
    val tools: List<RegisteredTool>,
)

data class RegisteredTool(
    val id: String,
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val enabled: Boolean,
    val requiresConfirmation: Boolean = false,
)
