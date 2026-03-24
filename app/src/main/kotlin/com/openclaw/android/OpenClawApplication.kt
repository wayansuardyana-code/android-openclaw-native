package com.openclaw.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OpenClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
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
