package com.uoai.integration.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.uoai.integration.AutomationCapability

/**
 * FR-11: Optional Android intent integration kept separate from core inference.
 * Only executes allow-listed, user-visible actions (launching an installed package, opening an http/https URL,
 * or opening the system share sheet). Never accesses another application's private storage.
 */
class ApprovedIntentExecutor(private val context: Context) : AutomationCapability {
    override val id: String = "android.intents"

    private val allowedSchemes = setOf("http", "https")

    fun launchApp(packageName: String): Result<Unit> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("Package '$packageName' is not installed or has no launch activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openUrl(url: String): Result<Unit> = runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        require(scheme in allowedSchemes) { "Scheme '$scheme' is not allowed; only http/https are supported" }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun shareText(text: String, title: String = "Share"): Result<Unit> = runCatching {
        require(text.isNotBlank()) { "Share text must not be empty" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
