package com.uoai.plugins

import com.uoai.core.database.*
import com.uoai.plugins.models.ToolCallRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

private class FakePluginDao : PluginDao {
    val plugins = linkedMapOf<String, PluginEntity>()
    val tools = linkedMapOf<String, PluginToolEntity>()
    override suspend fun upsertPlugin(p: PluginEntity) { plugins[p.id] = p }
    override suspend fun upsertTool(t: PluginToolEntity) { tools[t.id] = t }
    override fun observePlugins(): Flow<List<PluginEntity>> = flowOf(plugins.values.toList())
    override suspend fun allPlugins(): List<PluginEntity> = plugins.values.toList()
    override fun observeTools(): Flow<List<PluginToolEntity>> = flowOf(tools.values.toList())
    override suspend fun enabledTools(): List<PluginToolEntity> = tools.values.filter { it.enabled }
    override suspend fun toolByName(name: String): PluginToolEntity? = tools.values.firstOrNull { it.name == name }
    override suspend fun pluginById(id: String): PluginEntity? = plugins[id]
    override suspend fun setPluginEnabled(id: String, enabled: Boolean, now: Long) {
        plugins[id] = plugins[id]!!.copy(enabled = enabled, updatedAt = now)
    }
    override suspend fun setToolEnabled(id: String, enabled: Boolean) {
        tools[id] = tools[id]!!.copy(enabled = enabled)
    }
}

private class FakeAuditDao : AuditDao {
    val rows = mutableListOf<AuditLogEntity>()
    override suspend fun insert(e: AuditLogEntity) { rows += e }
    override fun observeRecent(limit: Int): Flow<List<AuditLogEntity>> = flowOf(rows)
}

class PluginManagerTest {
    private val pDao = FakePluginDao()
    private val aDao = FakeAuditDao()
    private val mgr = PluginManager(pDao, aDao)

    @Test fun calculatorEvaluatesSafeExpressionsAndRejectsCodeInjection() = runTest {
        val ok = SafeCalculatorHandler.execute(buildJsonObject { put("expression", "(12.5 * 4) + 3") })
        assertEquals("53", ok.getOrThrow())

        val divZero = SafeCalculatorHandler.execute(buildJsonObject { put("expression", "10 / 0") })
        assertTrue(divZero.isFailure)

        val injection = SafeCalculatorHandler.execute(buildJsonObject { put("expression", "Runtime.getRuntime().exec(\"ls\")") })
        assertTrue(injection.isFailure)
    }

    @Test fun executesEnabledProviderToolAndAudits() = runTest {
        val res = mgr.executeProviderTool("client1", ToolCallRequest("c1", "calculator", buildJsonObject { put("expression", "7 * 8") }))
        assertFalse(res.isError)
        assertEquals("56", res.content)
        assertTrue(aDao.rows.any { it.action == "tool.execute" && it.status == "OK" && it.clientId == "client1" })
    }

    @Test fun rejectsDisabledOrUnknownTools() = runTest {
        mgr.ensureDefaultsSeeded()
        mgr.setToolEnabled("builtin.utilities:calculator", false)
        val disabled = mgr.executeProviderTool("client1", ToolCallRequest("c2", "calculator", buildJsonObject { put("expression", "1+1") }))
        assertTrue(disabled.isError)
        assertTrue(disabled.content.contains("disabled"))

        val unknown = mgr.executeProviderTool("client1", ToolCallRequest("c3", "shell_exec", buildJsonObject {}))
        assertTrue(unknown.isError)
        assertTrue(aDao.rows.any { it.status.startsWith("DENIED:") })
    }
}
