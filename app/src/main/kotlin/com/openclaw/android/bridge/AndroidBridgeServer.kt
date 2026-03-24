package com.openclaw.android.bridge

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.ContactsContract
import com.openclaw.android.service.NotificationReaderService
import com.openclaw.android.service.ScreenReaderService
import com.openclaw.android.util.ServiceState
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Localhost HTTP server that exposes Android system capabilities.
 * Node.js OpenClaw extension calls these endpoints to control the device.
 *
 * All endpoints are on localhost:18790 — no network exposure.
 *
 * Endpoints:
 *   POST /android/tap          {x, y}
 *   POST /android/swipe        {x1, y1, x2, y2, duration}
 *   POST /android/type         {text}
 *   GET  /android/screen       → accessibility tree JSON
 *   POST /android/back
 *   POST /android/home
 *   POST /android/recents
 *   POST /android/open-app     {packageName}
 *   GET  /android/notifications → all active notifications
 *   GET  /android/notifications/recent?limit=20
 *   POST /android/notification/dismiss {key}
 *   GET  /android/battery
 *   POST /android/volume       {level, stream}
 *   GET  /android/contacts     ?limit=50
 *   GET  /android/health       → service status check
 */
class AndroidBridgeServer(private val context: Context) {

    private var server: NettyApplicationEngine? = null
    private val gson = Gson()
    private val port = 18790

    fun start() {
        val appContext = context
        server = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { gson() }

            routing {
                // Health check
                get("/android/health") {
                    call.respond(JsonObject().apply {
                        addProperty("status", "ok")
                        addProperty("accessibility", ScreenReaderService.instance != null)
                        addProperty("notifications", NotificationReaderService.instance != null)
                    }.toString())
                }

                // ── Screen Control ──────────────────────────

                get("/android/screen") {
                    val reader = ScreenReaderService.instance
                    if (reader == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Accessibility service not enabled"))
                        return@get
                    }
                    call.respondText(reader.readScreen().toString(), ContentType.Application.Json)
                }

                post("/android/tap") {
                    val body = call.receive<Map<String, Any>>()
                    val x = (body["x"] as Number).toFloat()
                    val y = (body["y"] as Number).toFloat()
                    val reader = ScreenReaderService.instance
                    if (reader == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Accessibility service not enabled"))
                        return@post
                    }
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.tap(x, y) { result -> cont.resume(result) }
                    }
                    call.respond(mapOf("success" to success, "x" to x, "y" to y))
                }

                post("/android/swipe") {
                    val body = call.receive<Map<String, Any>>()
                    val x1 = (body["x1"] as Number).toFloat()
                    val y1 = (body["y1"] as Number).toFloat()
                    val x2 = (body["x2"] as Number).toFloat()
                    val y2 = (body["y2"] as Number).toFloat()
                    val duration = (body["duration"] as? Number)?.toLong() ?: 300L
                    val reader = ScreenReaderService.instance
                    if (reader == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Accessibility service not enabled"))
                        return@post
                    }
                    val success = suspendCancellableCoroutine<Boolean> { cont ->
                        reader.swipe(x1, y1, x2, y2, duration) { result -> cont.resume(result) }
                    }
                    call.respond(mapOf("success" to success))
                }

                post("/android/type") {
                    val body = call.receive<Map<String, String>>()
                    val text = body["text"] ?: ""
                    val reader = ScreenReaderService.instance
                    if (reader == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Accessibility service not enabled"))
                        return@post
                    }
                    val success = reader.typeText(text)
                    call.respond(mapOf("success" to success))
                }

                // ── Navigation ──────────────────────────────

                post("/android/back") {
                    val success = ScreenReaderService.instance?.pressBack() ?: false
                    call.respond(mapOf("success" to success))
                }

                post("/android/home") {
                    val success = ScreenReaderService.instance?.pressHome() ?: false
                    call.respond(mapOf("success" to success))
                }

                post("/android/recents") {
                    val success = ScreenReaderService.instance?.pressRecents() ?: false
                    call.respond(mapOf("success" to success))
                }

                post("/android/open-app") {
                    val body = call.receive<Map<String, String>>()
                    val packageName = body["packageName"] ?: ""
                    val intent = appContext.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(intent)
                        call.respond(mapOf("success" to true, "packageName" to packageName))
                    } else {
                        call.respond(HttpStatusCode.NotFound,
                            mapOf("error" to "App not found: $packageName"))
                    }
                }

                // ── Notifications ───────────────────────────

                get("/android/notifications") {
                    val listener = NotificationReaderService.instance
                    if (listener == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Notification listener not enabled"))
                        return@get
                    }
                    call.respondText(
                        listener.getActiveNotificationsJson().toString(),
                        ContentType.Application.Json
                    )
                }

                get("/android/notifications/recent") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                    val listener = NotificationReaderService.instance
                    if (listener == null) {
                        call.respond(HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Notification listener not enabled"))
                        return@get
                    }
                    call.respondText(
                        listener.getRecentNotifications(limit).toString(),
                        ContentType.Application.Json
                    )
                }

                post("/android/notification/dismiss") {
                    val body = call.receive<Map<String, String>>()
                    val key = body["key"] ?: ""
                    val success = NotificationReaderService.instance?.dismissNotification(key) ?: false
                    call.respond(mapOf("success" to success))
                }

                // ── Device Info ─────────────────────────────

                get("/android/battery") {
                    val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val charging = bm.isCharging
                    call.respond(mapOf("level" to level, "charging" to charging))
                }

                post("/android/volume") {
                    val body = call.receive<Map<String, Any>>()
                    val level = (body["level"] as Number).toInt()
                    val stream = when (body["stream"] as? String) {
                        "music" -> AudioManager.STREAM_MUSIC
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val maxVol = am.getStreamMaxVolume(stream)
                    val target = (level * maxVol / 100).coerceIn(0, maxVol)
                    am.setStreamVolume(stream, target, 0)
                    call.respond(mapOf("success" to true, "level" to level, "actual" to target, "max" to maxVol))
                }

                // ── Contacts (read-only) ────────────────────

                get("/android/contacts") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    val contacts = mutableListOf<Map<String, String?>>()
                    try {
                        val cursor = appContext.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            ),
                            null, null,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                        )
                        cursor?.use {
                            var count = 0
                            while (it.moveToNext() && count < limit) {
                                contacts.add(mapOf(
                                    "name" to it.getString(0),
                                    "phone" to it.getString(1)
                                ))
                                count++
                            }
                        }
                    } catch (e: SecurityException) {
                        call.respond(HttpStatusCode.Forbidden,
                            mapOf("error" to "Contacts permission not granted"))
                        return@get
                    }
                    call.respond(contacts)
                }
            }
        }.start(wait = false)

        ServiceState.addLog("Bridge server listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
