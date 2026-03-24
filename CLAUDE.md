# OpenClaw Android Native

## Project Overview
Native Android app that replaces OpenClaw-in-Termux with full device control (~90% of root capabilities without root).

## Tech Stack
- **Language:** Kotlin + Jetpack Compose
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 34 (Android 14)
- **Build:** Gradle 8.5, AGP 8.2.2, Kotlin 1.9.22
- **Architecture:** MVVM with Room database

## Key Components
- **Bridge Server** (Ktor, localhost:18790) — exposes Android APIs to Node.js/LLM
- **AccessibilityService** — screen reading, tap, swipe, type across all apps
- **NotificationListenerService** — reads all device notifications
- **Foreground Service** — keeps everything alive
- **Room Database** — connectors config, tasks, agent sessions, vector memory

## Build Commands
```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK (needs signing config)
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure
```
app/src/main/kotlin/com/openclaw/android/
├── OpenClawApplication.kt     # App init, notification channels
├── MainActivity.kt            # 5-tab navigation (Dashboard, Connectors, Files, Logs, Settings)
├── service/
│   ├── OpenClawService.kt     # Foreground service, manages bridge + Node.js
│   ├── ScreenReaderService.kt # AccessibilityService (eyes + hands)
│   ├── NotificationReaderService.kt
│   └── BootReceiver.kt        # Auto-start on boot
├── ai/
│   ├── LlmClient.kt          # Unified LLM client (Anthropic/OpenAI/Google/custom)
│   ├── AgentLoop.kt           # AI agent loop (message → LLM → tool call → loop)
│   ├── AndroidTools.kt        # 8 device control tools for LLM function calling
│   └── ToolDef.kt             # Tool definition schema
├── bridge/
│   └── AndroidBridgeServer.kt # Ktor HTTP API (16 endpoints, includes /agent/chat)
├── data/
│   ├── AppDatabase.kt         # Room database
│   ├── ConnectorRegistry.kt   # Default connector definitions (28 connectors)
│   ├── VectorSearch.kt        # Cosine similarity for memory
│   ├── entity/                # Room entities
│   └── dao/                   # Room DAOs
├── ui/
│   ├── theme/Theme.kt         # Dark theme (GitHub-style colors)
│   └── screens/               # Dashboard, Connectors, Files, Logs, Settings
└── util/
    ├── ServiceState.kt        # Shared state (StateFlow)
    └── PermissionHelper.kt    # Permission management
```

## Conventions
- Dark theme only, monospace font (terminal aesthetic)
- Colors: BG=#0D1117, Surface=#161B22, Cyan=#58A6FF, Green=#3FB950, Red=#F85149
- All Android system access via Bridge Server HTTP API on localhost
- Connectors are configurable tools/APIs stored in Room database
- No Node.js runtime yet (Phase 1b) — bridge server is standalone

## Bridge API Endpoints
```
GET  /android/health
GET  /android/screen           # Accessibility tree JSON
POST /android/tap              # {x, y}
POST /android/swipe            # {x1, y1, x2, y2}
POST /android/type             # {text}
POST /android/back|home|recents
POST /android/open-app         # {packageName}
GET  /android/notifications
GET  /android/battery
POST /android/volume           # {level, stream}
GET  /android/contacts
GET  /android/health              # Service status
POST /agent/chat                  # {message, provider, apiKey, model, baseUrl} → AI agent
```

## AI Agent System
- LlmClient supports Anthropic, OpenAI, Google, MiniMax, OpenRouter, Ollama, custom APIs
- AgentLoop implements tool-calling loop: user msg → LLM → tool call → execute → loop → final response
- 8 Android tools exposed to LLM: read_screen, tap, swipe, type_text, press_back, press_home, open_app, read_notifications
- POST /agent/chat endpoint accepts {message, provider, apiKey, model, baseUrl}
- Max 10 tool-calling steps per agent run

## LLM Tools (45 total)
### Android Device Tools (18)
android_read_screen, find_element, read_region,
android_tap, android_long_press, android_swipe, android_type_text,
android_press_back, android_press_home, android_press_enter,
android_open_app, android_launch_url, android_scroll_to_text,
android_media_control (play/pause/next/prev/stop), android_volume (set/up/down/mute),
android_set_brightness, android_get_clipboard, android_set_clipboard,
android_wifi_toggle, android_read_notifications,
take_screenshot, shizuku_command

### Utility Tools (17)
run_shell_command, web_scrape, web_search, calculator,
read_file, write_file, list_files, generate_csv, generate_xlsx, generate_pdf,
http_request, spawn_sub_agent, list_sub_agents,
read_workspace_file, update_workspace_file,
send_telegram_message, send_telegram_photo

### Service Tools (7) — require API tokens
github_api, vercel_api, supabase_query, google_workspace, authenticated_api,
ssh_execute, postgres_query (via SSH tunnel)

### Python Runtime (3)
install_python, run_python, pip_install

## App Interaction Guide (TOOLS.md)
- TOOLS.md is a workspace file the agent reads at startup — contains per-app interaction patterns
- Documents how to navigate specific apps: button labels, tap sequences, known quirks
- Agent auto-updates TOOLS.md when it discovers a new interaction pattern during a task
- Example entries: how to compose a WhatsApp message, how to find settings in Instagram, etc.
- Agents should check TOOLS.md before attempting to control an unfamiliar app

## Auto-Learn System (skills.md)
- skills.md is auto-populated after the agent completes any task with 5+ steps
- Agent summarizes the task as a reusable skill: goal, steps taken, tools used, outcome
- On future tasks, agent reads skills.md first to check for applicable patterns
- Skills accumulate over time — agent gets faster at repeated task types
- Manual editing supported via Files tab

## ACT → OBSERVE → REPORT → LEARN Pattern
The agent follows a 4-phase automation loop for multi-step tasks:
1. **ACT** — Execute the plan (tool calls, screen taps, data operations)
2. **OBSERVE** — Read screen / check results / verify success
3. **REPORT** — Summarize what happened (push notification or chat reply)
4. **LEARN** — If task had 5+ steps, append skill summary to skills.md

This pattern ensures tasks are verifiable, results are communicated, and knowledge accumulates.

## MiniMax XML Cleanup (cleanLlmJson)
- MiniMax models sometimes return XML-wrapped JSON or partial XML tags in tool call responses
- cleanLlmJson() utility strips XML envelope tags before JSON parsing
- Prevents tool-call parse failures when using MiniMax M2.7 or VL-01
- Applied automatically in AgentLoop before processing LLM tool call responses

## File System
- Agent config files: `filesDir/agent_config/` (identity.md, memory.md, system_prompt.md, skills.md)
- Chat reads system_prompt.md + identity.md and injects into LLM system prompt
- Files are editable from the Files tab in the app
- API keys persisted via SharedPreferences (AgentConfig.kt)

## Development Notes
- PostgreSQL JDBC removed (Android compat issue with MethodHandle on API <26) — use HTTP proxy instead
- Shizuku integration declared but not yet connected
- nodejs-mobile integration optional — Kotlin-native AI agent works standalone
- APK served via `python3 -m http.server 8899` on VPS for download
- v1.9.1 is latest build
- v1.9.1: 45 LLM tools (18 Android device + 17 utility + 7 service + 3 Python)
- v1.9.1: Auto-learn (skills.md auto-populated after 5+ step tasks)
- v1.9.1: ACT → OBSERVE → REPORT → LEARN automation pattern
- v1.9.1: App interaction guide in TOOLS.md
- v1.9.1: MiniMax XML cleanup (cleanLlmJson) for stable tool call parsing
- v0.6.0: 13 LLM providers with dropdown, single API key field, editable model name
- v0.6.0: Toggle start/stop button, check for updates, push notification toggle
- v0.6.0: Logs copyable (SelectionContainer), Terminal tab with shell execution
- v0.6.0: File editor saves to disk, system prompt from files

## SSH & PostgreSQL
- ssh_execute: SSHJ library, connects to remote server, runs commands, 30s timeout
- postgres_query: runs psql via SSH tunnel (no direct JDBC needed)
- SSH credentials stored per-provider: ssh_host, ssh_user, ssh (password)
- PostgreSQL: db_user, db_host, db_port stored in AgentConfig

## Auth Connectors (how tokens are stored)
- All service tokens stored via AgentConfig.setKeyForProvider(provider, key)
- GitHub: Personal Access Token (github.com/settings/tokens)
- Vercel: API Token (vercel.com/account/tokens)
- Supabase: anon key + URL (supabase.com/dashboard/project/settings/api)
- Google Workspace: OAuth2 access token or service account key
- Generic: any Bearer token for any REST API

## Chat Features
- 16 slash commands (type "/" to see autocomplete popup)
- File attachment via paperclip icon (reads text content, sends to LLM)
- System messages for /status, /tools, /help, /identity, /prompt
- Messages are selectable (long-press to copy)

## v0.7.0 Changelog
- 22 LLM tools (8 device + 9 utility + 5 service)
- 13 LLM providers with dropdown selector
- Slash commands with autocomplete popup
- File attachment in chat
- Copyable logs + Terminal tab with shell execution
- Service connectors (GitHub, Vercel, Supabase, Google Workspace)
- Single toggle start/stop, check for updates, push notification toggle

## Sub-Agent System
- SubAgentManager: orchestrator pattern, main agent spawns background tasks
- spawn_sub_agent tool: LLM delegates work, chat stays responsive
- Tasks auto-flow through kanban: Pending → Active → Done
- Push notification on completion/failure
- list_sub_agents: check running/completed agents

## ModelRegistry
- 50+ models across 13 providers
- Per-provider model dropdown (no typing)
- Mirrors OpenClaw ecosystem model support

## Dashboard Hardware Monitor
- Real-time: RAM usage/total, storage used/free, battery level
- LLM token tracker
- Refreshes every 5 seconds

## v0.9.0 Complete Feature List (replaces v0.8.0)
- Dashboard: kanban board (Inbox/Active/Review/Done), add tasks, tap to advance
- Connectors: tokens persist to AgentConfig on save (wired to tool auth)
- Push notifications: fires on agent response + agent error
- 22 LLM tools, 13 providers, slash commands, file attach, terminal
- All core features working — app is usable for daily AI assistance

## Code Audit (v0.8.1) — All 14 issues fixed
- WakeLock: no timeout, released in onDestroy
- LlmClient: DisposableEffect cleanup
- ChatScreen: try/catch prevents UI lockup
- Shell: 30s timeout + destroyForcibly
- Bridge API: safe casts, no ClassCastException
- ServiceState: atomic StateFlow.update()
- @Volatile on service instances
- JSON escaping via Gson.toJson()
- Version consistent: 0.8.0-alpha everywhere
- Connector tokens persist to AgentConfig

## Vector Memory (built-in SQLite)
- Room DB has MemoryEntity with embedding field (JSON float array)
- VectorSearch.kt: cosine similarity search over memory embeddings
- MemoryDao: CRUD + prune old unused + access counting
- This is local semantic vector memory — NOT file-based RAG
- Embeddings can come from any provider (Cohere, OpenAI, etc.) via http_request tool

## Google Workspace Integration
- GWS CLI (googleworkspace/cli) is a Rust binary, NOT suitable for Android embedding
- Instead: call Google REST APIs directly with Bearer token auth
- google_workspace tool supports Drive, Sheets, Docs, Calendar, Gmail
- Auth: user provides OAuth2 access token (from Google Sign-In or manual)
- Endpoints: googleapis.com/drive/v3, sheets/v4, gmail/v1, calendar/v3

## Conversation Manager
- ConversationManager: tracks full conversation history across messages
- Token estimation: 1 token ≈ 4 chars
- Context window limits per provider (MiniMax=80k, Anthropic=200k, Google=1M, etc.)
- Auto-compaction at 70% of context window — summarizes old messages, keeps last 4
- Token display in chat header: "1.2k / 80k"
- /clear resets both chat UI and conversation history

## Crash Handler
- Thread.setDefaultUncaughtExceptionHandler writes crash to filesDir/crash_logs/
- Crash Logs tab in Logs screen, auto-switches to it if crash detected
- Crash report includes: time, thread, Android version, device, full stack trace

## Voice Input
- Android built-in SpeechRecognizer (zero dependency, free)
- Default language: Indonesian (id-ID) with auto-detect
- Partial results show in real-time as user speaks
- Future option: Vosk (com.alphacephei:vosk-android:0.3.75) for 100% offline (~40MB model)

## Telegram Bot
- TelegramBotService: long-polling getUpdates → AgentLoop → sendMessage
- Auto-starts if telegram token is configured in AgentConfig
- Add token via Settings → Services → "+" → Telegram Bot
- Tracks update_id offset, reconnects on error with 5s backoff

## Settings UI (v1.1.0+)
- Fully dynamic — LLM section empty by default, add with "+" button
- Services section: "+" to connect from 15 available services
- No hardcoded lists — only shows what user has configured
- Token cards: eye toggle, copy, delete

## Zuma Business Skills (generic ones applicable here)
- data-storytelling: narrative frameworks (SCQA, pyramid) — prompt template
- deploy-to-live: git + Vercel deploy workflow — already covered by tools
- markitdown: file→markdown converter — needs Python runtime
- xlsx-skill: Excel generation — already wired as generate_xlsx
- ppt-design: HTML slide generation — possible via write_file + html
- image-gen: Gemini image generation — possible via http_request to Gemini API

## Remaining Nice-to-Have (not blocking)
- Streaming LLM responses
- Google Sign-In OAuth flow (currently manual token paste)
- On-device model (Gemma 3n) — noted, not planned yet
- MetaClaw self-learning framework — not yet implemented
- Bootstrap.md auto-generation on first connect
