# Codex Android

An Android client for remotely connecting to `codex app-server`. It uses WebSockets to send prompts, receive streaming Codex responses, and display command execution, file changes, reasoning summaries, and approval requests.

## Features

- Latest 10 server-side sessions and conversation history restoration
- Streaming Markdown responses
- Create sessions, resume existing sessions, and stop generation
- Model and reasoning-effort selection
- Command and file-change approval requests
- Command output, file-change, and reasoning-summary presentation
- WebSocket reconnection and full session reconciliation, including automatic sync when returning from lock screen or background

## Requirements

- Android Studio (the included Gradle Wrapper is recommended)
- Android SDK 36
- JDK 11, or the JBR bundled with Android Studio
- A deployed and reachable `codex app-server`

## Server configuration

Open **Settings** in the app and provide:

| Field | Description |
| --- | --- |
| Server URL | WebSocket endpoint, for example `wss://example.com:8443` |
| Token | Server WebSocket Bearer token |

Saving returns to the session list. The token is stored locally on the device and must never be committed to the repository.

See [docs/MOBILE_APP_API.md](docs/MOBILE_APP_API.md) for the protocol reference and [docs/mock.py](docs/mock.py) for a minimal connectivity example.

## Run locally

1. Open this directory in Android Studio.
2. Wait for Gradle sync to finish.
3. Connect a device or start an emulator, then run the `app` configuration.
4. Enter the server WebSocket URL and token in Settings.

## Build a release APK

In Android Studio, select **Build → Generate App Bundles or APKs → Generate APKs**, then choose the `release` variant.

For convenient personal-device testing, the current `release` variant is signed with the debug certificate. Before a real distribution, configure a dedicated release keystore and never commit `.jks`/`.keystore` files or passwords.

You can also build from the command line:

```bash
./gradlew assembleRelease
```

The output is normally written to:

```text
app/build/outputs/apk/release/app-release.apk
```

## Background and reconnection behavior

Server-side tasks continue running, but Android may suspend networking or reclaim the process while the device is locked, in the background, or in Doze mode. The server also does not replay WebSocket stream notifications. When possible, the app reconnects and synchronizes the session through `thread/resume` and `thread/read`, so the final messages are restored when the app returns to the foreground.

FCM, Bark, and other background-completion push notifications are not included. They require an additional server-side push relay.

## Project layout

```text
app/src/main/java/com/hiro/codex_android/
├── data/        # WebSocket protocol, local storage, and data models
├── ui/chat/     # Chat, Markdown, input, and approval UI
├── ui/threads/  # Session list
├── ui/settings/ # Server settings
└── ui/theme/    # Compose theme and styling
```