package com.openclaw.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.openclaw.android.MainActivity
import com.openclaw.android.OpenClawApplication
import com.openclaw.android.R
import com.openclaw.android.bridge.AndroidBridgeServer
import com.openclaw.android.util.ServiceState
import kotlinx.coroutines.*

/**
 * Foreground service that runs:
 * 1. Android Bridge Server (Ktor on localhost:18790)
 *    - Exposes Android system APIs (accessibility, notifications, camera, etc.)
 *    - Node.js OpenClaw calls these via HTTP
 *
 * 2. Node.js Runtime (nodejs-mobile) — Phase 1b
 *    - Runs OpenClaw gateway on localhost:18789
 *    - Connects to Telegram/Discord/WhatsApp channels
 *    - Manages AI agent sessions
 *
 * The bridge server starts immediately. Node.js integration
 * will be added once nodejs-mobile dependency is configured.
 */
class OpenClawService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var bridgeServer: AndroidBridgeServer? = null
    private var telegramBot: TelegramBotService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        ServiceState.setRunning(true)
        ServiceState.addLog("Service started")

        // Stop old bridge server if exists (prevents "Address already in use")
        bridgeServer?.stop()
        bridgeServer = null

        // Start bridge server
        scope.launch {
            try {
                // Small delay to let old port release
                kotlinx.coroutines.delay(500)
                bridgeServer = AndroidBridgeServer(applicationContext)
                bridgeServer?.start()
                ServiceState.addLog("Bridge server started on :18790")
            } catch (e: Exception) {
                ServiceState.addLog("Bridge server error: ${e.message}")
            }
        }

        // Start Telegram bot polling (if token is configured)
        scope.launch {
            try {
                kotlinx.coroutines.delay(1000) // Let bridge server initialize first
                telegramBot?.stop()
                telegramBot = TelegramBotService()
                telegramBot?.start()
            } catch (e: Exception) {
                ServiceState.addLog("Telegram bot error: ${e.message}")
            }
        }

        // TODO Phase 1b: Start Node.js runtime with OpenClaw gateway
        // NodeJsRuntime.start(applicationContext, "openclaw/openclaw.mjs", "gateway")
        ServiceState.addLog("Node.js runtime: not yet integrated (Phase 1b)")
        ServiceState.addLog("Bridge API ready at http://localhost:18790")

        return START_NOT_STICKY  // Don't auto-restart (BootReceiver handles that)
    }

    override fun onDestroy() {
        ServiceState.addLog("Service stopping...")
        telegramBot?.stop()
        telegramBot = null
        scope.cancel()
        bridgeServer?.stop()
        releaseWakeLock()
        ServiceState.setRunning(false)
        ServiceState.addLog("Service stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OpenClawApplication.CHANNEL_SERVICE)
            .setContentTitle("OpenClaw Active")
            .setContentText("AI assistant running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClaw::ServiceWakeLock"
        ).apply {
            acquire() // No timeout — released in onDestroy
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
