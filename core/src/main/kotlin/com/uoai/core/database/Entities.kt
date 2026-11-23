package com.uoai.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "registered_clients", indices = [Index("package_name", unique = true)])
data class RegisteredClientEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "app_name") val appName: String,
    @ColumnInfo(name = "signing_certificate_digest") val signingCertificateDigest: String,
    val status: String,
    @ColumnInfo(name = "permissions_json") val permissionsJson: String,
    @ColumnInfo(name = "approved_at") val approvedAt: Long?,
    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "client_credentials",
    foreignKeys = [ForeignKey(RegisteredClientEntity::class, ["id"], ["client_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("client_id"), Index("token_hash", unique = true)],
)
data class ClientCredentialEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    /** SHA-256 of the high-entropy token. The raw token is never stored. */
    @ColumnInfo(name = "token_hash") val tokenHash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "revoked_at") val revokedAt: Long?,
)

@Entity(tableName = "inference_requests", indices = [Index("client_id"), Index("created_at")])
data class InferenceRequestEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "request_status") val requestStatus: String,
    /** Metadata only (parameters, message count). Prompt text is deliberately not retained. */
    @ColumnInfo(name = "request_json") val requestJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "model_name") val modelName: String,
    @ColumnInfo(name = "model_version") val modelVersion: String?,
    @ColumnInfo(name = "model_path") val modelPath: String,
    @ColumnInfo(name = "model_format") val modelFormat: String?,
    val quantization: String?,
    @ColumnInfo(name = "file_size") val fileSize: Long?,
    @ColumnInfo(name = "installation_status") val installationStatus: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "plugins")
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val description: String?,
    @ColumnInfo(name = "adapter_type") val adapterType: String,
    @ColumnInfo(name = "config_json") val configJson: String?,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "plugin_tools",
    foreignKeys = [ForeignKey(PluginEntity::class, ["id"], ["plugin_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("plugin_id")],
)
data class PluginToolEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "plugin_id") val pluginId: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "input_schema") val inputSchema: String,
    @ColumnInfo(name = "execution_config") val executionConfig: String?,
    val enabled: Boolean,
)

@Entity(tableName = "permissions", indices = [Index("client_id")])
data class PermissionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "client_id") val clientId: String,
    @ColumnInfo(name = "plugin_id") val pluginId: String?,
    val capability: String,
    val granted: Boolean,
    @ColumnInfo(name = "requires_confirmation") val requiresConfirmation: Boolean,
)

@Entity(tableName = "audit_logs", indices = [Index("created_at")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "client_id") val clientId: String?,
    val action: String,
    val resource: String?,
    val status: String,
    @ColumnInfo(name = "details_json") val detailsJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
