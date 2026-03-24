# OpenClaw Android Native

Autonomous AI agent that controls your Android phone through natural language.

---

## Features

- **45 LLM tools** — screen control, tap/swipe/type, media, volume, clipboard, brightness, notifications, files, web search, web scrape, HTTP requests, APIs, SSH, Python runtime
- **Auto-learn** — agent saves successful task patterns to `skills.md` automatically after 5+ step tasks
- **Multi-gateway** — respond via in-app chat, Telegram bot, or file export
- **Workspace files** — `SOUL.md`, `USER.md`, `memory.md`, `skills.md`, `TOOLS.md` — agent evolves over time
- **13 LLM providers** — Anthropic, OpenAI, Google, MiniMax, OpenRouter, Ollama, Groq, DeepSeek, xAI, Mistral, Together AI, Fireworks, Custom
- **Token-efficient** — compact screen reading (~50 tokens per element search)

---

## Requirements

- Android 7.0+ (API 24), optimized for Android 14
- Accessibility Service enabled
- LLM API key (any supported provider)

---

## Install

1. Download APK from [Releases](../../releases)
2. Install → Open → Settings → Add LLM provider (tap +) → Paste API key
3. Enable Accessibility Service when prompted
4. Chat — AI controls your phone

**Updates:** Settings → Check Updates → auto-downloads and installs.

---

## Architecture

- **Kotlin + Jetpack Compose** — native Android UI
- **AccessibilityService** — screen reading and device control across all apps
- **Ktor embedded server** — bridge API on localhost:18790
- **Room SQLite** — chat history, memories, tasks, connectors, agent sessions
- **Foreground Service** — always-on, survives screen off

---

## Tool Categories

| Category | Count | Examples |
|---|---|---|
| Android Device | 18 | read_screen, tap, swipe, type, open_app, media, volume, brightness, clipboard |
| Utility | 17 | shell, web_search, web_scrape, http_request, files, CSV/XLSX/PDF, sub_agent |
| Service | 7 | GitHub, Vercel, Supabase, Google Workspace, SSH, Postgres |
| Python | 3 | install_python, run_python, pip_install |

---

## Automation Pattern

Tasks follow: **ACT → OBSERVE → REPORT → LEARN**

1. ACT — execute tool calls
2. OBSERVE — verify results on screen
3. REPORT — push notification or chat reply
4. LEARN — append skill summary to `skills.md` if task had 5+ steps

---

## Current Version

**v1.9.1** — 45 tools, auto-learn, app interaction guide (TOOLS.md), MiniMax XML cleanup

---

## License

Private project.
