package com.openclaw.android.util

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.openclaw.android.OpenClawApplication
import com.openclaw.android.ai.AgentConfig

/**
 * Sends push notifications when:
 * - Agent completes a task
 * - Agent encounters an error
 * - A tool execution finishes (if enabled)
 */
object NotificationHelper {
    private var notifId = 100

    fun notifyAgentResponse(title: String, body: String) {
        if (!AgentConfig.pushNotificationsEnabled) return
        val context = OpenClawApplication.instance
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, OpenClawApplication.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId++, notification)
    }

    fun notifyError(message: String) {
        notifyAgentResponse("OpenClaw Error", message)
    }

    fun notifyTaskComplete(taskName: String) {
        notifyAgentResponse("Task Complete", taskName)
    }
}
