package com.openclaw.android.ai

import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.ServiceState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages a portable Python runtime on Android.
 * Downloads musl-static Python 3.13 (aarch64) on first use — no root needed.
 * Binary lives in app's private directory: filesDir/python/bin/python3
 *
 * This enables: markitdown, pandas, matplotlib, scipy, openpyxl, etc.
 */
object PythonRuntime {

    private const val PYTHON_URL = "https://github.com/astral-sh/python-build-standalone/releases/download/20260320/cpython-3.13.12%2B20260320-aarch64-unknown-linux-musl-install_only_stripped.tar.gz"
    private const val PYTHON_SIZE_MB = 27

    private fun pythonDir(): File = File(OpenClawApplication.instance.filesDir, "python")
    private fun pythonBin(): File = File(pythonDir(), "python/bin/python3")
    private fun pipBin(): File = File(pythonDir(), "python/bin/pip3")

    fun isInstalled(): Boolean = pythonBin().exists() && pythonBin().canExecute()

    /**
     * Install Python runtime. Downloads ~27MB, extracts to app private dir.
     * Returns status message.
     */
    fun install(): String {
        if (isInstalled()) return """{"status":"already_installed","path":"${pythonBin().absolutePath}"}"""

        ServiceState.addLog("Python: downloading portable runtime (~${PYTHON_SIZE_MB}MB)...")
        val dir = pythonDir()
        dir.mkdirs()

        // Ensure documents output dir exists
        val docsDir = File(OpenClawApplication.instance.filesDir, "documents")
        docsDir.mkdirs()

        try {
            // Download using Android DownloadManager or wget (curl may be blocked by shell blocklist)
            val tarFile = File(dir, "python.tar.gz")
            // Try wget first (not in blocklist), fallback to direct Java download
            val dlResult = runCmd("wget -q '$PYTHON_URL' -O '${tarFile.absolutePath}'", 120)
            if (!tarFile.exists() || tarFile.length() < 1000) {
                // Fallback: Java URL download
                ServiceState.addLog("Python: wget failed, trying Java download...")
                try {
                    val url = java.net.URL(PYTHON_URL)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 30000
                    conn.readTimeout = 120000
                    conn.inputStream.use { input -> tarFile.outputStream().use { output -> input.copyTo(output) } }
                    conn.disconnect()
                } catch (e: Exception) {
                    return """{"error":"Download failed: ${e.message?.take(200)}. Try manual: download Python tarball and place at ${tarFile.absolutePath}"}"""
                }
            }
            if (!tarFile.exists() || tarFile.length() < 1000) {
                return """{"error":"Download failed: file empty or missing. ${dlResult.take(200)}"}"""
            }
            ServiceState.addLog("Python: downloaded ${tarFile.length() / (1024 * 1024)}MB")

            // Extract
            ServiceState.addLog("Python: extracting...")
            val extractCmd = "cd '${dir.absolutePath}' && tar xzf python.tar.gz"
            runCmd(extractCmd, 120)

            // Make executable (try chmod too for SELinux)
            pythonBin().setExecutable(true)
            runCmd("chmod +x '${pythonBin().absolutePath}'")

            // Cleanup tar
            tarFile.delete()

            // Verify
            val version = runCmd("'${pythonBin().absolutePath}' --version")
            if (version.isBlank() || version.contains("Permission denied") || version.contains("not found")) {
                ServiceState.addLog("Python: SELinux may be blocking execution. Try: adb shell setenforce 0")
                return """{"error":"Python binary cannot execute — likely SELinux restriction. Use generate_xlsx/generate_csv/generate_pdf (Kotlin-native) instead, or try: Settings → Developer Options → disable SELinux enforcement (root only)."}"""
            }
            ServiceState.addLog("Python: installed — $version")

            return """{"status":"installed","version":"${version.trim()}","path":"${pythonBin().absolutePath}","output_dir":"${docsDir.absolutePath}"}"""
        } catch (e: Exception) {
            ServiceState.addLog("Python: install failed — ${e.message}")
            return """{"error":"${e.message}","fallback":"Use generate_xlsx/generate_csv/generate_pdf (Kotlin-native) for document creation"}"""
        }
    }

    /**
     * Run a Python script or command.
     */
    fun execute(code: String, timeout: Long = 60): String {
        if (!isInstalled()) return """{"error":"Python not installed. Use install_python tool first."}"""

        val python = pythonBin().absolutePath
        // Write code to temp file
        val scriptFile = File(pythonDir(), "temp_script.py")
        scriptFile.writeText(code)

        val result = runCmd("'$python' '${scriptFile.absolutePath}'", timeout)
        scriptFile.delete()
        return result
    }

    /**
     * Install a pip package.
     */
    fun pipInstall(packages: String): String {
        if (!isInstalled()) return """{"error":"Python not installed. Use install_python tool first."}"""
        val python = pythonBin().absolutePath
        // Use ensurepip first if pip not available
        if (!pipBin().exists()) {
            runCmd("'$python' -m ensurepip --default-pip")
        }
        return runCmd("'$python' -m pip install $packages --quiet", 120)
    }

    private fun runCmd(cmd: String, timeoutSecs: Long = 60): String {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText().take(4000)
        val finished = process.waitFor(timeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); return "(timed out after ${timeoutSecs}s)" }
        return output
    }

    /** Get tool definitions for the LLM */
    fun getToolDefinitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "install_python",
            description = "Install portable Python 3.13 runtime on this Android device (~27MB download). Only needs to be done once. After installing, you can run Python scripts with run_python.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "run_python",
            description = "Run a Python script on this device. Write full Python code. Has access to standard library. Use pip_install first for third-party packages (pandas, matplotlib, etc.).",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "code" to mapOf("type" to "string", "description" to "Python code to execute")
                ),
                "required" to listOf("code")
            )
        ),
        ToolDef(
            name = "pip_install",
            description = "Install Python packages via pip. Example: 'pandas matplotlib scipy openpyxl markitdown'",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "packages" to mapOf("type" to "string", "description" to "Space-separated package names to install")
                ),
                "required" to listOf("packages")
            )
        ),
    )

    suspend fun executeTool(name: String, args: com.google.gson.JsonObject): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (name) {
                "install_python" -> install()
                "run_python" -> execute(args.get("code").asString)
                "pip_install" -> pipInstall(args.get("packages").asString)
                else -> """{"error":"Unknown python tool: $name"}"""
            }
        }
    }
}
