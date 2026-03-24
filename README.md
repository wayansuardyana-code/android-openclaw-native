# OpenClaw Android Native

> Autonomous AI agent with full Android device control — no root required.

Turn your phone into an AI-controlled device. OpenClaw can see your screen, tap buttons, type text, open apps, read notifications, browse the web, run Python scripts, generate files, and connect to external services — all through natural language.

---

## Quick Start

1. Download APK from [Releases](https://github.com/wayansuardyana-code/android-openclaw-native/releases)
2. Install → Open → Settings → Add LLM provider (tap +) → Paste API token
3. Chat → Start talking → AI controls your phone

**Updates:** Settings → Check Updates → auto-downloads + installs. All settings preserved.

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│  ANDROID NATIVE APP (Kotlin + Jetpack Compose)   │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  SYSTEM LAYER                            │    │
│  │  Accessibility · Notifications           │    │
│  │  MediaProjection · Shizuku               │    │
│  └────────────────┬─────────────────────────┘    │
│                   │ localhost HTTP                │
│  ┌────────────────▼─────────────────────────┐    │
│  │  BRIDGE SERVER (Ktor, :18790)            │    │
│  │  16 REST endpoints · Agent chat API      │    │
│  └────────────────┬─────────────────────────┘    │
│                   │                              │
│  ┌────────────────▼─────────────────────────┐    │
│  │  AI BRAIN                                │    │
│  │  31 tools · Sub-agents · Python runtime  │    │
│  │  Conversation memory · Auto-compaction   │    │
│  └──────────────────────────────────────────┘    │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │  STORAGE                                 │    │
│  │  Room SQLite: chat history, memories,    │    │
│  │  tasks, connectors, agent sessions       │    │
│  │  Vector search: cosine similarity        │    │
│  │  Workspace files: SOUL.md, USER.md, etc  │    │
│  └──────────────────────────────────────────┘    │
└──────────────────────────────────────────────────┘
```

---

## Features

### AI Chat
- Natural language conversation with tool-calling AI agent
- 16 slash commands (`/tools`, `/search`, `/screen`, `/shell`, etc.)
- File attachment (paperclip icon)
- Voice input (mic icon, Android SpeechRecognizer)
- Tap message to copy
- Chat history persists in SQLite (survives restarts + updates)
- Auto-compaction at 70% context window
- Token counter: `1.2k / 80k`

### Device Control (8 tools)
- Read screen content of any app (accessibility tree)
- Tap, swipe, type text at coordinates
- Open any installed app
- Read all device notifications
- Press back/home buttons

### Utility Tools (13 tools)
- Shell command execution (30s timeout)
- Web search (DuckDuckGo, free)
- Web scraping (Jsoup)
- HTTP requests (any REST API)
- Calculator (exp4j math expressions)
- File read/write/list
- CSV, Excel (XLSX), PDF generation
- Sub-agent spawning (background task delegation)
- Python 3.13 runtime (on-device, no root)

### Service Connectors (7 tools)
- GitHub REST API
- Vercel REST API
- Supabase PostgREST
- Google Workspace (Drive, Sheets, Gmail, Calendar)
- SSH remote execution
- PostgreSQL via SSH tunnel
- Generic authenticated REST API

### Python Runtime
- Portable Python 3.13 (musl-static, ~27MB)
- Auto-downloads on first use
- pip install any package (pandas, matplotlib, etc.)
- Enables: markitdown, data analysis, infographic generation

### Telegram Bot
- Long-polling bot connector
- Control phone from Telegram
- Full tool access from Telegram messages
- Add bot token in Connect → Developer Tools

### Dashboard
- Mission Control with status grid
- Hardware monitor (RAM, storage, battery, tokens)
- Kanban board (Pending → Active → Done)
- Auto-managed by agent (orchestrator pattern)

### Workspace Files (auto-bootstrapped)
- `SOUL.md` — Agent personality and identity
- `USER.md` — Owner profile and preferences
- `AGENTS.md` — Workspace conventions
- `TOOLS.md` — Tool usage notes and credentials
- `HEARTBEAT.md` — Self-check patterns
- `identity.md`, `system_prompt.md`, `memory.md`, `skills.md`, `bootstrap.md`

### Settings
- 13 LLM providers (dynamic, add with +)
- Model dropdown per provider (50+ models)
- Token management (eye toggle, copy, delete)
- Auto-updater (Check Updates → download → install)
- Push notifications toggle

---

## LLM Providers

| Provider | Models |
|----------|--------|
| Anthropic | Claude Opus/Sonnet/Haiku 4.x |
| OpenAI | GPT-5.4, GPT-4.1, o3/o4 |
| MiniMax | M2.7, VL-01, M2.5 |
| Google | Gemini 2.5 Pro/Flash |
| DeepSeek | V3, R1 |
| Mistral | Large, Small, Codestral |
| Groq | Llama 3.3, Mixtral, Gemma |
| xAI | Grok 2, Grok 3 |
| Together AI | Llama, Qwen, DeepSeek |
| Fireworks | Llama, Qwen |
| OpenRouter | Any model |
| Ollama | Local models |
| Custom | Any OpenAI-compatible API |

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 1.9 |
| UI | Jetpack Compose + Material 3 |
| Database | Room (SQLite) + vector search |
| HTTP Server | Ktor (Netty) |
| HTTP Client | Ktor + OkHttp |
| Excel | FastExcel |
| PDF | Android PdfDocument |
| Web Scraping | Jsoup |
| SSH | SSHJ |
| CSV | kotlin-csv |
| Math | exp4j |
| Speech | Android SpeechRecognizer |
| Python | Portable musl-static 3.13 |

---

## Project Structure

```
app/src/main/kotlin/com/openclaw/android/
├── MainActivity.kt              # 5-tab navigation
├── OpenClawApplication.kt       # App init + crash handler
├── ai/
│   ├── LlmClient.kt            # Unified LLM client (13 providers)
│   ├── AgentLoop.kt             # Tool-calling agent loop
│   ├── AgentConfig.kt           # Persistent config (SharedPreferences)
│   ├── AndroidTools.kt          # Device control tools
│   ├── UtilityTools.kt          # Shell, web, files, CSV, XLSX, PDF
│   ├── ServiceTools.kt          # GitHub, Vercel, Supabase, SSH, Postgres
│   ├── PythonRuntime.kt         # Portable Python on Android
│   ├── SubAgentManager.kt       # Background task delegation
│   ├── ConversationManager.kt   # Token tracking + auto-compaction
│   ├── ModelRegistry.kt         # 50+ models per provider
│   ├── Bootstrap.kt             # Workspace file initialization
│   └── ToolDef.kt               # Tool schema
├── service/
│   ├── OpenClawService.kt       # Foreground service
│   ├── ScreenReaderService.kt   # Accessibility (eyes + hands)
│   ├── NotificationReaderService.kt
│   ├── TelegramBotService.kt    # Telegram bot polling
│   └── BootReceiver.kt          # Auto-start on boot
├── bridge/
│   └── AndroidBridgeServer.kt   # REST API (16 endpoints)
├── data/
│   ├── AppDatabase.kt           # Room database (v2)
│   ├── entity/                  # ChatMessage, Connector, Task, Memory, AgentSession
│   ├── dao/                     # CRUD + search
│   ├── ConnectorRegistry.kt     # Service definitions
│   └── VectorSearch.kt          # Cosine similarity
├── ui/screens/
│   ├── ChatScreen.kt            # AI chat + slash commands + voice
│   ├── DashboardScreen.kt       # Mission Control + kanban + hardware
│   ├── ConnectorsScreen.kt      # Services + active skills
│   ├── FilesScreen.kt           # Workspace file editor
│   ├── LogScreen.kt             # Logs + Terminal + Crash viewer
│   └── SettingsScreen.kt        # LLM config + updates
└── util/
    ├── ServiceState.kt          # Reactive state
    ├── PermissionHelper.kt      # Permission management
    ├── NotificationHelper.kt    # Push notifications
    └── AppUpdater.kt            # Auto-update from GitHub
```

---

## Stats

| Metric | Value |
|--------|-------|
| APK size | ~26 MB |
| Kotlin files | 40+ |
| Lines of code | 5,000+ |
| LLM tools | 31 |
| LLM providers | 13 |
| Models | 50+ |
| Slash commands | 16 |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |

---

## License

Private project.
