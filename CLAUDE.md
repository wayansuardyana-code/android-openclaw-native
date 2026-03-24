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
├── bridge/
│   └── AndroidBridgeServer.kt # Ktor HTTP API (15 endpoints)
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
```

## Development Notes
- PostgreSQL JDBC removed (Android compat issue) — use HTTP proxy instead
- Shizuku integration declared but not yet connected
- nodejs-mobile integration planned for Phase 1b
- APK served via `python3 -m http.server 8899` on VPS for download
