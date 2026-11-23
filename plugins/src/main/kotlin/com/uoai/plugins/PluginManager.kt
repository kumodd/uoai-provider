package com.uoai.plugins

import com.uoai.core.database.AuditDao
import com.uoai.core.database.AuditLogEntity
import com.uoai.core.database.PluginDao
import com.uoai.core.database.PluginEntity
import com.uoai.core.database.PluginToolEntity
import com.uoai.plugins.models.RegisteredPlugin
import com.uoai.plugins.models.RegisteredTool
import com.uoai.plugins.models.ToolCallRequest
import com.uoai.plugins.models.ToolCallResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PluginManager(
    private val pluginDao: PluginDao,
    private val auditDao: AuditDao,
    handlers: List<SafeToolHandler> = listOf(SafeCalculatorHandler, DeviceInfoHandler),
    private val clock: () -> Long = System::currentTimeMillis,
) : PluginRegistry {

    private val handlerByToolName: Map<String, SafeToolHandler> = handlers.associateBy { it.toolName }

    override fun observePlugins(): Flow<List<RegisteredPlugin>> =
        combine(pluginDao.observePlugins(), pluginDao.observeTools()) { plugins, tools ->
            val byPlugin = tools.groupBy { it.pluginId }
            plugins.map { p ->
                RegisteredPlugin(
                    id = p.id, name = p.name, version = p.version, description = p.description ?: "",
                    adapterType = p.adapterType, enabled = p.enabled,
                    tools = byPlugin[p.id].orEmpty().map { t ->
                        RegisteredTool(t.id, t.pluginId, t.name, t.description ?: "", t.inputSchema, t.enabled)
                    },
                )
            }
        }

    override suspend fun ensureDefaultsSeeded() {
        val now = clock()
        if (pluginDao.pluginById("builtin.utilities") == null) {
            pluginDao.upsertPlugin(
                PluginEntity(
                    id = "builtin.utilities",
                    name = "Built-in Offline Utilities",
                    version = "1.0.0",
                    description = "Predefined safe provider-owned tools (calculator, device_info).",
                    adapterType = "PREDEFINED_KOTLIN",
                    configJson = """{"sandbox":"strict","arbitrary_code":false}""",
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            for (h in handlerByToolName.values.filter { it.pluginId == "builtin.utilities" }) {
                pluginDao.upsertTool(
                    PluginToolEntity(
                        id = "${h.pluginId}:${h.toolName}",
                        pluginId = h.pluginId,
                        name = h.toolName,
                        description = h.description,
                        inputSchema = h.inputSchemaJson,
                        executionConfig = """{"confirmation":"none"}""",
                        enabled = true,
                    ),
                )
            }
        }
    }

    override suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginDao.setPluginEnabled(pluginId, enabled, clock())
        audit(null, if (enabled) "plugin.enable" else "plugin.disable", pluginId, "OK", null)
    }

    override suspend fun setToolEnabled(toolId: String, enabled: Boolean) {
        pluginDao.setToolEnabled(toolId, enabled)
        audit(null, if (enabled) "tool.enable" else "tool.disable", toolId, "OK", null)
    }

    override suspend fun enabledTools(): List<SafeToolHandler> {
        ensureDefaultsSeeded()
        val plugins = pluginDao.allPlugins().filter { it.enabled }.map { it.id }.toSet()
        return pluginDao.enabledTools()
            .filter { it.pluginId in plugins }
            .mapNotNull { handlerByToolName[it.name] }
    }

    override suspend fun executeProviderTool(clientId: String?, request: ToolCallRequest): ToolCallResult {
        ensureDefaultsSeeded()
        val handler = handlerByToolName[request.name]
            ?: return deny(clientId, request, "unknown_tool", "Tool '${request.name}' is not a registered provider tool")
        val toolEntity = pluginDao.toolByName(request.name)
        val pluginEntity = pluginDao.pluginById(handler.pluginId)
        if (toolEntity == null || !toolEntity.enabled || pluginEntity == null || !pluginEntity.enabled) {
            return deny(clientId, request, "tool_disabled", "Tool '${request.name}' is disabled")
        }
        return handler.execute(request.arguments).fold(
            onSuccess = { out ->
                audit(clientId, "tool.execute", request.name, "OK", out)
                ToolCallResult(request.id, request.name, out, isError = false)
            },
            onFailure = { err ->
                audit(clientId, "tool.execute", request.name, "ERROR", err.message)
                ToolCallResult(request.id, request.name, "Error: ${err.message}", isError = true)
            },
        )
    }

    private suspend fun deny(clientId: String?, req: ToolCallRequest, code: String, msg: String): ToolCallResult {
        audit(clientId, "tool.execute", req.name, "DENIED:$code", msg)
        return ToolCallResult(req.id, req.name, msg, isError = true)
    }

    private suspend fun audit(clientId: String?, action: String, resource: String?, status: String, detail: String?) {
        auditDao.insert(
            AuditLogEntity(
                clientId = clientId, action = action, resource = resource, status = status,
                detailsJson = detail?.let { Json.encodeToString(mapOf("detail" to it.take(300))) },
                createdAt = clock(),
            ),
        )
    }
}
