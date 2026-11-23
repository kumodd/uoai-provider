package com.uoai.plugins

import kotlinx.serialization.json.JsonObject

/**
 * Safe predefined tool handler for provider-owned tools (FR-08B).
 * Never executes dynamic or arbitrary code from JSON.
 */
interface SafeToolHandler {
    val pluginId: String
    val toolName: String
    val description: String
    val inputSchemaJson: String
    suspend fun execute(arguments: JsonObject): Result<String>
}
