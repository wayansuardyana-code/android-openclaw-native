package com.openclaw.android.ai

import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.ServiceState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages a portable Node.js runtime on Android.
 * Downloads Node.js 22 LTS (aarch64) on first use — no root needed.
 * Binary lives in app's private directory: filesDir/nodejs/bin/node
 *
 * This enables: Express, n8n, web servers, npm packages, JS scripting.
 *
 * Execution strategy:
 * 1. Try direct execution (works on Android 9 and below, some Android 10+)
 * 2. Try via system linker: /system/bin/linker64 <binary> (Termux trick for W^X bypass)
 * 3. Fallback: report error with instructions
 */
object NodeRuntime {

    // Node.js 22 LTS unofficial builds for aarch64 Linux (musl-static)
    private const val NODE_URL = "https://unofficial-builds.nodejs.org/download/release/v22.16.0/node-v22.16.0-linux-arm64-musl.tar.gz"
    private const val NODE_SIZE_MB = 45

    private fun nodeDir(): File = File(OpenClawApplication.instance.filesDir, "nodejs")
    private fun nodeBin(): File = File(nodeDir(), "node-v22.16.0-linux-arm64-musl/bin/node")
    private fun npmBin(): File = File(nodeDir(), "node-v22.16.0-linux-arm64-musl/bin/npm")
    private fun npxBin(): File = File(nodeDir(), "node-v22.16.0-linux-arm64-musl/bin/npx")

    fun isInstalled(): Boolean = nodeBin().exists()

    /**
     * Install Node.js runtime. Downloads ~45MB, extracts to app private dir.
     */
    fun install(): String {
        if (isInstalled()) return """{"status":"already_installed","path":"${nodeBin().absolutePath}","version":"${getVersion()}"}"""

        ServiceState.addLog("Node.js: downloading portable runtime (~${NODE_SIZE_MB}MB)...")
        val dir = nodeDir()
        dir.mkdirs()

        try {
            val tarFile = File(dir, "node.tar.gz")

            // Try Java URL download (wget/curl may be blocked)
            ServiceState.addLog("Node.js: downloading via Java URLConnection...")
            try {
                val url = java.net.URL(NODE_URL)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 180000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input -> tarFile.outputStream().use { output -> input.copyTo(output) } }
                conn.disconnect()
            } catch (e: Exception) {
                // Fallback: try wget
                ServiceState.addLog("Node.js: Java download failed, trying wget...")
                runCmd("wget -q '$NODE_URL' -O '${tarFile.absolutePath}'", 180)
            }

            if (!tarFile.exists() || tarFile.length() < 1000) {
                return """{"error":"Download failed. Check internet connection."}"""
            }
            ServiceState.addLog("Node.js: downloaded ${tarFile.length() / (1024 * 1024)}MB")

            // Extract
            ServiceState.addLog("Node.js: extracting...")
            runCmd("cd '${dir.absolutePath}' && tar xzf node.tar.gz", 120)

            // Make executable
            nodeBin().setExecutable(true)
            runCmd("chmod +x '${nodeBin().absolutePath}'")

            // Cleanup tar
            tarFile.delete()

            // Verify — try direct execution first, then linker bypass
            var version = runCmd("'${nodeBin().absolutePath}' --version")
            if (version.isBlank() || version.contains("Permission denied") || version.contains("not found")) {
                // Try linker bypass (Termux trick for Android 10+ W^X)
                ServiceState.addLog("Node.js: direct exec failed, trying linker bypass...")
                version = runCmd("/system/bin/linker64 '${nodeBin().absolutePath}' --version")
                if (version.isNotBlank() && version.startsWith("v")) {
                    ServiceState.addLog("Node.js: linker bypass works!")
                    // Save preference to use linker bypass
                    File(nodeDir(), ".use_linker").writeText("true")
                } else {
                    ServiceState.addLog("Node.js: both execution methods failed. SELinux may be blocking.")
                    return """{"error":"Node.js binary cannot execute — SELinux restriction. Your device may not support running downloaded binaries."}"""
                }
            }

            ServiceState.addLog("Node.js: installed — $version")
            return """{"status":"installed","version":"${version.trim()}","path":"${nodeBin().absolutePath}"}"""
        } catch (e: Exception) {
            ServiceState.addLog("Node.js: install failed — ${e.message}")
            return """{"error":"${e.message}"}"""
        }
    }

    private fun getNodeCommand(): String {
        val useLinker = File(nodeDir(), ".use_linker").exists()
        return if (useLinker) "/system/bin/linker64 '${nodeBin().absolutePath}'" else "'${nodeBin().absolutePath}'"
    }

    fun getVersion(): String {
        if (!isInstalled()) return "not installed"
        return runCmd("${getNodeCommand()} --version").trim()
    }

    /**
     * Run a Node.js script.
     */
    fun execute(code: String, timeout: Long = 60): String {
        if (!isInstalled()) return """{"error":"Node.js not installed. Use install_node tool first."}"""

        val scriptFile = File(nodeDir(), "temp_script.js")
        scriptFile.writeText(code)

        val result = runCmd("${getNodeCommand()} '${scriptFile.absolutePath}'", timeout)
        scriptFile.delete()
        return result
    }

    /**
     * Install an npm package globally.
     */
    fun npmInstall(packages: String): String {
        if (!isInstalled()) return """{"error":"Node.js not installed. Use install_node tool first."}"""
        val nodeCmd = getNodeCommand()
        val npmPath = npmBin().absolutePath
        return runCmd("$nodeCmd '$npmPath' install -g $packages --quiet", 120)
    }

    /**
     * Run an npm/npx command.
     */
    fun npxRun(command: String, timeout: Long = 60): String {
        if (!isInstalled()) return """{"error":"Node.js not installed. Use install_node tool first."}"""
        val nodeCmd = getNodeCommand()
        val npxPath = npxBin().absolutePath
        return runCmd("$nodeCmd '$npxPath' $command", timeout)
    }

    /**
     * Start a Node.js server (runs in background, returns PID).
     */
    fun startServer(scriptPath: String, port: Int = 3000): String {
        if (!isInstalled()) return """{"error":"Node.js not installed. Use install_node tool first."}"""
        val nodeCmd = getNodeCommand()
        val pid = runCmd("$nodeCmd '$scriptPath' &\necho $!")
        ServiceState.addLog("Node.js: server started on port $port (PID: ${pid.trim()})")
        return """{"success":true,"port":$port,"pid":"${pid.trim()}","command":"$nodeCmd '$scriptPath'"}"""
    }

    private fun runCmd(cmd: String, timeoutSecs: Long = 60): String {
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .directory(nodeDir())
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText().take(4000)
        val finished = process.waitFor(timeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); return "(timed out after ${timeoutSecs}s)" }
        return output
    }

    /** Tool definitions for the LLM */
    fun getToolDefinitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "install_node",
            description = "Install portable Node.js 22 LTS on this Android device (~45MB). One-time setup. After installing, run JavaScript with run_node, install packages with npm_install.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "run_node",
            description = "Run JavaScript/Node.js code on this device. Has access to Node.js standard library (fs, http, path, crypto, etc). Use npm_install first for third-party packages.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "code" to mapOf("type" to "string", "description" to "JavaScript code to execute")
                ),
                "required" to listOf("code")
            )
        ),
        ToolDef(
            name = "npm_install",
            description = "Install npm packages globally. Example: 'express axios cheerio puppeteer-core'",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "packages" to mapOf("type" to "string", "description" to "Space-separated package names")
                ),
                "required" to listOf("packages")
            )
        ),
        ToolDef(
            name = "start_node_server",
            description = "Start a Node.js server script in background. Returns PID. Use for: Express APIs, web servers, bots. Server runs on specified port (default 3000). Access via http://localhost:<port>.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "script_path" to mapOf("type" to "string", "description" to "Path to .js file to run"),
                    "port" to mapOf("type" to "number", "description" to "Port number (default 3000)")
                ),
                "required" to listOf("script_path")
            )
        ),
    )

    suspend fun executeTool(name: String, args: com.google.gson.JsonObject): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (name) {
                "install_node" -> install()
                "run_node" -> execute(args.get("code").asString)
                "npm_install" -> npmInstall(args.get("packages").asString)
                "start_node_server" -> {
                    val scriptPath = args.get("script_path").asString
                    val port = args.get("port")?.asInt ?: 3000
                    startServer(scriptPath, port)
                }
                else -> """{"error":"Unknown node tool: $name"}"""
            }
        }
    }
}
