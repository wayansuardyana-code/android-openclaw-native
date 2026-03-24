package com.openclaw.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OpenClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        setupCrashHandler()
        // Auto-bootstrap workspace files on app start
        if (!com.openclaw.android.ai.Bootstrap.isBootstrapped()) {
            com.openclaw.android.ai.Bootstrap.run()
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Write crash log to file
                val crashDir = File(filesDir, "crash_logs")
                crashDir.mkdirs()

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.log")

                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("=== OpenClaw Crash Report ===")
                pw.println("Time: $timestamp")
                pw.println("Thread: ${thread.name}")
                pw.println("Android: API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                pw.println("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                pw.println()
                pw.println("=== Stack Trace ===")
                throwable.printStackTrace(pw)

                // Also get cause chain
                var cause = throwable.cause
                while (cause != null) {
                    pw.println()
                    pw.println("=== Caused by ===")
                    cause.printStackTrace(pw)
                    cause = cause.cause
                }

                crashFile.writeText(sw.toString())

                // Also write to a "latest crash" file for easy reading
                File(crashDir, "latest_crash.log").writeText(sw.toString())

            } catch (_: Exception) {
                // Can't even write the crash log — give up gracefully
            }

            // Call the default handler (shows "app has stopped" dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Read the latest crash log (called from Logs screen on next app open).
     */
    fun getLatestCrashLog(): String? {
        val file = File(filesDir, "crash_logs/latest_crash.log")
        return if (file.exists()) file.readText() else null
    }

    /**
     * Get all crash logs sorted by newest first.
     */
    fun getCrashLogs(): List<Pair<String, String>> {
        val dir = File(filesDir, "crash_logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(10)
            ?.map { it.name to it.readText() }
            ?: emptyList()
    }

    fun clearCrashLogs() {
        val dir = File(filesDir, "crash_logs")
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps OpenClaw running in background"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important alerts from OpenClaw AI"
            }

            manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "openclaw_service"
        const val CHANNEL_ALERTS = "openclaw_alerts"
        lateinit var instance: OpenClawApplication
            private set
    }
}
