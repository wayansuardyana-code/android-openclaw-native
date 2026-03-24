package com.openclaw.android.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.BuildConfig
import com.openclaw.android.OpenClawApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Auto-updater: checks GitHub Releases for new APK, downloads, triggers install.
 *
 * Flow:
 * 1. Fetch latest release from GitHub API
 * 2. Compare version with current BuildConfig.VERSION_NAME
 * 3. Download APK via DownloadManager
 * 4. Trigger Android install prompt
 *
 * Settings persist across installs (SharedPreferences + filesDir stay).
 */
object AppUpdater {

    private const val REPO = "wayansuardyana-code/android-openclaw-native"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val isNewer: Boolean
    )

    /**
     * Check for updates. Returns UpdateInfo or null if up-to-date/error.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL(API_URL).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OpenClaw-Android")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
            reader.isLenient = true
            val release = Gson().fromJson<JsonObject>(reader, JsonObject::class.java)

            val tagName = release.get("tag_name")?.asString ?: return@withContext null
            val body = release.get("body")?.asString ?: ""

            // Find APK asset
            val assets = release.getAsJsonArray("assets")
            var apkUrl = ""
            assets?.forEach { asset ->
                val a = asset.asJsonObject
                val name = a.get("name")?.asString ?: ""
                if (name.endsWith(".apk")) {
                    apkUrl = a.get("browser_download_url")?.asString ?: ""
                }
            }

            // If no APK in release, construct download URL from VPS
            if (apkUrl.isBlank()) {
                apkUrl = "http://187.127.104.132:8899/openclaw-android-${tagName}-debug.apk"
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val isNewer = tagName.removePrefix("v") != currentVersion

            ServiceState.addLog("Update check: current=$currentVersion, latest=$tagName, newer=$isNewer")

            UpdateInfo(
                version = tagName,
                downloadUrl = apkUrl,
                releaseNotes = body.take(500),
                isNewer = isNewer
            )
        } catch (e: Exception) {
            ServiceState.addLog("Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download APK and trigger install.
     */
    fun downloadAndInstall(context: Context, url: String, version: String) {
        ServiceState.addLog("Downloading update: $version from $url")

        val fileName = "openclaw-$version.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("OpenClaw Update $version")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setMimeType("application/vnd.android.package-archive")

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        // Register receiver to install when download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    ctx.unregisterReceiver(this)
                    installApk(ctx, fileName)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, fileName: String) {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) {
                ServiceState.addLog("Update APK not found: ${file.absolutePath}")
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } else {
                    Uri.fromFile(file)
                }
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            ServiceState.addLog("Install prompt triggered for $fileName")
        } catch (e: Exception) {
            ServiceState.addLog("Install failed: ${e.message}")
            // Fallback: open file manager
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) } catch (_: Exception) {}
        }
    }
}
