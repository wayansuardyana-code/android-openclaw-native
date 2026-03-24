# OpenClaw Android Native — PLAN.md

> **Project:** Move OpenClaw from Termux container to a native Android app with full device control
> **Primary Device:** ADVAN X1 (aarch64)
> **Target:** Universal — all Android devices, including older/low-end
> **Owner:** Wayan
> **Created:** 2026-03-24
> **Status:** Planning

---

## Problem Statement

OpenClaw di Termux itu **trapped**:
- Jalan di sandbox Termux (`/data/data/com.termux/`)
- Gak bisa akses UI apps lain, notifikasi, kamera langsung
- Butuh `proot` hack buat Ubuntu, `hijack.js` buat network (`os.networkInterfaces()`)
- Gak bisa kontrol Android (tap, swipe, buka app)
- SSH reverse tunnel ribet, sering putus kalo WiFi switch / phone sleep
- Termux bisa di-kill oleh Android battery optimization

---

## Goal

Bikin native Android app yang:
1. **Embeds OpenClaw gateway** (full, unmodified) via nodejs-mobile
2. **Has full device control** (~90% of root capabilities, tanpa root)
3. **Runs as a persistent service** (survives battery optimization)
4. **Exposes Android capabilities as LLM tools** (AI bisa kontrol HP)
5. **No more Termux, no more SSH tunnel, no more proot hacks**

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  ANDROID NATIVE APP (Kotlin + Jetpack Compose)      │
│                                                     │
│  ┌───────────────────────────────────────────────┐  │
│  │  SYSTEM LAYER — Kotlin native                 │  │
│  │  • AccessibilityService (kontrol semua UI)    │  │
│  │  • NotificationListener (baca semua notif)    │  │
│  │  • MediaProjection (screenshot/screen cap)    │  │
│  │  • ContentProviders (contacts, SMS, calendar) │  │
│  │  • Camera, Location, Sensors, TTS/STT         │  │
│  │  • Shizuku bridge (ADB-level tanpa root)      │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │ IPC (localhost HTTP/WebSocket) │
│  ┌──────────────────▼────────────────────────────┐  │
│  │  RUNTIME LAYER — nodejs-mobile (embedded V8)  │  │
│  │  • OpenClaw gateway (full, unmodified)        │  │
│  │  • All 130+ extensions                        │  │
│  │  • Telegram/WhatsApp/Discord channels         │  │
│  │  • Agent sessions + tool pipeline             │  │
│  │  • Config: openclaw.json                      │  │
│  └──────────────────┬────────────────────────────┘  │
│                     │                                │
│  ┌──────────────────▼────────────────────────────┐  │
│  │  BRIDGE LAYER — Custom OpenClaw extension     │  │
│  │  Exposes Android APIs as LLM-callable tools:  │  │
│  │                                               │  │
│  │  android_tap(x, y)                            │  │
│  │  android_swipe(x1, y1, x2, y2)               │  │
│  │  android_screenshot() → base64 image          │  │
│  │  android_open_app(package_name)               │  │
│  │  android_read_screen() → accessibility tree   │  │
│  │  android_read_notifications() → JSON          │  │
│  │  android_send_sms(to, body)                   │  │
│  │  android_take_photo() → base64 image          │  │
│  │  android_get_location() → {lat, lng}          │  │
│  │  android_set_volume(level)                    │  │
│  │  android_read_contacts() → JSON               │  │
│  │  android_clipboard_get/set(text)              │  │
│  │  android_toggle_wifi(on/off)                  │  │
│  │  android_battery_status() → JSON              │  │
│  │  android_install_apk(path)  [via Shizuku]     │  │
│  │  android_run_shell(cmd)     [via Shizuku]     │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### IPC Design: Kotlin ↔ Node.js

```
Kotlin (System Layer)              Node.js (OpenClaw)
     │                                  │
     │  ← HTTP POST /android/tap        │  (Node calls Kotlin)
     │    {x: 500, y: 800}              │
     │  → 200 OK {success: true}        │
     │                                  │
     │  → WS event: notification        │  (Kotlin pushes to Node)
     │    {app: "whatsapp", text: "..."}│
     │                                  │
```

- Kotlin runs a lightweight HTTP server (Ktor/NanoHTTPD) on `localhost:18790`
- Node.js OpenClaw calls Kotlin APIs via HTTP
- Kotlin pushes real-time events (notifications, screen changes) via WebSocket
- All traffic on loopback — no network exposure

---

## Android Capabilities — No Root Required

### Special Permissions (user enables once in Settings)

| Permission | What It Gives | How To Enable |
|---|---|---|
| **AccessibilityService** | Read screen of ALL apps, tap/swipe/type anywhere, global actions (back, home, recents) | Settings > Accessibility > OpenClaw → ON |
| **NotificationListenerService** | Read ALL notifications from ALL apps (WhatsApp msgs, bank OTPs, email) | Settings > Apps > Special Access > Notification Access |
| **Device Admin** | Lock screen, wipe device, enforce password policy | Settings > Security > Device Administrators |
| **SYSTEM_ALERT_WINDOW** | Draw overlay UI on top of any app | Settings > Apps > Special Access > Display Over Other Apps |
| **MANAGE_EXTERNAL_STORAGE** | Read/write ALL files on device | Settings > Apps > Special Access > All Files Access |
| **Usage Stats** | Track which apps are used when and for how long | Settings > Apps > Special Access > Usage Access |

### Standard Runtime Permissions (user grants via dialog)

| Permission | Capability |
|---|---|
| `CAMERA` | Take photos/video |
| `RECORD_AUDIO` | Microphone, speech recognition |
| `ACCESS_FINE_LOCATION` | GPS location |
| `READ_CONTACTS` / `WRITE_CONTACTS` | Contact list |
| `READ_SMS` / `SEND_SMS` | SMS messages |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Calendar events |
| `READ_CALL_LOG` | Call history |
| `READ_PHONE_STATE` | Phone number, carrier info |

### Shizuku (ADB-level, no root)

| Capability | Notes |
|---|---|
| Silent app install/uninstall | `IPackageManager` via Shizuku binder |
| Grant/revoke permissions | For any app, silently |
| Input injection | `InputManager.injectInputEvent()` — no Accessibility needed |
| Screenshot without dialog | Hidden `SurfaceControl` API (like scrcpy) |
| System settings modification | `Settings.Secure`, `Settings.Global` |
| Hidden API access | Any API available to `shell` user |
| Package management | List, disable, enable any package |

**Setup:** User starts Shizuku via wireless debugging (Android 11+, no PC needed) or one ADB command from PC.

**Device note:** If running Android < 11, Shizuku needs one-time PC ADB command. Android 11+ supports wireless debugging (no PC needed). Core features (Accessibility, Notifications) work on any Android 7+.

---

## OpenClaw Architecture (from github.com/openclaw/openclaw)

### Core Components

```
openclaw/
├── src/
│   ├── agents/          # Agent execution, bash tools, auth
│   ├── channels/        # Channel implementations (Telegram, Discord, etc.)
│   ├── cli/             # CLI interface
│   ├── daemon/          # Service management (systemd/launchd/schtasks)
│   ├── browser/         # Playwright browser automation
│   ├── plugins/         # Plugin loader, discovery, command registry
│   ├── plugin-sdk/      # Extension API
│   ├── sessions/        # Session lifecycle, state management
│   ├── routing/         # Message routing
│   ├── config/          # Configuration (openclaw.json, JSON5)
│   ├── security/        # Auth, secrets, sandboxing
│   ├── entry.ts         # CLI entry point
│   └── index.ts         # Public API
├── extensions/          # 130+ extensions
└── docs/                # Documentation
```

### Key Facts

- **Runtime:** Node.js 24 + TypeScript
- **Gateway:** WebSocket server on `ws://127.0.0.1:18789`
- **Agent engine:** Wraps Anthropic's `pi-coding-agent`
- **Channels:** 20+ (Telegram, WhatsApp, Discord, Slack, Signal, etc.)
- **Config:** `~/.openclaw/openclaw.json` (JSON5)
- **Tools:** Bash execution, file ops, browser automation, MCP, web search
- **Providers:** Anthropic, OpenAI, Google, Groq, Mistral, DeepSeek, xAI, custom baseUrl

### What Needs Adaptation for Android

| Component | Status | Notes |
|---|---|---|
| Gateway (WebSocket) | ✅ Works as-is | nodejs-mobile supports `ws` |
| Telegram/Discord channels | ✅ Works as-is | Pure HTTP/WebSocket, no native deps |
| WhatsApp channel | ⚠️ Needs testing | Uses `whatsapp-web.js` which needs Chromium |
| Bash tools | 🔄 Replace | Route to Kotlin shell exec or Shizuku |
| File operations | 🔄 Adapt paths | Map to Android file system |
| Browser automation | 🔄 Replace | Playwright won't work → use WebView or skip |
| SQLite (sqlite-vec) | ⚠️ Needs native build | Compile for aarch64 or use pure-JS alternative |
| Sharp (image processing) | 🔄 Replace | Use pure-JS (jimp) or Kotlin-side processing |
| Config paths | 🔄 Adapt | Map `~/.openclaw/` to app's private storage |
| Daemon management | ❌ Remove | Replaced by Android Foreground Service |

---

## Technology Stack

| Layer | Technology | Why |
|---|---|---|
| **App shell + UI** | Kotlin + Jetpack Compose | Required for AccessibilityService, NotificationListener, system APIs |
| **OpenClaw runtime** | nodejs-mobile | Runs full OpenClaw gateway unmodified (V8 engine, ARM64 native) |
| **Privilege broker** | Shizuku | ADB-level power without root |
| **Local server** | Ktor (Kotlin) or NanoHTTPD | Exposes Android APIs to Node.js via localhost HTTP |
| **LLM providers** | OpenClaw's existing system | Anthropic, OpenAI, MiniMax, etc. — already built |
| **Build system** | Gradle (Kotlin) + npm (Node.js) | Standard Android + Node.js toolchain |

### Why NOT React Native / Expo?

- **AccessibilityService MUST be a native Android Service** declared in manifest
- Cannot register from JavaScript — must be Kotlin/Java
- Would end up writing 80% of critical code in Kotlin anyway
- React Native adds complexity without benefit for this use case
- nodejs-mobile already provides JS runtime for OpenClaw

---

## Reference Projects

| Project | What To Learn | Link |
|---|---|---|
| **DroidRun** (7.8K★) | AccessibilityService + LLM architecture | github.com/droidrun/droidrun |
| **mobile-use** | 100% AndroidWorld benchmark, accessibility tree → LLM | github.com/minitap-ai/mobile-use |
| **Koog** (JetBrains) | Kotlin AI agent framework, provider integration | github.com/JetBrains/koog |
| **ToolNeuron** | Fully offline on-device AI (GGUF, vision, tools) | github.com/Siddhesh2377/ToolNeuron |
| **S1-app** | Open source Rabbit R1 alternative | github.com/Foqsee/S1-app |
| **nodejs-mobile** | Embedding Node.js in Android | github.com/niclas79-mobile/nodejs-mobile |
| **Open-AutoGLM** | Phone control via screenshot + VLM | github.com/niclas79-org/Open-AutoGLM |
| **AppAgent** (Tencent) | App exploration → deployment pattern | github.com/TencentQQGYLab/AppAgent |

---

## Implementation Phases

### Phase 1: Shell App + Embedded OpenClaw ⏳ (Week 1-2)

**Goal:** OpenClaw gateway running inside a native Android app

```
Tasks:
├── 1.1 Scaffold Kotlin project (Jetpack Compose, min SDK 29)
├── 1.2 Integrate nodejs-mobile as dependency
├── 1.3 Bundle OpenClaw source code into APK assets
├── 1.4 On app start: extract OpenClaw → start Node.js → launch gateway
├── 1.5 Foreground Service to keep Node.js alive
├── 1.6 Simple UI: status indicator, gateway logs, start/stop
├── 1.7 Test: Telegram channel works from Android app
└── 1.8 Handle OpenClaw config (openclaw.json) in app storage
```

**Deliverable:** APK yang install → OpenClaw gateway jalan → Telegram bot responds

### Phase 2: Android System Bridge ⏳ (Week 3-4)

**Goal:** Kotlin system layer exposes Android APIs to Node.js

```
Tasks:
├── 2.1 Implement AccessibilityService (screen reading, tap, swipe, type)
├── 2.2 Implement NotificationListenerService
├── 2.3 Kotlin localhost HTTP server (Ktor) for IPC
├── 2.4 WebSocket push for real-time events (notifs, screen changes)
├── 2.5 Implement MediaProjection for screenshots
├── 2.6 Standard permissions: camera, location, microphone, contacts
├── 2.7 Shizuku integration for ADB-level operations
└── 2.8 Test all APIs independently from Kotlin side
```

**Deliverable:** Kotlin HTTP server on localhost with all Android APIs accessible

### Phase 3: OpenClaw Android Extension ⏳ (Week 5-6)

**Goal:** LLM can control Android through OpenClaw tools

```
Tasks:
├── 3.1 Create OpenClaw extension: `extensions/android-native/`
├── 3.2 Register Android tools in OpenClaw's tool pipeline
│   ├── android_tap, android_swipe, android_type
│   ├── android_screenshot, android_read_screen
│   ├── android_open_app, android_back, android_home
│   ├── android_read_notifications
│   ├── android_send_sms, android_make_call
│   ├── android_take_photo, android_get_location
│   ├── android_clipboard, android_volume
│   ├── android_battery, android_wifi
│   └── android_shell (via Shizuku)
├── 3.3 Screen understanding pipeline:
│   │   screenshot → send to vision model → get action → execute
│   └── OR: accessibility tree → structured JSON → LLM decides
├── 3.4 System prompt additions for Android context
├── 3.5 Test end-to-end: "buka WhatsApp, kirim pesan ke X"
└── 3.6 Safety guardrails (confirm destructive actions)
```

**Deliverable:** From Telegram, tell AI → AI controls your phone

### Phase 4: Polish & Self-Contained ⏳ (Week 7-8)

**Goal:** Production-ready app, full self-contained experience

```
Tasks:
├── 4.1 Onboarding flow (request all permissions, setup wizard)
├── 4.2 App UI: chat interface, live screen mirror, tool execution log
├── 4.3 Boot auto-start (RECEIVE_BOOT_COMPLETED)
├── 4.4 Battery optimization whitelist (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
├── 4.5 Quick Settings tile for instant access
├── 4.6 Home screen widget (status + quick actions)
├── 4.7 Notification channel for persistent service
├── 4.8 Error handling, crash recovery, auto-restart
├── 4.9 Optional: on-device model (Gemma 3n via LiteRT) for offline
└── 4.10 Build signed APK for sideload distribution
```

**Deliverable:** Polished APK, install once → full AI-controlled Android

### Phase 5: Web App Mirror + Google Auth ⏳ (v2.0)

**Goal:** Access OpenClaw from any browser — same chat, dashboard, logs, connectors. Single Google Sign-In across Android + web. Google account doubles as GWS API auth.

```
Architecture:

┌────────────────────┐         ┌──────────────────┐
│  WEB APP (Vercel)  │  HTTPS  │  ANDROID (HP)    │
│  Next.js dashboard │ ──────→ │  Bridge :18790   │
│  Chat, Logs, Dash  │         │  OpenClaw agent  │
│  Google Sign-In    │         │  Accessibility   │
└────────────────────┘         └──────────────────┘
         ↑                              ↑
         │ Browser                      │ Secure tunnel
         │                              │ (Cloudflare Tunnel / ngrok)
    ┌─────────┐                    ┌──────────┐
    │ Laptop  │                    │ Internet │
    └─────────┘                    └──────────┘

Tasks:
├── 5.1 Secure Tunnel — expose phone bridge to internet
│   ├── Option A: Cloudflare Tunnel (free, stable, custom domain)
│   ├── Option B: ngrok (free tier, random URL)
│   ├── Option C: Tailscale Funnel (mesh VPN, invite-only access)
│   └── Auth token verification on bridge server (prevent unauthorized access)
│
├── 5.2 Web App — Next.js on Vercel
│   ├── Same 5 tabs: Chat, Dashboard, Connectors, Logs, Settings
│   ├── WebSocket connection to phone bridge for real-time sync
│   ├── Chat: send messages, receive responses, slash commands
│   ├── Dashboard: kanban, hardware stats (proxied from phone)
│   ├── Logs: real-time log streaming via WebSocket
│   ├── Settings: provider config, API keys (synced with phone)
│   └── Responsive design — works on laptop, tablet, phone browser
│
├── 5.3 Google Sign-In — dual platform auth
│   ├── Google Cloud Console: create project, enable APIs
│   ├── OAuth2 Client ID (Web) → Vercel web app
│   ├── OAuth2 Client ID (Android) → Android app
│   ├── Scopes: openid, profile, email + Drive, Sheets, Gmail, Calendar
│   ├── Android: com.google.android.gms:play-services-auth
│   ├── Web: next-auth or @auth/core with Google provider
│   └── Token flow:
│       ├── User logs in → ID token (identity) + access token (GWS APIs)
│       ├── Access token stored in AgentConfig
│       ├── google_workspace tool uses stored token automatically
│       └── Refresh token for persistent login (no re-auth needed)
│
├── 5.4 Bridge Server Upgrades
│   ├── WebSocket endpoint for real-time events (logs, notifications, status)
│   ├── Auth middleware — verify JWT/token on all endpoints
│   ├── CORS config for Vercel web app domain
│   ├── Rate limiting on public-facing endpoints
│   └── Encrypted communication (tunnel handles TLS)
│
├── 5.5 Multi-Device Session Sync
│   ├── Chat history synced between Android app and web app
│   ├── Settings synced via bridge server (single source of truth = phone)
│   ├── Concurrent access: web + Android can both chat simultaneously
│   └── Conflict resolution: phone state is always master
│
└── 5.6 Google Workspace Native Integration
    ├── Login with Google → access token covers all GWS APIs
    ├── No separate GWS CLI needed — direct REST API calls
    ├── Supported: Drive (files), Sheets (data), Gmail (email),
    │   Calendar (events), Docs (documents)
    ├── Auto-refresh token on expiry
    └── User can revoke access via Google Account settings
```

**Deliverable:** Web dashboard at https://openclaw.vercel.app (or custom domain), login with Google, control your phone from any browser. Same experience as Android app.

**Key Decisions:**
- Tunnel: Cloudflare Tunnel recommended (free, stable, custom domain support)
- Web framework: Next.js 16 + shadcn/ui + Vercel AI SDK
- Auth: Google OAuth2 via Google Cloud Console (not Firebase — too heavy)
- State: Phone is always master, web is a mirror/remote control
- GWS: Google login token = GWS API token (no separate auth flow)

---

## Device Compatibility

### Target: Universal Android

Satu APK untuk semua device. Strategy: **minSdk 24 (Android 7.0)** — covers 99%+ active devices.

#### API Availability by Android Version

| Feature | Min API | Android Ver | Fallback for older |
|---|---|---|---|
| **AccessibilityService** (basic) | 4 | 1.6 | ✅ Always available |
| **NotificationListenerService** | 18 | 4.3 | ✅ Always available (minSdk 24) |
| **MediaProjection** (screenshot) | 21 | 5.0 | ✅ Always available |
| **dispatchGesture()** (tap/swipe) | 24 | 7.0 | ✅ This is our minSdk |
| **Foreground Service** | 26 | 8.0 | Use `startService()` on API <26 |
| **Shizuku wireless debug** | 30 | 11 | Needs one-time PC ADB on older |
| **takeScreenshot()** via A11y | 30 | 11 | Use MediaProjection fallback |
| **Foreground service types** | 34 | 14 | Declare conditionally |

#### Architecture Support

| Arch | Support | Notes |
|---|---|---|
| **arm64-v8a (aarch64)** | ✅ Primary | ADVAN X1, most modern phones |
| **armeabi-v7a (32-bit ARM)** | ✅ Supported | Older/budget phones |
| **x86_64** | ✅ Supported | Emulators, ChromeOS |
| **x86** | ⚠️ Best effort | Very old emulators only |

nodejs-mobile provides prebuilt binaries for arm64 and arm32. We ship both in the APK (Android splits handle this automatically — user only downloads their arch).

#### Low-End Device Optimizations

| Concern | Strategy |
|---|---|
| **RAM** (1-2 GB) | Lazy-load OpenClaw extensions. Only init channels that are configured. |
| **Storage** (16 GB) | APK ~50-80MB. OpenClaw data in app storage, cleanup old logs. |
| **CPU** (old SoC) | Node.js V8 is efficient. Heavy work (vision) offloaded to cloud LLM. |
| **Battery** | Intelligent wake: only process when message arrives. Sleep between. |
| **Slow network** | Queue messages. Retry with backoff. Offline notification reading still works. |
| **Android Go** | Works — no Google Play Services dependency. |

#### Tested / Target Devices

| Device | Android | RAM | Arch | Status |
|---|---|---|---|---|
| **ADVAN X1** | TBD | TBD | arm64 | Primary dev device |
| Budget phones (Redmi, Realme, Samsung A-series) | 8-14 | 2-4 GB | arm64 | Target market |
| Old phones (2018-2020 era) | 7-10 | 1-3 GB | arm64/arm32 | Supported with fallbacks |
| Tablets | 8+ | 2+ GB | arm64 | Supported |
| Emulator (Android Studio) | Any | N/A | x86_64 | Dev/testing |

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **Google Play rejection** | Can't distribute via Play Store | Sideload APK, F-Droid, or GitHub Releases |
| **nodejs-mobile native module compat** | sqlite-vec, sharp may not compile for ARM64 | Use pure-JS alternatives (better-sqlite3 has ARM builds, jimp for images) |
| **Battery drain** | User complaints, Android kills app | Foreground Service, intelligent wake, battery whitelist |
| **Old Android (< 11)** | Shizuku needs PC for first setup | Provide one-time ADB setup script. Core features (Accessibility, Notifications) work without Shizuku |
| **WhatsApp channel needs Chromium** | whatsapp-web.js may not work | Use Baileys (no browser needed) or skip WhatsApp channel |
| **OpenClaw updates** | Hard to keep in sync with upstream | Git submodule or periodic sync script |
| **Android 14+ restrictions** | Stricter foreground service rules | Declare proper foreground service types in manifest |
| **Low RAM devices (1-2GB)** | Node.js + Kotlin may be tight | Lazy-load, limit concurrent sessions, aggressive GC |
| **32-bit ARM devices** | nodejs-mobile arm32 support needed | Ship both ABIs, test on arm32 emulator |

---

## File Structure (Planned)

```
android-openclaw-native/
├── PLAN.md                          ← This file
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/openclaw/android/
│       │   ├── MainActivity.kt
│       │   ├── OpenClawApplication.kt
│       │   ├── service/
│       │   │   ├── OpenClawService.kt          # Foreground service, manages Node.js
│       │   │   ├── ScreenReaderService.kt       # AccessibilityService
│       │   │   └── NotificationReaderService.kt # NotificationListenerService
│       │   ├── bridge/
│       │   │   ├── AndroidBridgeServer.kt       # Ktor localhost HTTP server
│       │   │   ├── ScreenController.kt          # Tap, swipe, type via Accessibility
│       │   │   ├── NotificationReader.kt        # Read all notifications
│       │   │   ├── DeviceController.kt          # Volume, WiFi, brightness, etc.
│       │   │   ├── MediaController.kt           # Camera, screenshot, audio
│       │   │   ├── ContactsProvider.kt          # Contacts, SMS, calendar
│       │   │   ├── LocationProvider.kt          # GPS location
│       │   │   └── ShizukuBridge.kt             # ADB-level operations
│       │   ├── ui/
│       │   │   ├── theme/
│       │   │   ├── screens/
│       │   │   │   ├── HomeScreen.kt
│       │   │   │   ├── SetupScreen.kt
│       │   │   │   ├── LogScreen.kt
│       │   │   │   └── ChatScreen.kt
│       │   │   └── components/
│       │   └── util/
│       ├── assets/
│       │   └── openclaw/                        # Bundled OpenClaw source
│       └── res/
├── openclaw-android-extension/                  # OpenClaw extension (Node.js)
│   ├── package.json
│   ├── index.ts                                 # Extension entry point
│   ├── tools/
│   │   ├── screen.ts                            # android_tap, android_swipe, etc.
│   │   ├── notifications.ts                     # android_read_notifications
│   │   ├── media.ts                             # android_screenshot, android_take_photo
│   │   ├── device.ts                            # android_volume, android_wifi, etc.
│   │   ├── contacts.ts                          # android_read_contacts, android_send_sms
│   │   └── shell.ts                             # android_shell (via Shizuku)
│   └── bridge-client.ts                         # HTTP client to Kotlin bridge server
├── build.gradle.kts                             # Root Gradle config
├── settings.gradle.kts
└── gradle.properties
```

---

## Current AdvanClaw Setup (for reference/migration)

| Field | Value |
|---|---|
| **Device** | ADVAN X1, aarch64 |
| **Current setup** | Termux → proot Ubuntu → OpenClaw gateway |
| **Model** | MiniMax M2.5 via `api.minimax.io/anthropic` |
| **Channel** | Telegram bot |
| **Gateway port** | 18789, loopback, token auth |
| **Hack needed** | `hijack.js` patches `os.networkInterfaces()` for proot |
| **SSH tunnel** | `ssh -R 2222:localhost:8022 root@187.127.104.132 -N` |
| **Proot home** | `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/root/` |
| **Other projects** | `openclaw-trader/` (IDX stock + Indodax bot) |

---

## Next Steps

1. **Research nodejs-mobile** compatibility with OpenClaw's dependencies
2. **Scaffold Kotlin project** with Jetpack Compose
3. **Proof of concept:** Node.js hello world running inside Android app
4. **Then:** Port OpenClaw gateway into it
