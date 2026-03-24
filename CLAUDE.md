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

## LLM Tools (24 total)
### Android Device Tools (8)
android_read_screen, android_tap, android_swipe, android_type_text,
android_press_back, android_press_home, android_open_app, android_read_notifications

### Utility Tools (11)
run_shell_command, web_scrape, web_search, calculator,
read_file, write_file, list_files, generate_csv, http_request,
spawn_sub_agent, list_sub_agents

### Service Tools (5) — require API tokens
github_api, vercel_api, supabase_query, google_workspace, authenticated_api

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
- v0.6.0 is latest build
- v0.6.0: 13 LLM providers with dropdown, single API key field, editable model name
- v0.6.0: Toggle start/stop button, check for updates, push notification toggle
- v0.6.0: Logs copyable (SelectionContainer), Terminal tab with shell execution
- v0.6.0: File editor saves to disk, system prompt from files

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

## Remaining Nice-to-Have (not blocking)
- Chat history persistence to Room DB
- Streaming LLM responses
- Google Sign-In OAuth flow (currently manual token paste)
- On-device model (Gemma 3n) — noted, not planned yet
- MetaClaw self-learning framework — not yet implemented
