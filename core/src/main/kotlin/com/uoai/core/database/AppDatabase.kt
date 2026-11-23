package com.uoai.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RegisteredClientEntity::class, ClientCredentialEntity::class, InferenceRequestEntity::class,
        ModelEntity::class, PluginEntity::class, PluginToolEntity::class, PermissionEntity::class, AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun credentialDao(): CredentialDao
    abstract fun requestDao(): InferenceRequestDao
    abstract fun auditDao(): AuditDao
    abstract fun modelDao(): ModelDao
    abstract fun pluginDao(): PluginDao
    abstract fun permissionDao(): PermissionDao
}
