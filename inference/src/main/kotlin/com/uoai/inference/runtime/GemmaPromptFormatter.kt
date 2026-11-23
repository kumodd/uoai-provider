package com.uoai.inference.runtime

import com.uoai.inference.ChatMessage

/** Gemma instruction-tuned turn format. Gemma has no system role: system text is prepended to the first user turn. */
object GemmaPromptFormatter {
    fun format(messages: List<ChatMessage>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val sb = StringBuilder()
        var firstUser = true
        for (m in messages) {
            when (m.role) {
                "user" -> {
                    sb.append("<start_of_turn>user\n")
                    if (firstUser && system.isNotEmpty()) sb.append(system).append("\n\n")
                    firstUser = false
                    sb.append(m.content).append("<end_of_turn>\n")
                }
                "assistant" -> sb.append("<start_of_turn>model\n").append(m.content).append("<end_of_turn>\n")
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
