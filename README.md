# OpenClaw Android Native

> AI agent with full Android device control — no root required.

Replace Termux-based AI assistants with a native Android app that can see your screen, read your notifications, tap buttons, open apps, and control your entire phone through natural language.

---

## What This Does

OpenClaw Android Native turns your phone into an AI-controlled device. The app runs as a persistent service with system-level access:

- **See everything** — reads the screen of any app via AccessibilityService
- **Control everything** — taps, swipes, types text in any app
- **Read all notifications** — WhatsApp messages, bank OTPs, emails
- **Open any app** — launch apps by package name
- **Access hardware** — camera, GPS, microphone, sensors
- **Read contacts, SMS, calendar** — full ContentProvider access
- **Generate files** — Excel (XLSX), CSV, PDF
- **Connect to databases** — PostgreSQL, SQLite
- **SSH into servers** — remote shell access
- **Scrape the web** — fetch and parse any webpage
- **Run on boot** — auto-starts with your phone

All without rooting your device.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  ANDROID NATIVE APP (Kotlin + Jetpack Compose)      │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  SYSTEM LAYER                                 │  │
│  │  AccessibilityService · NotificationListener  │  │
│  │  MediaProjection · Shizuku · ContentProviders │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │ localhost HTTP                  │
│  ┌──────────────────▼────────────────────────────┐  │
│  │  BRIDGE SERVER (Ktor, :18790)                 │  │
│  │  15 REST endpoints for device control         │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │                                │
│  ┌──────────────────▼────────────────────────────┐  │
│  │  AI BRAIN                                     │  │
│  │  LLM providers · Channels · Tool pipeline     │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

---

## Screenshots

| Dashboard | Connectors | Settings |
|:---------:|:----------:|:--------:|
| Mission Control with status, quick actions, task kanban, logs | 28 configurable connectors grouped by category | LLM provider selection, API keys, service control |

---

## Bridge API

The app runs a localhost HTTP server exposing Android capabilities:

```bash
# Screen control
GET  /android/screen              # Full accessibility tree (JSON)
POST /android/tap                 # {"x": 500, "y": 800}
POST /android/swipe               # {"x1": 100, "y1": 500, "x2": 100, "y2": 200}
POST /android/type                # {"text": "hello world"}

# Navigation
POST /android/back
POST /android/home
POST /android/open-app            # {"packageName": "com.whatsapp"}

# Notifications
GET  /android/notifications       # All active notifications
GET  /android/notifications/recent

# Device
GET  /android/battery             # {"level": 63, "charging": false}
POST /android/volume              # {"level": 50, "stream": "music"}
GET  /android/contacts            # Contact list
GET  /android/health              # Service status
```

---

## Connectors (28 built-in)

### LLM Providers
| Connector | Models |
|-----------|--------|
| Anthropic Claude | Sonnet, Opus, Haiku |
| OpenAI | GPT-5, GPT-4o, o-series |
| Google Gemini | Gemini 3.1, 2.5 Pro/Flash |
| MiniMax | M2.5 |
| OpenRouter | Multi-provider gateway |
| Ollama | Self-hosted local models |
| Custom API | Any OpenAI-compatible endpoint |

### Channels
Telegram Bot, Discord Bot, Native SMS

### Developer Tools
Terminal/Shell, SSH Remote, GitHub, Vercel, Web Scraper, Web Search, Calculator, Python (via SSH)

### Databases
PostgreSQL (via HTTP proxy), SQLite (built-in with vector search)

### File Generation
Excel (XLSX), CSV, PDF

### Skills & Extensions
ClawHub registry, GitHub repos, Custom local skills

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin 1.9 |
| UI | Jetpack Compose + Material 3 |
| Database | Room (SQLite) + cosine similarity vector search |
| HTTP Server | Ktor (Netty) |
| HTTP Client | Ktor + OkHttp |
| Excel | FastExcel |
| Web Scraping | Jsoup |
| SSH | SSHJ |
| CSV | kotlin-csv |
| Math | exp4j |
| Privilege | Shizuku (ADB-level without root) |

---

## Device Compatibility

| | Supported |
|---|---|
| **Min Android** | 7.0 (API 24) |
| **Architectures** | arm64-v8a, armeabi-v7a, x86_64 |
| **Coverage** | 99%+ of active Android devices |
| **Root required** | No |
| **Google Play Services** | Not required |

### Permission Tiers

| Tier | What You Get |
|------|-------------|
| **Standard** (dialog grant) | Camera, mic, GPS, contacts, SMS, calendar, storage |
| **Special** (enable in Settings) | Accessibility (full UI control), Notification reading, Overlay, All files |
| **Shizuku** (one-time ADB) | Silent app install, input injection, hidden APIs, system settings |

---

## Build

```bash
# Prerequisites
# - JDK 17
# - Android SDK (API 34, Build Tools 34.0.0)

export ANDROID_HOME=/path/to/android-sdk

# Debug build
./gradlew assembleDebug

# APK location
ls app/build/outputs/apk/debug/app-debug.apk
```

---

## Install

1. Download the APK to your phone
2. Enable **Install from Unknown Sources** for your file manager
3. Install the APK
4. Open OpenClaw
5. Tap **Permissions** to grant runtime permissions
6. Enable **Accessibility Service** in Settings > Accessibility > OpenClaw
7. Enable **Notification Access** in Settings > Apps > Special Access
8. Tap **Start** to launch the service

---

## Project Structure

```
app/src/main/kotlin/com/openclaw/android/
├── MainActivity.kt              # 5-tab navigation
├── OpenClawApplication.kt       # App init
├── service/
│   ├── OpenClawService.kt       # Foreground service
│   ├── ScreenReaderService.kt   # Accessibility (eyes + hands)
│   ├── NotificationReaderService.kt
│   └── BootReceiver.kt          # Auto-start on boot
├── bridge/
│   └── AndroidBridgeServer.kt   # REST API (15 endpoints)
├── data/
│   ├── AppDatabase.kt           # Room database
│   ├── ConnectorRegistry.kt     # 28 connector definitions
│   ├── VectorSearch.kt          # Memory vector search
│   ├── entity/                  # DB entities
│   └── dao/                     # DB access objects
├── ui/screens/
│   ├── DashboardScreen.kt       # Mission Control
│   ├── ConnectorsScreen.kt      # Tool configuration
│   ├── FilesScreen.kt           # MD file editor
│   ├── LogScreen.kt             # Log viewer
│   └── SettingsScreen.kt        # LLM & app settings
└── util/
    ├── ServiceState.kt          # Reactive state
    └── PermissionHelper.kt      # Permission management
```

---

## Roadmap

- [x] Phase 1a — Native app shell, bridge server, accessibility, notifications
- [x] Phase 1a — Mission Control UI (dashboard, connectors, files, logs, settings)
- [x] Phase 1a — Room database with vector search memory
- [x] Phase 1a — 28 configurable connectors
- [ ] Phase 1b — Node.js runtime (nodejs-mobile) for OpenClaw gateway
- [ ] Phase 2 — Shizuku integration for ADB-level control
- [ ] Phase 2 — MediaProjection screenshots
- [ ] Phase 3 — LLM tool calling (AI controls phone end-to-end)
- [ ] Phase 3 — Vision pipeline (screenshot → LLM → action)
- [ ] Phase 4 — On-device model (Gemma 3n) for offline
- [ ] Phase 4 — Quick Settings tile, home screen widget

---

## Stats

| Metric | Value |
|--------|-------|
| APK size (debug) | ~26 MB |
| Kotlin source files | 26 |
| Lines of code | ~2,100 |
| Bridge API endpoints | 15 |
| Connectors | 28 |
| Min SDK | 24 (Android 7.0) |

---

## License

Private project. Not for distribution.
