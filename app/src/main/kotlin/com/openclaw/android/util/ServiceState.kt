package com.openclaw.android.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared state between Service and UI.
 * Uses StateFlow so Compose recomposes automatically.
 */
object ServiceState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _gatewayPort = MutableStateFlow(18789)
    val gatewayPort = _gatewayPort.asStateFlow()

    private val _bridgePort = MutableStateFlow(18790)
    val bridgePort = _bridgePort.asStateFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        // Thread-safe atomic update
        _logs.update { current ->
            val updated = current + entry
            if (updated.size > 500) updated.takeLast(500) else updated
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
