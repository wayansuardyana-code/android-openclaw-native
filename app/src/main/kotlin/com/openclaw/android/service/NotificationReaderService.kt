package com.openclaw.android.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.openclaw.android.util.ServiceState
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Reads ALL notifications from ALL apps.
 * Stores recent notifications in memory for the AI to query.
 * Also pushes real-time events via the bridge WebSocket.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        ServiceState.addLog("Notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val entry = notificationToJson(sbn)
        recentNotifications.addFirst(entry)

        // Keep last 100 notifications
        while (recentNotifications.size > 100) {
            recentNotifications.removeLast()
        }

        // Push to bridge WebSocket if connected
        // AndroidBridgeServer.pushEvent("notification", entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Could track dismissed notifications if needed
    }

    /**
     * Get all currently active notifications as JSON.
     */
    fun getActiveNotificationsJson(): JsonArray {
        val result = JsonArray()
        try {
            activeNotifications?.forEach { sbn ->
                result.add(notificationToJson(sbn))
            }
        } catch (e: Exception) {
            ServiceState.addLog("Error reading notifications: ${e.message}")
        }
        return result
    }

    /**
     * Get recent notification history (last 100).
     */
    fun getRecentNotifications(limit: Int = 20): JsonArray {
        val result = JsonArray()
        recentNotifications.take(limit).forEach { result.add(it) }
        return result
    }

    /**
     * Dismiss a notification by key.
     */
    fun dismissNotification(key: String): Boolean {
        return try {
            cancelNotification(key)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notificationToJson(sbn: StatusBarNotification): JsonObject {
        val extras = sbn.notification.extras
        return JsonObject().apply {
            addProperty("key", sbn.key)
            addProperty("packageName", sbn.packageName)
            addProperty("postTime", sbn.postTime)
            addProperty("isOngoing", sbn.isOngoing)
            addProperty("title", extras.getCharSequence("android.title")?.toString() ?: "")
            addProperty("text", extras.getCharSequence("android.text")?.toString() ?: "")
            addProperty("bigText", extras.getCharSequence("android.bigText")?.toString() ?: "")
            addProperty("subText", extras.getCharSequence("android.subText")?.toString() ?: "")
        }
    }

    companion object {
        @Volatile var instance: NotificationReaderService? = null
            private set
        private val recentNotifications = ConcurrentLinkedDeque<JsonObject>()
    }
}
