package com.uoai.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ClientUsageRow(
    @androidx.room.ColumnInfo(name = "client_id") val clientId: String,
    val total: Int,
    val failed: Int,
)

@Dao
interface ClientDao {
    @Insert suspend fun insert(c: RegisteredClientEntity)
    @Update suspend fun update(c: RegisteredClientEntity)
    @Query("SELECT * FROM registered_clients WHERE package_name = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): RegisteredClientEntity?
    @Query("SELECT * FROM registered_clients WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): RegisteredClientEntity?
    @Query("SELECT * FROM registered_clients ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RegisteredClientEntity>>
    @Query("""SELECT c.* FROM registered_clients c JOIN client_credentials k ON k.client_id = c.id
              WHERE k.token_hash = :hash AND k.revoked_at IS NULL LIMIT 1""")
    suspend fun byActiveTokenHash(hash: String): RegisteredClientEntity?
    @Query("UPDATE registered_clients SET last_connected_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)
}

@Dao
interface CredentialDao {
    @Insert suspend fun insert(c: ClientCredentialEntity)
    @Query("UPDATE client_credentials SET revoked_at = :now WHERE client_id = :clientId AND revoked_at IS NULL")
    suspend fun revokeAll(clientId: String, now: Long)
}

@Dao
interface InferenceRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: InferenceRequestEntity)
    @Query("SELECT * FROM inference_requests ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<InferenceRequestEntity>>
    @Query("SELECT * FROM inference_requests WHERE request_status = 'FAILED' ORDER BY created_at DESC LIMIT :limit")
    fun observeRecentErrors(limit: Int): Flow<List<InferenceRequestEntity>>
    @Query("""SELECT client_id, COUNT(*) AS total, SUM(CASE WHEN request_status = 'FAILED' THEN 1 ELSE 0 END) AS failed
              FROM inference_requests GROUP BY client_id""")
    fun observeUsage(): Flow<List<ClientUsageRow>>
    /** Requests left QUEUED/RUNNING by a killed process can never finish. */
    @Query("""UPDATE inference_requests SET request_status = 'FAILED', completed_at = :now,
              error_message = 'Provider process terminated' WHERE request_status IN ('QUEUED','RUNNING')""")
    suspend fun failStale(now: Long): Int
    @Query("DELETE FROM inference_requests WHERE created_at < :before")
    suspend fun prune(before: Long)
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(e: AuditLogEntity)
    @Query("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>
}

@Dao
interface ModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(m: ModelEntity)
    @Query("SELECT * FROM models ORDER BY created_at DESC") fun observeAll(): Flow<List<ModelEntity>>
    @Query("DELETE FROM models WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface PluginDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPlugin(p: PluginEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTool(t: PluginToolEntity)
    @Query("SELECT * FROM plugins ORDER BY name ASC") fun observePlugins(): Flow<List<PluginEntity>>
    @Query("SELECT * FROM plugins") suspend fun allPlugins(): List<PluginEntity>
    @Query("SELECT * FROM plugin_tools ORDER BY name ASC") fun observeTools(): Flow<List<PluginToolEntity>>
    @Query("SELECT * FROM plugin_tools WHERE enabled = 1") suspend fun enabledTools(): List<PluginToolEntity>
    @Query("SELECT * FROM plugin_tools WHERE name = :name LIMIT 1") suspend fun toolByName(name: String): PluginToolEntity?
    @Query("SELECT * FROM plugins WHERE id = :id LIMIT 1") suspend fun pluginById(id: String): PluginEntity?
    @Query("UPDATE plugins SET enabled = :enabled, updated_at = :now WHERE id = :id") suspend fun setPluginEnabled(id: String, enabled: Boolean, now: Long)
    @Query("UPDATE plugin_tools SET enabled = :enabled WHERE id = :id") suspend fun setToolEnabled(id: String, enabled: Boolean)
}

@Dao
interface PermissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PermissionEntity)
    @Query("SELECT * FROM permissions WHERE client_id = :clientId") suspend fun forClient(clientId: String): List<PermissionEntity>
    @Query("SELECT * FROM permissions") fun observeAll(): Flow<List<PermissionEntity>>
}
