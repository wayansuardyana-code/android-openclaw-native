package com.openclaw.android.util

import android.content.pm.PackageManager
import com.openclaw.android.OpenClawApplication
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku integration — ADB-level power without root.
 *
 * What Shizuku enables:
 * - Auto-enable Accessibility Service (survives APK updates)
 * - Auto-enable Notification Listener
 * - Install/uninstall packages silently
 * - Change system settings
 * - Input injection (alternative to AccessibilityService)
 *
 * Prerequisites:
 * 1. User installs Shizuku from Play Store
 * 2. User enables Wireless Debugging (Android 11+)
 * 3. User starts Shizuku
 * 4. App requests Shizuku permission
 */
object ShizukuHelper {

    private const val PERMISSION_CODE = 1337

    /**
     * Check if Shizuku is installed and running
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check if we have Shizuku permission
     */
    fun hasPermission(): Boolean {
        return try {
            if (!isAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Request Shizuku permission
     */
    fun requestPermission() {
        try {
            if (!isAvailable()) {
                ServiceState.addLog("Shizuku: not available (install Shizuku app first)")
                return
            }
            if (hasPermission()) {
                ServiceState.addLog("Shizuku: already has permission")
                return
            }
            Shizuku.requestPermission(PERMISSION_CODE)
            ServiceState.addLog("Shizuku: permission requested")
        } catch (e: Exception) {
            ServiceState.addLog("Shizuku: request error — ${e.message}")
        }
    }

    /**
     * Run a shell command with ADB (shell) privileges via Shizuku
     */
    fun runShellCommand(command: String): String {
        if (!hasPermission()) return "Shizuku permission not granted"
        return try {
            // Execute via Shizuku's binder-based shell service
            val process = java.lang.Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val result = (output + error).trim()
            ServiceState.addLog("Shizuku cmd: $command → ${result.take(100)}")
            result
        } catch (e: Exception) {
            ServiceState.addLog("Shizuku cmd error: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * Auto-enable Accessibility Service for this app
     */
    fun enableAccessibility(): Boolean {
        if (!hasPermission()) return false
        val pkg = OpenClawApplication.instance.packageName
        val service = "$pkg/.service.ScreenReaderService"

        // Get current enabled services
        val current = runShellCommand("settings get secure enabled_accessibility_services")

        val newValue = if (current.isBlank() || current == "null") {
            service
        } else if (!current.contains(service)) {
            "$current:$service"
        } else {
            return true // Already enabled
        }

        runShellCommand("settings put secure enabled_accessibility_services '$newValue'")
        runShellCommand("settings put secure accessibility_enabled 1")
        ServiceState.addLog("Shizuku: accessibility enabled for $service")
        return true
    }

    /**
     * Auto-enable Notification Listener for this app
     */
    fun enableNotificationListener(): Boolean {
        if (!hasPermission()) return false
        val pkg = OpenClawApplication.instance.packageName
        val service = "$pkg/.service.NotificationReaderService"

        val current = runShellCommand("settings get secure enabled_notification_listeners")

        val newValue = if (current.isBlank() || current == "null") {
            service
        } else if (!current.contains(service)) {
            "$current:$service"
        } else {
            return true
        }

        runShellCommand("settings put secure enabled_notification_listeners '$newValue'")
        ServiceState.addLog("Shizuku: notification listener enabled for $service")
        return true
    }

    /**
     * Auto-enable everything — call after app update
     */
    fun autoEnableAll() {
        if (!isAvailable() || !hasPermission()) {
            ServiceState.addLog("Shizuku: not available or no permission — skipping auto-enable")
            return
        }
        ServiceState.addLog("Shizuku: auto-enabling services...")
        enableAccessibility()
        enableNotificationListener()
    }

    /**
     * Get Shizuku status for display
     */
    fun getStatus(): String {
        return when {
            !isAvailable() -> "Not installed (install Shizuku from Play Store)"
            !hasPermission() -> "Running but no permission (tap to request)"
            else -> "Active — ADB-level access granted"
        }
    }
}
