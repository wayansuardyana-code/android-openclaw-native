# OpenClaw Android

Autonomous AI agent that controls your Android phone through natural language.

## What it does

OpenClaw is a native Android app that gives an AI agent full control of your device — open apps, navigate UI, read screens, type text, manage files, browse the web, and automate multi-step tasks. Think of it as an AI assistant that can actually *do* things on your phone, not just answer questions.

## Features

- **49 LLM tools** — screen control, media, volume, clipboard, notifications, files, web, APIs
- **15 LLM providers** — Anthropic, OpenAI, Google Gemini, MiniMax, Kimi, OpenRouter, DeepSeek, Groq, xAI, Mistral, Together, Fireworks, Ollama, Moonshot, custom
- **LLM fallback** — if primary provider fails, auto-tries others with saved keys
- **Vision (Gemini)** — agent can "see" the screen via screenshot + Gemini Vision API
- **Auto-learn** — successful multi-step tasks saved as reusable skills
- **Autonomous heartbeat** — agent runs every 30 min without being asked
- **Self-improvement** — failures logged, reviewed, skills updated
- **Live narration** — agent tells you what it's doing at each step
- **Mid-task feedback** — send messages while agent is working to adjust its approach
- **Telegram bot** — control your phone remotely via Telegram
- **Multi-gateway** — respond via chat, Telegram, or file export
- **Workspace files** — SOUL.md, USER.md, memory.md, skills.md evolve over time

## Requirements

- Android 7.0+ (API 24), optimized for Android 14
- Accessibility Service enabled
- Any LLM API key (Gemini free tier works)

## Install

Download APK from [GitHub Releases](../../releases), install, enable Accessibility Service in Settings.

## Architecture

- Kotlin + Jetpack Compose
- AccessibilityService for full device control
- Room SQLite database (chat, memory, tasks)
- Ktor embedded HTTP server (bridge API)
- Foreground Service (always-on)
- HeartbeatService (autonomous 30-min loop)

## Current Version

v2.2.0 — 49 tools, 15 providers, LLM fallback, vision, auto-learn, autonomous heartbeat

## License

MIT
