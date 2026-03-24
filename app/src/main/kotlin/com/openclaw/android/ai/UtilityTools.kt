package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.OpenClawApplication
import com.openclaw.android.util.ServiceState
import net.objecthunter.exp4j.ExpressionBuilder
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Non-Android utility tools: shell, web scraper, calculator, file gen.
 * These use the deps already in build.gradle (Jsoup, exp4j, FastExcel, kotlin-csv, SSHJ).
 */
object UtilityTools {

    fun getToolDefinitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "run_shell_command",
            description = "Execute a shell command on the Android device. Returns stdout and stderr. Use for: listing files, checking processes, network info, etc.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute")
                ),
                "required" to listOf("command")
            )
        ),
        ToolDef(
            name = "web_scrape",
            description = "Fetch a web page and extract its text content, title, and links. Good for reading articles, checking websites, getting information.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "URL to fetch"),
                    "selector" to mapOf("type" to "string", "description" to "Optional CSS selector to extract specific content")
                ),
                "required" to listOf("url")
            )
        ),
        ToolDef(
            name = "web_search",
            description = "Search the web using DuckDuckGo. Returns top results with titles, URLs, and snippets.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "Search query")
                ),
                "required" to listOf("query")
            )
        ),
        ToolDef(
            name = "calculator",
            description = "Evaluate a math expression. Supports: +, -, *, /, ^, sqrt, sin, cos, tan, log, abs, ceil, floor, pi, e. Example: '2 * sin(pi/4) + sqrt(16)'",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf("type" to "string", "description" to "Math expression to evaluate")
                ),
                "required" to listOf("expression")
            )
        ),
        ToolDef(
            name = "read_file",
            description = "Read the contents of a file on the device.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Absolute file path")
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "write_file",
            description = "Write content to a file on the device. Creates parent directories if needed.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Absolute file path"),
                    "content" to mapOf("type" to "string", "description" to "File content to write")
                ),
                "required" to listOf("path", "content")
            )
        ),
        ToolDef(
            name = "list_files",
            description = "List files and directories at a given path.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Directory path to list")
                ),
                "required" to listOf("path")
            )
        ),
        ToolDef(
            name = "generate_csv",
            description = "Generate a CSV file. Provide headers and rows as arrays.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "filename" to mapOf("type" to "string", "description" to "Output filename (saved to Documents/OpenClaw/)"),
                    "headers" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "rows" to mapOf("type" to "array", "items" to mapOf("type" to "array", "items" to mapOf("type" to "string")))
                ),
                "required" to listOf("filename", "headers", "rows")
            )
        ),
        ToolDef(
            name = "http_request",
            description = "Make an HTTP GET or POST request to any URL. Good for calling APIs.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "Request URL"),
                    "method" to mapOf("type" to "string", "description" to "GET or POST (default GET)"),
                    "body" to mapOf("type" to "string", "description" to "Request body for POST"),
                    "headers" to mapOf("type" to "object", "description" to "Optional headers as key-value pairs")
                ),
                "required" to listOf("url")
            )
        ),
    )

    suspend fun executeTool(name: String, args: JsonObject): String {
        ServiceState.addLog("Utility tool: $name")
        return try {
            when (name) {
                "run_shell_command" -> runShell(args.get("command").asString)
                "web_scrape" -> webScrape(args.get("url").asString, args.get("selector")?.asString)
                "web_search" -> webSearch(args.get("query").asString)
                "calculator" -> calculate(args.get("expression").asString)
                "read_file" -> readFile(args.get("path").asString)
                "write_file" -> writeFile(args.get("path").asString, args.get("content").asString)
                "list_files" -> listFiles(args.get("path").asString)
                "generate_csv" -> generateCsv(args)
                "http_request" -> httpRequest(args)
                else -> """{"error":"Unknown tool: $name"}"""
            }
        } catch (e: Exception) {
            ServiceState.addLog("Utility tool error: $name — ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun runShell(command: String): String {
        val blocked = listOf("rm -rf /", "mkfs", "dd if=", ":(){ :|:&", "format")
        if (blocked.any { command.contains(it) }) {
            return """{"error":"Command blocked for safety"}"""
        }

        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream))
            .readText()
            .take(4000)

        val finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return """{"error":"Command timed out after 30 seconds"}"""
        }
        val exitCode = process.exitValue()
        val escaped = com.google.gson.Gson().toJson(output) // proper JSON escaping
        return """{"exitCode":$exitCode,"output":$escaped}"""
    }

    private fun webScrape(url: String, selector: String?): String {
        val doc = Jsoup.connect(url)
            .userAgent("OpenClaw/1.0")
            .timeout(10000)
            .get()

        val title = doc.title()
        val content = if (selector != null) {
            doc.select(selector).text()
        } else {
            doc.body().text().take(3000)
        }

        return """{"title":"${title.replace("\"", "'")}","content":"${content.replace("\"", "'").replace("\n", " ").take(3000)}"}"""
    }

    private fun webSearch(query: String): String {
        // DuckDuckGo HTML search (no API key needed)
        val doc = Jsoup.connect("https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            .userAgent("OpenClaw/1.0")
            .timeout(10000)
            .get()

        val results = doc.select(".result__body").take(5).mapIndexed { i, el ->
            val resultTitle = el.select(".result__title").text()
            val snippet = el.select(".result__snippet").text()
            val link = el.select(".result__url").text()
            """{"rank":${i + 1},"title":"${resultTitle.replace("\"", "'")}","snippet":"${snippet.replace("\"", "'")}","url":"${link.replace("\"", "'")}"}"""
        }

        return """{"query":"${query.replace("\"", "'")}","results":[${results.joinToString(",")}]}"""
    }

    private fun calculate(expression: String): String {
        val result = ExpressionBuilder(expression)
            .build()
            .evaluate()
        return """{"expression":"${expression.replace("\"", "'")}","result":$result}"""
    }

    private fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) return """{"error":"File not found: $path"}"""
        if (!file.canRead()) return """{"error":"Cannot read file: $path"}"""
        val content = file.readText().take(4000)
        return """{"path":"$path","size":${file.length()},"content":"${content.replace("\"", "\\\"").replace("\n", "\\n")}"}"""
    }

    private fun writeFile(path: String, content: String): String {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return """{"success":true,"path":"$path","size":${file.length()}}"""
    }

    private fun listFiles(path: String): String {
        val dir = File(path)
        if (!dir.exists()) return """{"error":"Directory not found: $path"}"""
        val files = dir.listFiles()?.map { f ->
            """{"name":"${f.name}","isDir":${f.isDirectory},"size":${f.length()}}"""
        } ?: emptyList()
        return """{"path":"$path","files":[${files.joinToString(",")}]}"""
    }

    private fun generateCsv(args: JsonObject): String {
        val filename = args.get("filename").asString
        val headers = args.getAsJsonArray("headers").map { it.asString }
        val rows = args.getAsJsonArray("rows").map { row ->
            row.asJsonArray.map { it.asString }
        }

        val dir = File(OpenClawApplication.instance.getExternalFilesDir(null), "documents")
        dir.mkdirs()
        val file = File(dir, filename)

        val sb = StringBuilder()
        sb.appendLine(headers.joinToString(",") { "\"$it\"" })
        rows.forEach { row ->
            sb.appendLine(row.joinToString(",") { "\"$it\"" })
        }
        file.writeText(sb.toString())

        return """{"success":true,"path":"${file.absolutePath}","rows":${rows.size}}"""
    }

    private fun httpRequest(args: JsonObject): String {
        val url = args.get("url").asString
        val method = args.get("method")?.asString ?: "GET"

        val conn = Jsoup.connect(url)
            .ignoreContentType(true)
            .userAgent("OpenClaw/1.0")
            .timeout(15000)
            .method(if (method.uppercase() == "POST") org.jsoup.Connection.Method.POST else org.jsoup.Connection.Method.GET)

        if (args.has("body")) {
            conn.requestBody(args.get("body").asString)
        }

        val response = conn.execute()
        val body = response.body().take(4000)

        return """{"status":${response.statusCode()},"body":"${body.replace("\"", "\\\"").replace("\n", "\\n")}"}"""
    }
}
