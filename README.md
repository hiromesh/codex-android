# Codex Android

一个用于远程连接 `codex app-server` 的 Android 客户端。它通过 WebSocket 发送消息、接收 Codex 的流式回复，并展示命令执行、文件修改、推理摘要和审批请求。

## 功能

- 最近 10 个服务端会话与历史消息恢复
- 流式 Markdown 回复
- 新建会话、继续已有会话、停止生成
- 模型与推理档位切换
- 命令与文件修改审批
- 命令输出、文件改动、推理摘要展示
- WebSocket 断线重连与会话全量对账；从锁屏或后台返回时自动同步当前会话

## 环境要求

- Android Studio（推荐使用项目自带的 Gradle Wrapper）
- Android SDK 36
- JDK 11 或 Android Studio 自带 JBR
- 已部署且可访问的 `codex app-server`

## 配置服务端

启动 App 后进入设置，填写：

| 项目 | 说明 |
| --- | --- |
| 服务器地址 | WebSocket 地址，例如 `wss://example.com:8443` |
| Token | 服务端 WebSocket Bearer Token |

保存后会返回首页。Token 只保存在设备本地，不应提交到仓库。

接口协议说明见 [docs/MOBILE_APP_API.md](docs/MOBILE_APP_API.md)，最小服务端连通性示例见 [docs/mock.py](docs/mock.py)。

## 本地运行

1. 用 Android Studio 打开本目录。
2. 等待 Gradle 同步完成。
3. 连接手机或启动模拟器后运行 `app`。
4. 在设置页面填入服务端 WebSocket 地址与 Token。

## 构建 Release APK

Android Studio 中选择 **Build → Generate App Bundles or APKs → Generate APKs**，选择 `release` 变体。

当前 `release` 变体为便于个人设备测试，暂以 debug 证书签名。正式发布前请配置独立的 release keystore，且绝不要提交 `.jks`/`.keystore` 文件或密码。

也可以使用命令行：

```bash
./gradlew assembleRelease
```

输出通常在：

```text
app/build/outputs/apk/release/app-release.apk
```

## 后台与断线行为

服务端任务会继续执行；不过 Android 在锁屏、后台或 Doze 下可能暂停网络或回收进程，WebSocket 的流式通知也不会由服务端重放。App 会在可用时重新连接，并通过 `thread/resume` 与 `thread/read` 同步会话，因此回到前台后能恢复最终消息。

本项目未实现 FCM/Bark 等后台完成推送；若需要通知，需要服务端额外接入推送转发。

## 项目结构

```text
app/src/main/java/com/hiro/codex_android/
├── data/       # WebSocket 协议、存储与数据模型
├── ui/chat/    # 对话、Markdown、输入与审批 UI
├── ui/threads/ # 会话列表
├── ui/settings/# 服务端设置
└── ui/theme/   # Compose 主题与样式
docs/           # 服务端 API 说明与测试脚本
```

## 安全提示

- 不要提交服务端 Token、签名密钥或本地配置。
- 不要将 `hiro.jks` 等密钥文件推送到 GitHub。
- 远程服务端如开启高权限沙箱或免审批策略，请确认仅对受信任的使用者开放。
