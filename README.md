# Codex Android

An Android client for remotely connecting to `codex app-server`. It uses WebSockets to send prompts, receive streaming Codex responses, and display command execution, file changes, reasoning summaries, and approval requests.

## Features

- Latest 15 server-side sessions and conversation history restoration
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

Open **Settings** in the app and manage one or more **Agent 服务器** profiles. Each profile has:

| Field | Description |
| --- | --- |
| Type | Agent kind. Currently only **Codex** (`codex app-server`) is supported; Kimi Code / Claude Code / OpenCode are listed as upcoming |
| Name | Optional display name; defaults to the type name |
| Server URL | WebSocket endpoint, for example `wss://example.com:8443` |
| Token | Server WebSocket Bearer token |

Profiles can be individually enabled/disabled. The session list aggregates threads from all enabled profiles, with a badge showing which agent each session belongs to. Tokens are stored locally on the device and must never be committed to the repository.

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

## Web 版本

[web/](web/) 是同一 app-server 的桌面网页客户端（前后端一体 TypeScript 单仓库）：

```bash
cd web
npm install
npm run dev      # 开发：http://localhost:5175
npm run build && npm start   # 生产：单端口 http://localhost:3000
```

功能与安卓端完全等价（会话列表、流式 Markdown、审批、模型/档位切换、token 占用、语音输入、断线对账），桌面布局为左侧会话栏 + 主聊天区。由于浏览器 WebSocket 不能自定义请求头，服务端同时代理 `/ws/codex`（注入 Bearer token）与 `/ws/asr`（注入火山 ASR 鉴权头）。详见 [web/README.md](web/README.md)。
