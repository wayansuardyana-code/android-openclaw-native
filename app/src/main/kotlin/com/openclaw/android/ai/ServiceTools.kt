package com.openclaw.android.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.openclaw.android.util.ServiceState
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.jsoup.Jsoup
import org.jsoup.Connection
import java.util.concurrent.TimeUnit

/**
 * External service tools that use API tokens for auth.
 * GitHub, Vercel, Supabase, Google Workspace — all via REST API.
 * Tokens are stored in AgentConfig per-provider.
 */
object ServiceTools {

    private val gson = Gson()

    fun getToolDefinitions(): List<ToolDef> = listOf(
        // ── GitHub ──
        ToolDef(
            name = "github_api",
            description = "Call the GitHub REST API. Requires GitHub token in Settings. Examples: GET /user, GET /repos/{owner}/{repo}/issues, POST /repos/{owner}/{repo}/issues",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "endpoint" to mapOf("type" to "string", "description" to "API path e.g. /user or /repos/owner/repo/issues"),
                    "method" to mapOf("type" to "string", "description" to "GET, POST, PUT, DELETE (default GET)"),
                    "body" to mapOf("type" to "string", "description" to "JSON body for POST/PUT")
                ),
                "required" to listOf("endpoint")
            )
        ),
        // ── Vercel ──
        ToolDef(
            name = "vercel_api",
            description = "Call the Vercel REST API. Requires Vercel token in Settings. Examples: GET /v9/projects, GET /v6/deployments",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "endpoint" to mapOf("type" to "string", "description" to "API path e.g. /v9/projects"),
                    "method" to mapOf("type" to "string", "description" to "GET, POST, PUT, DELETE"),
                    "body" to mapOf("type" to "string", "description" to "JSON body for POST/PUT")
                ),
                "required" to listOf("endpoint")
            )
        ),
        // ── Supabase ──
        ToolDef(
            name = "supabase_query",
            description = "Query a Supabase database via REST API. Requires Supabase URL and anon key in Settings.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "table" to mapOf("type" to "string", "description" to "Table name"),
                    "select" to mapOf("type" to "string", "description" to "Columns to select (default *)"),
                    "filter" to mapOf("type" to "string", "description" to "PostgREST filter e.g. id=eq.1"),
                    "limit" to mapOf("type" to "number", "description" to "Max rows (default 20)")
                ),
                "required" to listOf("table")
            )
        ),
        // ── Google Workspace ──
        ToolDef(
            name = "google_workspace",
            description = "Interact with Google Workspace APIs (Drive, Sheets, Docs, Calendar, Gmail). Requires Google OAuth token or service account key.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "service" to mapOf("type" to "string", "description" to "drive, sheets, docs, calendar, gmail"),
                    "action" to mapOf("type" to "string", "description" to "list, get, create, update, delete, search"),
                    "params" to mapOf("type" to "string", "description" to "JSON params for the action")
                ),
                "required" to listOf("service", "action")
            )
        ),
        // ── Generic REST API ──
        ToolDef(
            name = "authenticated_api",
            description = "Call any REST API with Bearer token auth. The token is read from the connector config.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "Full URL"),
                    "method" to mapOf("type" to "string", "description" to "GET, POST, PUT, DELETE"),
                    "body" to mapOf("type" to "string", "description" to "Request body"),
                    "token" to mapOf("type" to "string", "description" to "Bearer token (or reads from connector config)")
                ),
                "required" to listOf("url")
            )
        ),
        // ── SSH ──
        ToolDef(
            name = "ssh_execute",
            description = "Execute a command on a remote server via SSH. Requires SSH host, username, and password/key stored in connector config (provider key 'ssh').",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "command" to mapOf("type" to "string", "description" to "Shell command to execute on the remote server"),
                    "host" to mapOf("type" to "string", "description" to "SSH host (or reads from saved config)"),
                    "port" to mapOf("type" to "number", "description" to "SSH port (default 22)"),
                    "username" to mapOf("type" to "string", "description" to "SSH username (or reads from saved config)"),
                    "password" to mapOf("type" to "string", "description" to "SSH password (or reads from saved config)")
                ),
                "required" to listOf("command")
            )
        ),
        // ── PostgreSQL via SSH ──
        ToolDef(
            name = "postgres_query",
            description = "Execute a PostgreSQL query via SSH tunnel (runs psql on the remote server). Requires SSH access to a server with psql installed.",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "SQL query to execute"),
                    "database" to mapOf("type" to "string", "description" to "Database name"),
                    "ssh_host" to mapOf("type" to "string", "description" to "SSH host where psql is available"),
                    "db_host" to mapOf("type" to "string", "description" to "PostgreSQL host (default localhost)"),
                    "db_port" to mapOf("type" to "string", "description" to "PostgreSQL port (default 5432)"),
                    "db_user" to mapOf("type" to "string", "description" to "Database username")
                ),
                "required" to listOf("query", "database")
            )
        ),
    )

    suspend fun executeTool(name: String, args: JsonObject): String {
        ServiceState.addLog("Service tool: $name")
        return try {
            when (name) {
                "github_api" -> githubApi(args)
                "vercel_api" -> vercelApi(args)
                "supabase_query" -> supabaseQuery(args)
                "google_workspace" -> googleWorkspace(args)
                "authenticated_api" -> authenticatedApi(args)
                "ssh_execute" -> sshExecute(args)
                "postgres_query" -> postgresQuery(args)
                else -> """{"error":"Unknown service tool: $name"}"""
            }
        } catch (e: Exception) {
            ServiceState.addLog("Service tool error: $name — ${e.message}")
            """{"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun githubApi(args: JsonObject): String {
        val token = AgentConfig.getKeyForProvider("github")
        if (token.isBlank()) return """{"error":"GitHub token not set. Go to Settings > Connectors and add your GitHub Personal Access Token."}"""

        val endpoint = args.get("endpoint").asString
        val method = (args.get("method")?.asString ?: "GET").uppercase()
        val url = "https://api.github.com$endpoint"

        return callApi(url, method, token, args.get("body")?.asString)
    }

    private fun vercelApi(args: JsonObject): String {
        val token = AgentConfig.getKeyForProvider("vercel")
        if (token.isBlank()) return """{"error":"Vercel token not set. Go to Settings > Connectors and add your Vercel API token."}"""

        val endpoint = args.get("endpoint").asString
        val method = (args.get("method")?.asString ?: "GET").uppercase()
        val url = "https://api.vercel.com$endpoint"

        return callApi(url, method, token, args.get("body")?.asString)
    }

    private fun supabaseQuery(args: JsonObject): String {
        val supabaseUrl = AgentConfig.getKeyForProvider("supabase_url")
        val supabaseKey = AgentConfig.getKeyForProvider("supabase")
        if (supabaseUrl.isBlank() || supabaseKey.isBlank()) return """{"error":"Supabase URL or key not set. Add in Settings > Connectors."}"""

        val table = args.get("table").asString
        val select = args.get("select")?.asString ?: "*"
        val filter = args.get("filter")?.asString ?: ""
        val limit = args.get("limit")?.asInt ?: 20

        var url = "$supabaseUrl/rest/v1/$table?select=$select&limit=$limit"
        if (filter.isNotBlank()) url += "&$filter"

        val response = Jsoup.connect(url)
            .ignoreContentType(true)
            .header("apikey", supabaseKey)
            .header("Authorization", "Bearer $supabaseKey")
            .method(Connection.Method.GET)
            .timeout(10000)
            .execute()

        return """{"status":${response.statusCode()},"data":${response.body()}}"""
    }

    private fun googleWorkspace(args: JsonObject): String {
        val token = AgentConfig.getKeyForProvider("google_workspace")
        if (token.isBlank()) return """{"error":"Google Workspace token not set. You need an OAuth2 access token or service account key. Add in Settings > Connectors."}"""

        val service = args.get("service").asString
        val action = args.get("action").asString

        val baseUrl = when (service) {
            "drive" -> "https://www.googleapis.com/drive/v3"
            "sheets" -> "https://sheets.googleapis.com/v4/spreadsheets"
            "docs" -> "https://docs.googleapis.com/v1/documents"
            "calendar" -> "https://www.googleapis.com/calendar/v3"
            "gmail" -> "https://gmail.googleapis.com/gmail/v1/users/me"
            else -> return """{"error":"Unknown service: $service. Use: drive, sheets, docs, calendar, gmail"}"""
        }

        val endpoint = when {
            service == "drive" && action == "list" -> "$baseUrl/files?pageSize=20"
            service == "drive" && action == "search" -> {
                val q = args.get("params")?.asString ?: ""
                "$baseUrl/files?q=$q&pageSize=20"
            }
            service == "calendar" && action == "list" -> "$baseUrl/events?maxResults=20&orderBy=startTime&singleEvents=true"
            service == "gmail" && action == "list" -> "$baseUrl/messages?maxResults=10"
            service == "gmail" && action == "get" -> {
                val id = args.get("params")?.asString ?: ""
                "$baseUrl/messages/$id"
            }
            service == "sheets" && action == "get" -> {
                val id = args.get("params")?.asString ?: ""
                "$baseUrl/$id/values/A1:Z100"
            }
            else -> "$baseUrl"
        }

        return callApi(endpoint, "GET", token, null)
    }

    private fun authenticatedApi(args: JsonObject): String {
        val url = args.get("url").asString
        val method = (args.get("method")?.asString ?: "GET").uppercase()
        val token = args.get("token")?.asString ?: ""
        val body = args.get("body")?.asString

        return callApi(url, method, token, body)
    }

    private fun callApi(url: String, method: String, token: String, body: String?): String {
        val conn = Jsoup.connect(url)
            .ignoreContentType(true)
            .userAgent("OpenClaw-Android/0.7.0")
            .timeout(15000)
            .header("Accept", "application/json")

        if (token.isNotBlank()) {
            conn.header("Authorization", "Bearer $token")
        }

        val connMethod = when (method) {
            "POST" -> Connection.Method.POST
            "PUT" -> Connection.Method.PUT
            "DELETE" -> Connection.Method.DELETE
            "PATCH" -> Connection.Method.PATCH
            else -> Connection.Method.GET
        }
        conn.method(connMethod)

        if (body != null && method in listOf("POST", "PUT", "PATCH")) {
            conn.header("Content-Type", "application/json")
            conn.requestBody(body)
        }

        val response = conn.execute()
        val responseBody = response.body().take(4000)
        return """{"status":${response.statusCode()},"body":${responseBody}}"""
    }

    private fun sshExecute(args: JsonObject): String {
        // Get credentials from args or saved config
        val host = args.get("host")?.asString ?: AgentConfig.getKeyForProvider("ssh_host")
        val port = args.get("port")?.asInt ?: 22
        val username = args.get("username")?.asString ?: AgentConfig.getKeyForProvider("ssh_user")
        val password = args.get("password")?.asString ?: AgentConfig.getKeyForProvider("ssh")
        val command = args.get("command").asString

        if (host.isBlank()) return """{"error":"SSH host not configured. Set ssh_host, ssh_user, ssh keys in Connectors."}"""
        if (username.isBlank()) return """{"error":"SSH username not configured."}"""

        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(host, port)

        try {
            if (password.isNotBlank()) {
                ssh.authPassword(username, password)
            } else {
                // Try key-based auth
                ssh.authPublickey(username)
            }

            val session = ssh.startSession()
            val cmd = session.exec(command)

            val output = cmd.inputStream.bufferedReader().readText().take(4000)
            cmd.join(30, TimeUnit.SECONDS)
            val exitCode = cmd.exitStatus ?: -1

            session.close()
            val gson = Gson()
            return """{"exitCode":$exitCode,"output":${gson.toJson(output)},"host":"$host"}"""
        } finally {
            ssh.disconnect()
        }
    }

    private fun postgresQuery(args: JsonObject): String {
        val query = args.get("query").asString
        val database = args.get("database").asString
        val sshHost = args.get("ssh_host")?.asString ?: AgentConfig.getKeyForProvider("ssh_host")
        val dbHost = args.get("db_host")?.asString ?: "localhost"
        val dbPort = args.get("db_port")?.asString ?: "5432"
        val dbUser = args.get("db_user")?.asString ?: AgentConfig.getKeyForProvider("db_user")

        if (sshHost.isBlank()) return """{"error":"SSH host required for PostgreSQL tunnel. Set ssh_host in Connectors."}"""

        // Execute psql via SSH
        val psqlCmd = "psql -h $dbHost -p $dbPort -U $dbUser -d $database -t -A -c ${Gson().toJson(query)}"
        val sshArgs = JsonObject().apply {
            addProperty("host", sshHost)
            addProperty("command", psqlCmd)
        }
        return sshExecute(sshArgs)
    }
}
