# OpenClaw Android

**Autonomous AI agent that controls your Android phone through natural language.**

Built natively in Kotlin — no cloud dependency, no root required.

---

## What is this?

OpenClaw turns your Android phone into an AI-powered autonomous agent. Tell it what to do in plain language, and it actually does it — opens apps, taps buttons, types text, reads screens, scrapes the web, generates reports, and sends results wherever you want.

It's not a chatbot. It's a **doer**.

```
You: "Search YouTube for lofi music and play the first result"
Nate: Opens YouTube → taps search → types "lofi music" → taps first video → playing.

You: "Check NVIDIA stock price, make an Excel report, send to my Telegram"
Nate: Calls Finnhub API → generates XLSX → sends via Telegram Bot → done.

You: "Every morning at 8, check weather in Bali and text me"
Nate: Saves to schedule → HeartbeatService runs at 8am → weather API → Telegram message.
```

---

## Features

### 56 LLM Tools
| Category | Count | Examples |
|----------|-------|---------|
| Android Device | 18 | tap, swipe, type, read screen, screenshot, volume, brightness, clipboard, wifi |
| Utility | 22 | web search, web scrape, file read/write, Excel/CSV/PDF gen, calculator, Brave/Exa/Firecrawl search |
| Service | 7 | GitHub API, Vercel API, Supabase, Google Workspace, SSH, PostgreSQL |
| Python | 3 | install, run, pip install |
| Vision | 2 | screenshot analysis (Gemini Vision), document analysis |

### 15 LLM Providers
Anthropic, OpenAI, Google Gemini, MiniMax, Kimi, Moonshot, OpenRouter, DeepSeek, Groq, xAI, Mistral, Together AI, Fireworks, Ollama, custom endpoint.

**LLM Fallback** — if your primary provider fails, OpenClaw auto-tries others with saved API keys. Zero downtime.

### 20 Free APIs (No Key Required)
Weather, currency exchange, stock quotes, Quran verses, Bible passages, prayer times, news, holidays, QR codes, URL shortener, translation, and more — all wired with trigger conditions so the agent knows when to use each one.

### Autonomous Agent
- **HeartbeatService** — runs every 30 minutes autonomously. Reviews failures, updates skills, executes scheduled tasks.
- **Auto-learn** — after completing 5+ step tasks, saves the pattern as a reusable skill.
- **Auto-memory** — every conversation turn and tool result auto-saved to SQLite. Agent recalls past experience before starting new tasks.
- **Self-improvement** — failures logged, heartbeat reviews them, skills updated. Agent gets smarter over time.

### Live Interaction
- **Narration** — agent tells you what it's doing at each step ("Opening Chrome... Searching for... Found 5 results...")
- **Mid-task feedback** — send a message while agent is working to change its approach
- **Telegram bot** — control your phone remotely from anywhere
- **Vision** — agent can "see" the screen via Gemini Vision API (icons, images, colors — not just text)

---

## Quick Start

### 1. Install
Download APK from [Releases](../../releases). Install on Android 7.0+ device.

### 2. Enable Accessibility
Settings → Accessibility → OpenClaw → Enable. This gives the agent eyes and hands.

### 3. Add an API Key
Open app → Settings → "+" → pick a provider → paste API key.

**Free option:** Google Gemini (AI Studio) gives free API access.

### 4. Chat
Open app → Chat tab → type a message → the agent does the rest.

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              Android Device                  │
├──────────────┬──────────────────────────────┤
│  OpenClaw App│                              │
│  ┌──────────┐│  ┌────────────────────────┐  │
│  │ Chat UI  ││  │  AccessibilityService  │  │
│  │ Settings ││  │  (eyes + hands)        │  │
│  │ Files    ││  ├────────────────────────┤  │
│  │ Logs     ││  │  NotificationListener  │  │
│  └──────────┘│  ├────────────────────────┤  │
│       │      │  │  ForegroundService     │  │
│       ▼      │  ├────────────────────────┤  │
│  ┌──────────┐│  │  HeartbeatService      │  │
│  │AgentLoop ││  │  (autonomous 30-min)   │  │
│  │ LLM call ││  ├────────────────────────┤  │
│  │ Tool exec││  │  TelegramBotService    │  │
│  │ Auto-mem ││  │  (remote control)      │  │
│  └──────────┘│  └────────────────────────┘  │
│       │      │              │                │
│       ▼      │              ▼                │
│  ┌──────────┐│  ┌────────────────────────┐  │
│  │ Room DB  ││  │  Bridge Server (Ktor)  │  │
│  │ SQLite   ││  │  localhost:18790       │  │
│  └──────────┘│  └────────────────────────┘  │
└──────────────┴──────────────────────────────┘
         │
         ▼
   ┌───────────┐
   │ LLM APIs  │  (15 providers)
   │ Free APIs │  (20 services)
   └───────────┘
```

**Tech Stack:** Kotlin, Jetpack Compose, Room, Ktor, Shizuku (optional)

---

## How the Agent Works

Every task follows the **ACT → OBSERVE → REPORT → LEARN** pattern:

1. **ACT** — Execute: open apps, navigate UI, call APIs, generate files
2. **OBSERVE** — Read screen, take screenshots, verify results
3. **REPORT** — Send results via chat, Telegram, or file export
4. **LEARN** — Auto-save to SQLite memory + skills.md for future tasks

The agent has 3 layers of defense against getting stuck:
- **Auto-nudge** — if the LLM replies with text instead of calling tools, the agent forces a retry
- **Problem solving** — tries 3 different approaches before giving up
- **Sub-agent spawn** — for tasks exceeding 25 steps, spawns a background agent to continue

---

## Memory System

OpenClaw has a **tiered memory architecture**:

| Tier | What | When Loaded |
|------|------|-------------|
| L0 | Core preferences, most-accessed memories | Always in system prompt |
| L1 | Recent (24h) + keyword-matched memories | Per-message relevance |
| L2 | Full SQLite database | On-demand via memory_search |

**Auto-memory** saves everything without being asked:
- Conversation turns (user request + response + tools used)
- Fact-producing tool results (web search, API calls, database queries)
- Auto-prune at 500+ memories (removes old, unused, low-importance entries)

---

## Workspace Files

The agent evolves through workspace files stored on device:

| File | Purpose |
|------|---------|
| `SOUL.md` | Agent personality and core values |
| `USER.md` | What the agent knows about you |
| `TOOLS.md` | App interaction guides + free API reference |
| `skills.md` | Learned patterns from past tasks (auto-populated) |
| `memory.md` | Failure log for heartbeat review |
| `HEARTBEAT.md` | Autonomous behavior program |

Edit any file from the **Files** tab in the app.

---

## Build from Source

```bash
git clone https://github.com/wayansuardyana-code/android-openclaw-native.git
cd android-openclaw-native
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires: JDK 17, Android SDK 34, Kotlin 1.9.22

---

## Current Version

**v2.4.1** — 56 tools, 15 providers, SoM vision, scheduled tasks, security hardened, auto-memory, Discord/Slack

---

## Roadmap

- [ ] Set-of-Mark (SoM) overlay for precise visual targeting
- [ ] Discord / Slack bot connectors
- [ ] On-device model (Gemma 3n) for zero-API-cost operation
- [ ] Web dashboard (control phone from browser)
- [ ] Supabase cloud sync (cross-device memory)

---

## License

MIT
