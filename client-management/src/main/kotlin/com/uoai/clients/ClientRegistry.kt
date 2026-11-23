package com.uoai.clients

import kotlinx.serialization.Serializable

enum class ClientStatus { PENDING, APPROVED, REJECTED, REVOKED, DISABLED }

/** Per-client policy (stored as permissions_json). */
@Serializable
data class ClientPermissions(
    val chat: Boolean = true,
    val streaming: Boolean = true,
    val toolCalling: Boolean = false,
    val maxTokens: Int = 4096,
    /** Scheduler priority; higher runs first. */
    val priority: Int = 0,
) {
    companion object {
        val TOKEN_PRESETS = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
    }
}

data class ClientInfo(
    val id: String,
    val packageName: String,
    val appName: String,
    val certDigest: String,
    val status: ClientStatus,
    val permissions: ClientPermissions,
    val approvedAt: Long?,
    val lastConnectedAt: Long?,
    val createdAt: Long,
)

data class AuthenticatedClient(val id: String, val permissions: ClientPermissions)

interface ClientRegistry {
    /** @return the client for a valid token of an APPROVED client, else null. */
    suspend fun authenticate(bearerToken: String): AuthenticatedClient?
}
