package com.uoai.clients

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

data class CallerIdentity(val packageName: String, val appName: String, val certDigest: String)

/** Resolves the OS-verified identity of a calling package. A package name alone is never trusted. */
object CallerIdentityResolver {
    @Suppress("DEPRECATION")
    fun resolve(context: Context, packageName: String): CallerIdentity? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (signers.size != 1) return null // multi-signer apps are not supported
        val digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
            .joinToString(":") { "%02X".format(it) }
        val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        CallerIdentity(packageName, label, digest)
    }.getOrNull()
}
