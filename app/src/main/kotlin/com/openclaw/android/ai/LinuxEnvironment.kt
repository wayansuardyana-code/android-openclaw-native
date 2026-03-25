package com.openclaw.android.ai

import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.ServiceState
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Embedded Linux Environment for Android (no root required).
 *
 * Uses PRoot + Alpine Linux to provide a full Linux userspace:
 * - bash, sh, coreutils (ls, cp, mv, rm, cat, grep, etc.)
 * - apk package manager (install anything: git, openssh, nginx, etc.)
 * - Full /usr/bin, /etc, /home filesystem
 * - Works on Android 7.0+ (API 24+), ARM64
 *
 * Architecture:
 * 1. Download proot (aarch64 static binary, ~2MB)
 * 2. Download Alpine Linux minirootfs (~5MB)
 * 3. Extract rootfs to app's private directory
 * 4. Run commands via: proot -0 -r rootfs -w /root /bin/sh -c "command"
 *
 * The linker bypass (/system/bin/linker64) is used if direct exec fails (Android 10+ W^X).
 * Proven approach: used by Termux, UserLAnd (4.2K stars), tiny_computer (2.9K stars).
 */
object LinuxEnvironment {

    private const val PROOT_URL = "https://github.com/nicehash/proot-android/releases/download/v5.4.0/proot-android-aarch64"
    private const val ALPINE_URL = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz"
    private const val ALPINE_SIZE_MB = 5

    private fun baseDir(): File = File(OpenClawApplication.instance.filesDir, "linux")
    private fun prootBin(): File = File(baseDir(), "proot")
    private fun rootfsDir(): File = File(baseDir(), "alpine")

    fun isInstalled(): Boolean = prootBin().exists() && File(rootfsDir(), "bin/sh").exists()

    /**
     * Setup the Linux environment. Downloads PRoot + Alpine Linux (~7MB total).
     * One-time setup, takes ~30 seconds on good connection.
     */
    fun setup(): String {
        if (isInstalled()) return """{"status":"already_installed","rootfs":"${rootfsDir().absolutePath}"}"""

        val dir = baseDir()
        dir.mkdirs()

        try {
            // Step 1: Download PRoot binary
            ServiceState.addLog("Linux: downloading proot (~2MB)...")
            val prootFile = prootBin()
            downloadFile(PROOT_URL, prootFile)
            if (!prootFile.exists() || prootFile.length() < 1000) {
                return """{"error":"Failed to download proot binary"}"""
            }
            prootFile.setExecutable(true)
            runCmd("chmod +x '${prootFile.absolutePath}'")
            ServiceState.addLog("Linux: proot downloaded (${prootFile.length() / 1024}KB)")

            // Step 2: Download Alpine rootfs
            ServiceState.addLog("Linux: downloading Alpine Linux (~${ALPINE_SIZE_MB}MB)...")
            val alpineTar = File(dir, "alpine.tar.gz")
            downloadFile(ALPINE_URL, alpineTar)
            if (!alpineTar.exists() || alpineTar.length() < 1000) {
                return """{"error":"Failed to download Alpine rootfs"}"""
            }
            ServiceState.addLog("Linux: Alpine downloaded (${alpineTar.length() / (1024 * 1024)}MB)")

            // Step 3: Extract rootfs
            ServiceState.addLog("Linux: extracting rootfs...")
            val rootfs = rootfsDir()
            rootfs.mkdirs()
            runCmd("cd '${rootfs.absolutePath}' && tar xzf '${alpineTar.absolutePath}'", 60)

            // Verify extraction
            if (!File(rootfs, "bin/sh").exists()) {
                return """{"error":"Alpine extraction failed — bin/sh not found"}"""
            }

            // Step 4: Configure DNS resolvers
            val resolv = File(rootfs, "etc/resolv.conf")
            resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // Step 5: Configure environment
            val profile = File(rootfs, "etc/profile.d/openclaw.sh")
            profile.parentFile?.mkdirs()
            profile.writeText("""
                export HOME=/root
                export LANG=C.UTF-8
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export TERM=xterm-256color
                alias ll='ls -la'
            """.trimIndent() + "\n")

            // Cleanup tar
            alpineTar.delete()

            // Step 6: Verify proot works (try direct exec, then linker bypass)
            var testResult = runCmd("'${prootFile.absolutePath}' --version")
            if (testResult.isBlank() || testResult.contains("Permission denied")) {
                ServiceState.addLog("Linux: direct exec failed, trying linker bypass...")
                testResult = runCmd("/system/bin/linker64 '${prootFile.absolutePath}' --version")
                if (testResult.isNotBlank() && !testResult.contains("Permission denied")) {
                    File(dir, ".use_linker").writeText("true")
                    ServiceState.addLog("Linux: linker bypass works!")
                } else {
                    return """{"error":"PRoot cannot execute on this device — SELinux may be blocking it. Device needs Android with permissive exec policy."}"""
                }
            }

            // Step 7: Test running a command inside Alpine
            val helloTest = execute("echo 'Hello from Alpine Linux' && cat /etc/alpine-release")
            ServiceState.addLog("Linux: setup complete! $helloTest")

            return """{"status":"installed","rootfs":"${rootfs.absolutePath}","proot":"${prootFile.absolutePath}","test":"${helloTest.take(200).replace("\"", "'")}"}"""
        } catch (e: Exception) {
            ServiceState.addLog("Linux: setup failed — ${e.message}")
            return """{"error":"Setup failed: ${e.message?.take(200)}"}"""
        }
    }

    /**
     * Run a command inside the Linux environment.
     */
    fun execute(command: String, timeout: Long = 60): String {
        if (!isInstalled()) return """{"error":"Linux environment not installed. Use setup_linux tool first."}"""

        val prootCmd = getProotCommand()
        val rootfs = rootfsDir().absolutePath

        // PRoot invocation:
        // -0: simulate root user
        // -r: set root filesystem
        // -w /root: set working directory
        // -b /dev: bind device filesystem
        // -b /proc: bind proc filesystem
        // -b /sys: bind sys filesystem
        // -b /sdcard: bind shared storage (if accessible)
        val fullCmd = buildString {
            append("$prootCmd -0 ")
            append("-r '$rootfs' ")
            append("-w /root ")
            append("-b /dev ")
            append("-b /proc ")
            append("-b /sys ")
            // Bind shared storage if available
            if (File("/sdcard").exists()) append("-b /sdcard ")
            // Bind app files dir for file exchange
            val filesDir = OpenClawApplication.instance.filesDir.absolutePath
            append("-b '$filesDir:/openclaw' ")
            append("/bin/sh -c '")
            append(". /etc/profile 2>/dev/null; ")
            append(command.replace("'", "'\\''"))
            append("'")
        }

        return runCmd(fullCmd, timeout)
    }

    /**
     * Install a package using Alpine's apk package manager.
     */
    fun installPackage(packages: String): String {
        if (!isInstalled()) return """{"error":"Linux environment not installed. Use setup_linux tool first."}"""
        ServiceState.addLog("Linux: installing packages: $packages")
        val result = execute("apk update && apk add --no-cache $packages", 120)
        ServiceState.addLog("Linux: package install result: ${result.take(100)}")
        return result
    }

    private fun getProotCommand(): String {
        val useLinker = File(baseDir(), ".use_linker").exists()
        val proot = prootBin().absolutePath
        return if (useLinker) "/system/bin/linker64 '$proot'" else "'$proot'"
    }

    private fun downloadFile(url: String, target: File) {
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 180000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
        } catch (e: Exception) {
            ServiceState.addLog("Linux: download failed (${e.message}), trying wget...")
            runCmd("wget -q '$url' -O '${target.absolutePath}'", 180)
        }
    }

    private fun runCmd(cmd: String, timeoutSecs: Long = 60): String {
        val env = arrayOf(
            "HOME=${baseDir().absolutePath}",
            "PROOT_TMP_DIR=${baseDir().absolutePath}/tmp",
            "PROOT_NO_SECCOMP=1"  // Required on some Android devices
        )
        // Ensure tmp dir exists
        File(baseDir(), "tmp").mkdirs()

        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .directory(baseDir())
            .start()

        // Set environment via reflection or pass via sh -c
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText().take(4000)
        val finished = process.waitFor(timeoutSecs, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); return "(timed out after ${timeoutSecs}s)" }
        return output
    }

    /** Tool definitions for the LLM */
    fun getToolDefinitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "setup_linux",
            description = "Install a full Linux environment on this Android device (~7MB). Provides: bash, sh, coreutils (ls, cp, mv, cat, grep, find, tar, etc.), apk package manager (install git, openssh, nginx, postgres, and 10,000+ packages). One-time setup. No root needed.",
            inputSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ),
        ToolDef(
            name = "run_in_linux",
            description = "Run a command inside the Linux environment (Alpine Linux with full bash/coreutils). Examples: 'ls -la', 'git clone https://...', 'apk add nodejs npm', 'python3 script.py'. /sdcard is mounted for file access. /openclaw maps to app files.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Linux command to run (bash/sh)")
                ),
                "required" to listOf("command")
            )
        ),
        ToolDef(
            name = "linux_pkg_install",
            description = "Install packages in the Linux environment using Alpine's apk. Examples: 'git openssh nginx', 'python3 py3-pip', 'nodejs npm', 'postgresql', 'build-base gcc'. Over 10,000 packages available.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "packages" to mapOf("type" to "string", "description" to "Space-separated package names (Alpine apk)")
                ),
                "required" to listOf("packages")
            )
        ),
    )

    suspend fun executeTool(name: String, args: com.google.gson.JsonObject): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (name) {
                "setup_linux" -> setup()
                "run_in_linux" -> {
                    val command = args.get("command")?.asString ?: return@withContext """{"error":"Missing 'command' parameter"}"""
                    val result = execute(command)
                    if (result.isBlank()) """{"output":"(no output)","exitCode":0}"""
                    else """{"output":${com.google.gson.Gson().toJson(result)}}"""
                }
                "linux_pkg_install" -> {
                    val packages = args.get("packages")?.asString ?: return@withContext """{"error":"Missing 'packages' parameter"}"""
                    val result = installPackage(packages)
                    """{"output":${com.google.gson.Gson().toJson(result)}}"""
                }
                else -> """{"error":"Unknown linux tool: $name"}"""
            }
        }
    }
}
