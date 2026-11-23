package com.uoai.providerapi

import com.uoai.inference.ChatMessage
import com.uoai.plugins.SafeToolHandler
import com.uoai.providerapi.dto.FunctionCallDto
import com.uoai.providerapi.dto.MessageDto
import com.uoai.providerapi.dto.ToolCallDto
import com.uoai.providerapi.dto.ToolSpecDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Formats tool definitions into Gemma system instructions and parses structured `<tool_call>` blocks
 * emitted by the model. Supports both client-owned tools (returned to the caller as `tool_calls`) and
 * provider-owned tools (executed locally via [com.uoai.plugins.PluginRegistry]).
 */
object ToolCallingSupport {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagRegex = Regex("""<tool_call>\s*(\{.*?\})\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
    private val rawJsonRegex = Regex("""\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*(\{.*?\})\s*\}""", RegexOption.DOT_MATCHES_ALL)

    data class ParsedCall(val id: String, val name: String, val arguments: JsonObject)

    fun buildMessages(
        messages: List<MessageDto>,
        clientTools: List<ToolSpecDto>,
        providerTools: List<SafeToolHandler>,
    ): List<ChatMessage> {
        val hasTools = clientTools.isNotEmpty() || providerTools.isNotEmpty()
        val toolInstruction = if (!hasTools) null else buildString {
            append("You have access to the following tools:\n")
            for (t in clientTools) {
                append("- ").append(t.function.name).append(": ").append(t.function.description ?: "")
                t.function.parameters?.let { append(" | parameters: ").append(it) }
                append("\n")
            }
            for (p in providerTools) {
                append("- ").append(p.toolName).append(": ").append(p.description)
                    .append(" | parameters: ").append(p.inputSchemaJson).append("\n")
            }
            append(
                "When you need to call a tool, respond ONLY with:\n" +
                    "<tool_call>{\"name\":\"<tool_name>\",\"arguments\":{...}}</tool_call>\n" +
                    "Wait for the tool result before answering the user.",
            )
        }
        val out = mutableListOf<ChatMessage>()
        if (toolInstruction != null) out += ChatMessage("system", toolInstruction)
        for (m in messages) {
            when (m.role) {
                "tool" -> out += ChatMessage("user", "[Tool result for ${m.name ?: m.toolCallId ?: "tool"}]: ${m.content}")
                else -> out += ChatMessage(m.role, m.content)
            }
        }
        return out
    }

    fun tryParseToolCall(modelOutput: String, allowedNames: Set<String>): ParsedCall? {
        val candidates = tagRegex.findAll(modelOutput).map { it.groupValues[1] } +
            rawJsonRegex.findAll(modelOutput).map { it.value }
        for (candidate in candidates) {
            val parsed = runCatching {
                val obj = json.parseToJsonElement(candidate).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@runCatching null
                if (name !in allowedNames) return@runCatching null
                val args = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                ParsedCall("call_" + UUID.randomUUID().toString().take(8), name, args)
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun toDto(call: ParsedCall): ToolCallDto =
        ToolCallDto(id = call.id, function = FunctionCallDto(name = call.name, arguments = call.arguments.toString()))
}
