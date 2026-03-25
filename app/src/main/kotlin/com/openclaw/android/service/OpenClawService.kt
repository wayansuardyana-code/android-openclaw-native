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
    private var heartbeat: HeartbeatService? = null

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

        // Start Telegram bot + periodic check for new token
        scope.launch {
            kotlinx.coroutines.delay(1000)
            // Try immediately
            tryStartTelegram()
            // Then check every 30s if token was added while service is running
            while (isActive) {
                kotlinx.coroutines.delay(30000)
                if (telegramBot == null || telegramBot?.isActive() != true) {
                    tryStartTelegram()
                }
            }
        }

        // Start Heartbeat (autonomous agent loop)
        scope.launch {
            kotlinx.coroutines.delay(5000) // Let everything settle
            heartbeat = HeartbeatService()
            heartbeat?.start()
        }

        ServiceState.addLog("Bridge API ready at http://localhost:18790")

        // Request battery optimization exemption
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (_: Exception) {}

        return START_STICKY  // Auto-restart if killed
    }

    private fun tryStartTelegram() {
        try {
            val token = com.openclaw.android.ai.AgentConfig.getKeyForProvider("telegram")
            if (token.isBlank()) return
            if (telegramBot?.isActive() == true) return
            telegramBot?.stop()
            telegramBot = TelegramBotService()
            telegramBot?.start()
            ServiceState.addLog("Telegram bot started")
        } catch (e: Exception) {
            ServiceState.addLog("Telegram bot error: ${e.message}")
        }
    }

    override fun onDestroy() {
        ServiceState.addLog("Service stopping...")
        heartbeat?.stop()
        heartbeat = null
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
