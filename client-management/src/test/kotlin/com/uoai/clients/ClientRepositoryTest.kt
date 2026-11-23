package com.uoai.clients

import com.uoai.core.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class FakeClients : ClientDao {
    val rows = linkedMapOf<String, RegisteredClientEntity>()
    var creds: FakeCreds? = null
    override suspend fun insert(c: RegisteredClientEntity) { rows[c.id] = c }
    override suspend fun update(c: RegisteredClientEntity) { rows[c.id] = c }
    override suspend fun byPackage(pkg: String) = rows.values.firstOrNull { it.packageName == pkg }
    override suspend fun byId(id: String) = rows[id]
    override fun observeAll(): Flow<List<RegisteredClientEntity>> = flowOf(rows.values.toList())
    override suspend fun byActiveTokenHash(hash: String) =
        creds!!.rows.firstOrNull { it.tokenHash == hash && it.revokedAt == null }?.let { rows[it.clientId] }
    override suspend fun touch(id: String, now: Long) { rows[id] = rows[id]!!.copy(lastConnectedAt = now) }
}
private class FakeCreds : CredentialDao {
    val rows = mutableListOf<ClientCredentialEntity>()
    override suspend fun insert(c: ClientCredentialEntity) { rows += c }
    override suspend fun revokeAll(clientId: String, now: Long) {
        rows.replaceAll { if (it.clientId == clientId && it.revokedAt == null) it.copy(revokedAt = now) else it }
    }
}
private class FakeAudit : AuditDao {
    val rows = mutableListOf<AuditLogEntity>()
    override suspend fun insert(e: AuditLogEntity) { rows += e }
    override fun observeRecent(limit: Int): Flow<List<AuditLogEntity>> = flowOf(rows)
}

class ClientRepositoryTest {
    private val clients = FakeClients().also { it.creds = FakeCreds() }
    private val audit = FakeAudit()
    private val repo = ClientRepository(clients, clients.creds!!, audit) { 1000L }

    private suspend fun approvedClient(): Pair<String, String> {
        val c = (repo.registerOrGet("com.a", "A", "AA:BB") as RegistrationOutcome.New).client
        repo.approve(c.id)
        return c.id to repo.issueToken(c.id)
    }

    @Test fun newClientIsPendingAndCannotGetToken() = runTest {
        val c = (repo.registerOrGet("com.a", "A", "AA") as RegistrationOutcome.New).client
        assertEquals(ClientStatus.PENDING, c.status)
        try { repo.issueToken(c.id); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun certificateMismatchIsRefused() = runTest {
        repo.registerOrGet("com.a", "A", "AA")
        assertTrue(repo.registerOrGet("com.a", "A", "ZZ") is RegistrationOutcome.CertificateMismatch)
    }

    @Test fun approvedClientAuthenticatesAndRawTokenIsNotStored() = runTest {
        val (id, token) = approvedClient()
        assertEquals(id, repo.authenticate(token)?.id)
        assertTrue(clients.creds!!.rows.none { it.tokenHash == token })
        assertEquals(ClientRepository.hash(token), clients.creds!!.rows.single().tokenHash)
    }

    @Test fun unknownTokenRejected() = runTest { approvedClient(); assertNull(repo.authenticate("uoai_bogus")) }

    @Test fun revokeBlocksAccessImmediately() = runTest {
        val (id, token) = approvedClient()
        repo.revoke(id)
        assertNull(repo.authenticate(token))
        assertEquals("REVOKED", clients.rows[id]!!.status)
    }

    @Test fun disableAndEnable() = runTest {
        val (id, token) = approvedClient()
        repo.setEnabled(id, false); assertNull(repo.authenticate(token))
        repo.setEnabled(id, true); assertNotNull(repo.authenticate(token))
    }

    @Test fun reissuingTokenInvalidatesOldOne() = runTest {
        val (id, old) = approvedClient()
        val new = repo.issueToken(id)
        assertNull(repo.authenticate(old)); assertNotNull(repo.authenticate(new))
    }

    @Test fun permissionsRoundTripAndAuditTrail() = runTest {
        val (id, token) = approvedClient()
        repo.updatePermissions(id, ClientPermissions(streaming = false, maxTokens = 64, priority = 3))
        val p = repo.authenticate(token)!!.permissions
        assertEquals(false, p.streaming); assertEquals(64, p.maxTokens); assertEquals(3, p.priority)
        assertTrue(audit.rows.map { it.action }.containsAll(listOf("client.register", "client.approve", "client.token_issued", "client.permissions")))
    }
}
