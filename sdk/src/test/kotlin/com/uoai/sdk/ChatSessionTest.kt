package com.uoai.sdk

import com.uoai.sdk.exceptions.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    private fun mockClient(): UniversalAIClient = mockk(relaxed = true) {
        every { config } returns ClientConfig()
    }

    @Test
    fun clientConfigDefaultsToAutoModel() {
        val cfg = ClientConfig()
        assertEquals("auto", cfg.model)
    }

    @Test
    fun sendAccumulatesMultiTurnHistoryWithSystemPrompt() = runTest {
        val client = mockClient()
        val captured = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(captured), any(), any(), any(), any(), any()) } returnsMany listOf(
            GenerationResult("Hi there!", "req-1"),
            GenerationResult("4", "req-2"),
        )

        val session = ChatSession(client, systemPrompt = "Be brief.", model = "auto")
        val r1 = session.send("Hello")
        val r2 = session.send("What is 2+2?")

        assertEquals("Hi there!", r1.text)
        assertEquals("4", r2.text)
        assertEquals(
            listOf(
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "Hi there!"),
                ChatMessage("user", "What is 2+2?"),
                ChatMessage("assistant", "4"),
            ),
            session.messages,
        )
        assertEquals(
            listOf(
                ChatMessage("system", "Be brief."),
                ChatMessage("user", "Hello"),
            ),
            captured[0],
        )
        assertEquals(
            listOf(
                ChatMessage("system", "Be brief."),
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "Hi there!"),
                ChatMessage("user", "What is 2+2?"),
            ),
            captured[1],
        )
    }

    @Test
    fun sendStreamAccumulatesAndTrimsHistoryWindowPreservingLatestUser() = runTest {
        val client = mockClient()
        val captured = mutableListOf<List<ChatMessage>>()
        every { client.chatStream(capture(captured), any(), any(), any()) } returnsMany listOf(
            flow { emit("A"); emit("1") },
            flow { emit("A"); emit("2") },
            flow { emit("A"); emit("3") },
        )

        val session = ChatSession(client, systemPrompt = "Sys", model = "auto", maxHistoryMessages = 3)
        assertEquals(listOf("A", "1"), session.sendStream("U1").toList())
        assertEquals(listOf("A", "2"), session.sendStream("U2").toList())
        assertEquals(listOf("A", "3"), session.sendStream("U3").toList())

        assertEquals(
            listOf(
                ChatMessage("system", "Sys"),
                ChatMessage("user", "U2"),
                ChatMessage("assistant", "A2"),
                ChatMessage("user", "U3"),
            ),
            captured[2],
        )
    }

    @Test
    fun streamErrorWithZeroTokensRollsBackDanglingUserMessage() = runTest {
        val client = mockClient()
        coEvery { client.chat(any(), any(), any(), any(), any(), any()) } returns GenerationResult("First", "req-0")
        every { client.chatStream(any(), any(), any(), any()) } returns flow {
            throw ProviderNotRunningException("down")
        }

        val session = ChatSession(client)
        session.send("Hello")

        val thrown = runCatching { session.sendStream("Will fail").toList() }.exceptionOrNull()
        assertTrue(thrown is ProviderNotRunningException)
        assertEquals(
            listOf(
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "First"),
            ),
            session.messages,
        )
    }

    @Test
    fun streamCancellationWithPartialTokensPreservesPartialAssistantReply() = runTest {
        val client = mockClient()
        every { client.chatStream(any(), any(), any(), any()) } returns flow {
            emit("Partial ")
            emit("response")
            throw RequestCancelledException("cancelled")
        }

        val session = ChatSession(client)
        val thrown = runCatching { session.sendStream("Explain").toList() }.exceptionOrNull()
        assertTrue(thrown is RequestCancelledException)
        assertEquals(
            listOf(
                ChatMessage("user", "Explain"),
                ChatMessage("assistant", "Partial response"),
            ),
            session.messages,
        )

        // Also verify downstream coroutine cancellation after partial emission keeps emitted tokens balanced
        every { client.chatStream(any(), any(), any(), any()) } returns flow {
            emit("Chunk1")
            emit("Chunk2")
        }
        val chunks = session.sendStream("Next").take(1).toList()
        assertEquals(listOf("Chunk1"), chunks)
        assertEquals(
            listOf(
                ChatMessage("user", "Explain"),
                ChatMessage("assistant", "Partial response"),
                ChatMessage("user", "Next"),
                ChatMessage("assistant", "Chunk1"),
            ),
            session.messages,
        )
    }

    @Test
    fun apiClientMapErrorMapsStructuredProviderErrors() {
        val api = ApiClient(ClientConfig()) { null }

        assertTrue(api.mapError(401, """{"error":{"code":"missing_token","message":"Need auth"}}""") is NotRegisteredException)
        assertTrue(api.mapError(401, """{"error":{"code":"invalid_token","message":"Revoked"}}""") is NotRegisteredException)
        assertTrue(api.mapError(503, """{"error":{"code":"model_not_ready","message":"Loading"}}""") is ModelNotReadyException)
        assertTrue(api.mapError(403, """{"error":{"code":"permission_denied","message":"No chat"}}""") is PermissionDeniedException)
        assertTrue(api.mapError(409, """{"error":{"code":"request_cancelled","message":"Aborted"}}""") is RequestCancelledException)

        val other = api.mapError(429, """{"error":{"code":"queue_full","message":"Busy"}}""")
        assertTrue(other is ApiException)
        other as ApiException
        assertEquals(429, other.httpStatus)
        assertEquals("queue_full", other.code)
    }
}
