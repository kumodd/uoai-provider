package com.uoai.provider.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.uoai.provider.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the loopback API alive while the user has started the provider.
 * It is only started by an explicit user action (dashboard Start button).
 */
@AndroidEntryPoint
class ProviderService : Service() {
    @Inject lateinit var controller: ServerController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { controller.server.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        scope.launch {
            controller.server.start()
            controller.server.lastError.value?.let { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        // Not sticky: if Android kills the process the user restarts explicitly; state is never assumed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.launch { controller.server.stop() }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Provider status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProviderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.uoai.provider.R.drawable.ic_stat_uoai)
            .setContentTitle("UOAI Provider")
            .setContentText("API listening on 127.0.0.1:8765 · LLM & Speech")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Stop", stop).build()
    }

    companion object {
        const val ACTION_STOP = "com.uoai.provider.STOP"
        private const val CHANNEL = "provider"
        private const val NOTIF_ID = 1
        fun start(c: Context) = c.startForegroundService(Intent(c, ProviderService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, ProviderService::class.java).setAction(ACTION_STOP))
    }
}
