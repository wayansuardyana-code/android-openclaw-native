package com.openclaw.android.data

import com.openclaw.android.data.entity.ConnectorEntity

object ConnectorRegistry {
    fun getDefaults(): List<ConnectorEntity> = listOf(
        // ── LLM Providers ──
        ConnectorEntity("llm_anthropic", "llm", "Anthropic Claude", "Claude Sonnet, Opus, Haiku", "Psychology", configJson = """{"apiKey":"","model":"claude-sonnet-4-6","baseUrl":"https://api.anthropic.com"}"""),
        ConnectorEntity("llm_openai", "llm", "OpenAI", "GPT-5, GPT-4o, o-series", "AutoAwesome", configJson = """{"apiKey":"","model":"gpt-5.4","baseUrl":"https://api.openai.com"}"""),
        ConnectorEntity("llm_google", "llm", "Google Gemini", "Gemini 3.1, 2.5 Pro/Flash", "AutoAwesome", configJson = """{"apiKey":"","model":"gemini-3.1-pro","baseUrl":"https://generativelanguage.googleapis.com"}"""),
        ConnectorEntity("llm_minimax", "llm", "MiniMax", "MiniMax M2.5 (current AdvanClaw)", "AutoAwesome", configJson = """{"apiKey":"","model":"m2.5","baseUrl":"https://api.minimax.io/anthropic"}"""),
        ConnectorEntity("llm_openrouter", "llm", "OpenRouter", "Multi-provider gateway", "Router", configJson = """{"apiKey":"","model":"","baseUrl":"https://openrouter.ai/api"}"""),
        ConnectorEntity("llm_ollama", "llm", "Ollama (Local)", "Self-hosted models", "Dns", configJson = """{"baseUrl":"http://localhost:11434","model":"llama3.2"}"""),
        ConnectorEntity("llm_custom", "llm", "Custom API", "Any OpenAI-compatible API", "Api", configJson = """{"apiKey":"","model":"","baseUrl":""}"""),

        // ── Channels ──
        ConnectorEntity("ch_telegram", "channel", "Telegram Bot", "Send/receive via Telegram", "Telegram", configJson = """{"botToken":"","allowedUsers":""}"""),
        ConnectorEntity("ch_discord", "channel", "Discord Bot", "Discord server integration", "Forum", configJson = """{"botToken":"","guildId":""}"""),
        ConnectorEntity("ch_sms", "channel", "SMS", "Native Android SMS", "Sms", configJson = """{"enabled":true}"""),

        // ── Databases ──
        ConnectorEntity("db_postgres", "database", "PostgreSQL", "Remote PostgreSQL connection", "Storage", configJson = """{"host":"","port":"5432","database":"","username":"","password":"","sslMode":"require"}"""),
        ConnectorEntity("db_sqlite_local", "database", "SQLite (Built-in)", "Local on-device database", "SdStorage", enabled = true, status = "connected", configJson = """{"path":"openclaw.db"}"""),

        // ── Developer Tools ──
        ConnectorEntity("tool_terminal", "tool", "Terminal / Shell", "Execute shell commands", "Terminal", configJson = """{"shell":"sh","workDir":"/data/data/com.openclaw.android/files"}"""),
        ConnectorEntity("tool_ssh", "tool", "SSH Remote", "Connect to remote servers", "Laptop", configJson = """{"host":"","port":"22","username":"","password":"","keyPath":""}"""),
        ConnectorEntity("tool_github", "tool", "GitHub", "Repos, issues, PRs, code search", "Code", configJson = """{"token":"","defaultOrg":"","defaultRepo":""}"""),
        ConnectorEntity("tool_vercel", "tool", "Vercel", "Deployments, projects, env vars", "Cloud", configJson = """{"token":"","teamId":""}"""),
        ConnectorEntity("tool_web_scrape", "tool", "Web Scraper", "Fetch & parse web pages", "Language", configJson = """{"userAgent":"OpenClaw/1.0","timeout":"10000"}"""),
        ConnectorEntity("tool_search", "tool", "Web Search", "Brave/DuckDuckGo search", "Search", configJson = """{"provider":"duckduckgo","braveApiKey":""}"""),
        ConnectorEntity("tool_calculator", "tool", "Calculator", "Math expressions, statistics", "Calculate", configJson = """{"precision":"10"}"""),
        ConnectorEntity("tool_python", "tool", "Python (Remote)", "Execute Python via SSH", "DataObject", configJson = """{"sshConnectorId":"tool_ssh","pythonPath":"python3"}"""),

        // ── File Generation ──
        ConnectorEntity("file_xlsx", "file_gen", "Excel (XLSX)", "Generate spreadsheets", "TableChart", configJson = """{"defaultPath":"/storage/emulated/0/Documents/OpenClaw"}"""),
        ConnectorEntity("file_csv", "file_gen", "CSV", "Generate CSV data files", "Description", configJson = """{"delimiter":",","encoding":"UTF-8"}"""),
        ConnectorEntity("file_pdf", "file_gen", "PDF", "Generate PDF documents", "PictureAsPdf", configJson = """{"defaultPath":"/storage/emulated/0/Documents/OpenClaw"}"""),

        // ── Skills & Extensions ──
        ConnectorEntity("skill_clawhub", "skill", "ClawHub Skills", "Install skills from ClawHub registry", "Extension", configJson = """{"registryUrl":"https://clawhub.openclaw.dev"}"""),
        ConnectorEntity("skill_github_repo", "skill", "GitHub Skill Repo", "Load skills from any GitHub repo", "GitHub", configJson = """{"repoUrl":"","branch":"main","skillPath":"skills/"}"""),
        ConnectorEntity("skill_custom", "skill", "Custom Skills", "Local skill files", "Build", configJson = """{"skillDir":"/data/data/com.openclaw.android/files/skills"}"""),
    )
}
