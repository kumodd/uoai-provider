package com.uoai.plugins

import com.uoai.plugins.models.RegisteredPlugin
import com.uoai.plugins.models.ToolCallRequest
import com.uoai.plugins.models.ToolCallResult
import kotlinx.coroutines.flow.Flow

/**
 * Registry and executor for provider-owned plugins and tools (FR-08).
 * Strictly executes pre-registered [SafeToolHandler] instances; never executes arbitrary code from JSON.
 */
interface PluginRegistry {
    fun observePlugins(): Flow<List<RegisteredPlugin>>
    suspend fun ensureDefaultsSeeded()
    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean)
    suspend fun setToolEnabled(toolId: String, enabled: Boolean)
    suspend fun enabledTools(): List<SafeToolHandler>
    suspend fun executeProviderTool(clientId: String?, request: ToolCallRequest): ToolCallResult
}
