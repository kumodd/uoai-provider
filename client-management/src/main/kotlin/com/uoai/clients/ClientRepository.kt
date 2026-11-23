package com.uoai.clients

import com.uoai.core.database.AuditDao
import com.uoai.core.database.AuditLogEntity
import com.uoai.core.database.ClientCredentialEntity
import com.uoai.core.database.ClientDao
import com.uoai.core.database.CredentialDao
import com.uoai.core.database.RegisteredClientEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

sealed interface RegistrationOutcome {
    /** First time this package is seen. Pending until the user decides. */
    data class New(val client: ClientInfo) : RegistrationOutcome
    data class Existing(val client: ClientInfo) : RegistrationOutcome
    /** Same package name, different signing certificate: refuse, possible impersonation. */
    data class CertificateMismatch(val client: ClientInfo) : RegistrationOutcome
}

class ClientRepository(
    private val clients: ClientDao,
    private val credentials: CredentialDao,
    private val audit: AuditDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : ClientRegistry {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeClients(): Flow<List<ClientInfo>> = clients.observeAll().map { l -> l.map { it.toInfo() } }

    suspend fun registerOrGet(packageName: String, appName: String, certDigest: String): RegistrationOutcome {
        val existing = clients.byPackage(packageName)
        if (existing != null) {
            if (existing.signingCertificateDigest != certDigest) {
                log(existing.id, "client.register", packageName, "DENIED", "certificate mismatch")
                return RegistrationOutcome.CertificateMismatch(existing.toInfo())
            }
            return RegistrationOutcome.Existing(existing.toInfo())
        }
        val e = RegisteredClientEntity(
            UUID.randomUUID().toString(), packageName, appName, certDigest, ClientStatus.PENDING.name,
            json.encodeToString(ClientPermissions()), null, null, clock(),
        )
        clients.insert(e)
        log(e.id, "client.register", packageName, "PENDING", null)
        return RegistrationOutcome.New(e.toInfo())
    }

    suspend fun approve(clientId: String, permissions: ClientPermissions? = null) =
        mutate(clientId, "client.approve") {
            it.copy(status = ClientStatus.APPROVED.name, approvedAt = clock(),
                permissionsJson = permissions?.let { p -> json.encodeToString(p) } ?: it.permissionsJson)
        }

    suspend fun reject(clientId: String) = mutate(clientId, "client.reject") { it.copy(status = ClientStatus.REJECTED.name) }

    /** Marks the client REVOKED and invalidates every credential immediately. */
    suspend fun revoke(clientId: String) {
        mutate(clientId, "client.revoke") { it.copy(status = ClientStatus.REVOKED.name) }
        credentials.revokeAll(clientId, clock())
    }

    suspend fun setEnabled(clientId: String, enabled: Boolean) {
        val c = clients.byId(clientId) ?: return
        val cur = ClientStatus.valueOf(c.status)
        val next = when {
            enabled && cur == ClientStatus.DISABLED -> ClientStatus.APPROVED
            !enabled && cur == ClientStatus.APPROVED -> ClientStatus.DISABLED
            else -> return
        }
        mutate(clientId, if (enabled) "client.enable" else "client.disable") { it.copy(status = next.name) }
    }

    suspend fun updatePermissions(clientId: String, permissions: ClientPermissions) =
        mutate(clientId, "client.permissions") { it.copy(permissionsJson = json.encodeToString(permissions)) }

    /** Replaces all existing credentials with a new one and returns the raw token (shown once, never stored). */
    suspend fun issueToken(clientId: String): String {
        val c = clients.byId(clientId)
        require(c != null && c.status == ClientStatus.APPROVED.name) { "client is not approved" }
        credentials.revokeAll(clientId, clock())
        val raw = "uoai_" + ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        credentials.insert(ClientCredentialEntity(UUID.randomUUID().toString(), clientId, hash(raw), clock(), null))
        log(clientId, "client.token_issued", c.packageName, "OK", null)
        return raw
    }

    override suspend fun authenticate(bearerToken: String): AuthenticatedClient? {
        val c = clients.byActiveTokenHash(hash(bearerToken)) ?: return null
        if (c.status != ClientStatus.APPROVED.name) return null
        clients.touch(c.id, clock())
        return AuthenticatedClient(c.id, c.toInfo().permissions)
    }

    private suspend fun mutate(id: String, action: String, f: (RegisteredClientEntity) -> RegisteredClientEntity) {
        val c = clients.byId(id) ?: return
        val n = f(c)
        clients.update(n)
        log(id, action, c.packageName, n.status, null)
    }

    private suspend fun log(clientId: String?, action: String, resource: String?, status: String, details: String?) =
        audit.insert(AuditLogEntity(clientId = clientId, action = action, resource = resource, status = status,
            detailsJson = details?.let { json.encodeToString(mapOf("detail" to it)) }, createdAt = clock()))

    private fun RegisteredClientEntity.toInfo() = ClientInfo(
        id, packageName, appName, signingCertificateDigest,
        runCatching { ClientStatus.valueOf(status) }.getOrDefault(ClientStatus.DISABLED),
        runCatching { json.decodeFromString<ClientPermissions>(permissionsJson) }.getOrDefault(ClientPermissions()),
        approvedAt, lastConnectedAt, createdAt,
    )

    companion object {
        fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
