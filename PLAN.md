# OpenClaw Android Native — PLAN.md

> **Version:** 2.0.0
> **Device:** ADVAN X1 (Android 14, API 34)
> **Owner:** Wayan
> **Updated:** 2026-03-25
> **Status:** Active development

---

## Current State (v2.0.0)

### What's Done
- 45 LLM tools (18 Android + 17 utility + 7 service + 3 Python)
- Kotlin + Jetpack Compose native app
- AccessibilityService (screen control, tap, swipe, type, long press, scroll)
- NotificationListenerService
- Telegram bot integration (bi-directional)
- Auto-learn (save successful patterns to skills.md)
- Failure logging to memory.md
- HeartbeatService (autonomous 30-min loop)
- Self-improvement: heartbeat reviews failures, updates skills
- Problem solving: 3-attempt retry, sub-agent fallback
- Screen reading: 80 nodes, off-screen filter, class types, focused element
- ACT → OBSERVE → REPORT → LEARN automation pattern
- App interaction guides (WhatsApp, Instagram, Chrome, YouTube, Gmail, Settings)
- MiniMax XML cleanup for stable tool parsing
- Lenient JSON + truncated JSON recovery
- 13 LLM providers, workspace files, skills system

### What's NOT Done Yet
Listed below as upcoming phases.

---

## Phase 8: Polish & Reliability (v2.1)

### 8.1 Camera + Microphone in Chat
- Camera button: launch camera intent → capture photo → attach to message
- Microphone button: SpeechRecognizer not triggering — debug and fix
- File picker (paperclip) already works

### 8.2 XLSX/PDF Generate → Send to Gateway
- Test: "buat laporan Excel, kirim ke Telegram"
- Flow: generate_xlsx → file path → send_telegram_photo (as document, not photo)
- Need: `send_telegram_document` tool (Telegram API sendDocument for non-image files)
- Test: same flow but send to chat (display download link or inline)

### 8.3 Google Workspace Connection
- NOT using GWS CLI (Rust binary, can't run on Android)
- Use direct REST API calls with Bearer token
- google_workspace tool already exists — needs testing
- Auth flow: user pastes OAuth2 access token manually for now
- Future: Google Sign-In flow in app (Phase 5)
- Test: "buat Google Sheet baru", "kirim email via Gmail"

### 8.4 Sub-Agent Auto-Spawn
- Currently: agent only spawns sub-agent at step 25 (max steps)
- Needed: agent should proactively spawn sub-agents for complex tasks
- System prompt already says "spawn_sub_agent for >10 step tasks"
- Improve: detect complexity early (multiple apps, web tasks) → spawn immediately
- Test: "buka 3 website, compare harga, kirim report ke Telegram"

---

## Phase 9: Better Vision — How Agent Sees the Screen (v2.2)

### Research Sources
| Repo | Stars | Key Technique |
|------|-------|--------------|
| X-PLUG/MobileAgent | 8.3k | Screenshot + VLM (multimodal vision) |
| droidrun/droidrun | 8k | Natural language → Android automation |
| TencentQQGYLab/AppAgent | 6.6k | Learn-from-exploration |
| firerpa/lamda | 7.7k | Android RPA framework |
| IPADS-SAI/MobiAgent | 1.8k | Action planning |
| HKUDS/OpenPhone | 749 | Mobile agentic foundation models |

### 9.1 Screenshot + Vision Model (HIGH PRIORITY)
- Current: agent reads accessibility tree (text only, misses visual context)
- Needed: send screenshot to multimodal LLM (GPT-4V, Gemini Vision, etc.)
- Flow: take_screenshot → encode base64 → send to vision model → get description
- Benefits: sees images, icons, colors, layout — not just text nodes
- Implementation: new tool `analyze_screenshot` or enhance `read_screen` with vision option

### 9.2 Set-of-Mark (SoM) Overlay
- Technique from MobileAgent: overlay numbered labels on screenshot
- Agent says "tap #7" instead of guessing coordinates
- More precise than accessibility tree bounds
- Implementation: Kotlin draws numbered circles on screenshot before sending to VLM

### 9.3 Explore-Then-Act (from AppAgent)
- Before doing a task in unfamiliar app: explore first
- Random taps → read results → build knowledge base → then execute task
- Save exploration results to skills.md
- Example: first time opening Shopee → explore navigation → save pattern → then search product

### 9.4 Action History Context
- Track full action sequence in current session
- Send to LLM: "you already tried X, Y, Z — try something different"
- Prevents agent from repeating failed actions

---

## Phase 10: Event-Driven Triggers (v2.3)

### 10.1 Notification-Triggered Actions
- When notification arrives → agent reads it → decides action
- OTP from bank → auto-copy to clipboard
- WhatsApp from specific contact → auto-summarize + suggest reply
- Delivery notification → track package
- Implementation: NotificationReaderService pushes events to agent pipeline

### 10.2 Time-Based Scheduled Tasks
- User says: "every morning at 8, check weather and send to Telegram"
- Store schedule in Room DB
- HeartbeatService checks schedule on each tick
- Cron-like expressions or natural language ("every weekday at 9am")

### 10.3 Battery/WiFi/Location Triggers
- Battery < 20% → notify owner
- Connected to home WiFi → sync data
- Location near office → show today's calendar

---

## Phase 5: Web App Mirror + Google Auth (v3.0)

(Unchanged from original plan — control phone from any browser via Vercel)

- Secure tunnel (Cloudflare Tunnel)
- Next.js web app on Vercel
- Google Sign-In (OAuth2)
- Real-time sync via WebSocket
- Same chat/dashboard/settings as Android app

---

## Phase 6: Supabase Cloud Sync (v4.0)

(Unchanged — uninstall-proof data)

- All data synced to Supabase PostgreSQL
- Login → everything restored
- Cross-device (Android + Web)

---

## Bugs to Fix

| Bug | Priority | Notes |
|-----|----------|-------|
| Camera button in chat doesn't work | HIGH | Intent not firing or permission issue |
| Microphone button in chat doesn't work | HIGH | SpeechRecognizer not triggering |
| `send_telegram_document` missing | MEDIUM | Can only send photos, not files (XLSX/PDF) |
| Python install fails (curl not found) | LOW | Need Ktor-based download fallback |
| Accessibility auto-disables after update | WONTFIX | Android OS behavior — manual re-enable |
| Shizuku not installable on ADVAN X1 | WONTFIX | Android 14 compat issue |

---

## Research Notes

### Karpathy's autoresearch Pattern
- `program.md` drives autonomous agent behavior
- Agent modifies code, trains, evaluates, keeps/discards
- OpenClaw equivalent: HEARTBEAT.md drives autonomous device tasks
- Key insight: "you program the markdown, not the code"

### UI Agent Navigation (from research)
- Best approach: screenshot + vision model > accessibility tree alone
- SoM (Set-of-Mark) overlay most precise for tap targeting
- Exploration phase builds knowledge before executing tasks
- Action history prevents repeating failed actions
