# Contributing to OpenClaw Android

## Setup
1. Clone the repo
2. Open in Android Studio
3. Set `ANDROID_HOME` environment variable
4. Run `./gradlew assembleDebug`

## Architecture
See CLAUDE.md for project structure and conventions.

## Guidelines
- Follow existing Kotlin code style
- Test on Android 7.0+ devices
- Keep tool definitions consistent with existing patterns
- Security: never hardcode API keys or tokens
