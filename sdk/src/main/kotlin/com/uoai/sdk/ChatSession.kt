package com.uoai.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stateful multi-turn chat helper that preserves local conversation history, prepends an optional
 * system prompt, enforces a bounded sliding window, and keeps turns balanced on failures/cancellations.
 */
class ChatSession internal constructor(
    private val client: UniversalAIClient,
    val systemPrompt: String? = null,
    val model: String = client.config.model,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val maxHistoryMessages: Int = 40,
) {
    private val lock = Any()
    private val _messages = mutableListOf<ChatMessage>()

    val messages: List<ChatMessage>
        get() = synchronized(lock) { _messages.toList() }

    /** Sends a single user turn (non-streaming) and appends both turns to local history on success. */
    suspend fun send(userText: String): GenerationResult {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Message must not be blank" }
        val (userMsg, requestMessages) = beginTurn(trimmed)
        return try {
            val result = client.chat(requestMessages, model, temperature, maxTokens)
            finishOrRollbackTurn(userMsg, result.text)
            result
        } catch (t: Throwable) {
            finishOrRollbackTurn(userMsg, "")
            throw t
        }
    }

    /**
     * Streams assistant tokens for [userText]. Keeps partial assistant output in history if cancelled
     * mid-stream, or removes the dangling user turn if zero tokens were received.
     */
    fun sendStream(userText: String): Flow<String> {
        val trimmed = userText.trim()
        require(trimmed.isNotEmpty()) { "Message must not be blank" }
        return flow {
            val (userMsg, requestMessages) = beginTurn(trimmed)
            val sb = StringBuilder()
            try {
                client.chatStream(requestMessages, model, temperature, maxTokens).collect { chunk ->
                    sb.append(chunk)
                    emit(chunk)
                }
                finishOrRollbackTurn(userMsg, sb.toString())
            } catch (t: Throwable) {
                finishOrRollbackTurn(userMsg, sb.toString())
                throw t
            }
        }
    }

    fun restoreHistory(history: List<ChatMessage>) = synchronized(lock) {
        _messages.clear()
        _messages.addAll(history)
    }

    fun clear() = synchronized(lock) {
        _messages.clear()
    }

    private fun beginTurn(trimmedUserText: String): Pair<ChatMessage, List<ChatMessage>> = synchronized(lock) {
        val history = if (maxHistoryMessages > 1) _messages.takeLast(maxHistoryMessages - 1) else emptyList()
        val userMsg = ChatMessage(role = "user", content = trimmedUserText)
        _messages.add(userMsg)
        val requestMessages = buildList {
            systemPrompt?.takeIf { it.isNotBlank() }?.let { add(ChatMessage(role = "system", content = it)) }
            addAll(history)
            add(userMsg)
        }
        userMsg to requestMessages
    }

    private fun finishOrRollbackTurn(userMsg: ChatMessage, partialOrFullText: String) = synchronized(lock) {
        if (_messages.lastOrNull() !== userMsg) return@synchronized
        if (partialOrFullText.isNotEmpty()) {
            _messages.add(ChatMessage(role = "assistant", content = partialOrFullText))
        } else {
            _messages.removeAt(_messages.lastIndex)
        }
    }
}
