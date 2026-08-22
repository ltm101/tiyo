# Tiyo 1.3.0 Open Source

Tiyo is an experimental Android companion shell that combines a local agent runtime, isolated companion identities, memory, device context, and optional automation in one app.

This repository is the public edition. It has an independent Git history and application ID, contains no private role biography, personal memory, API key, signing key, private runtime bundle, or Live2D SDK/model.

> Status: alpha. Build it for testing and development; do not treat it as a finished security product or a medical/safety service.

## What is included

- **Koyo (可又)** as the built-in Tiyo guide, with public product knowledge and no private biography
- Custom companion creation with a birth certificate and generated 2D asset pack
- Per-companion persona, chat, avatar, asset, and memory namespaces
- Explicit collaboration rules between independent companions
- An OpenAI-compatible agent provider configuration with bring-your-own-key storage
- Chat image understanding through the active vision-capable provider
- Phone-native Feishu, WeCom, QQ Bot, and Weixin iLink Presence adapters
- One shared companion/persona/memory path across every supported app body
- EnuMan private-state boundary: internal snapshots never become user-visible chat text
- Optional image generation, MiniMax TTS, MCP/Skill configuration, and native tools
- Screen-time, steps, weather, reminders, mail bridge, notifications, and optional BLE peripherals
- Kotlin unit tests and the Rust source for the bundled arm64 agent binary

## Open-source boundary

The public edition intentionally excludes:

- the private Koyo persona, relationship history, biography, and memories
- personal portraits, stickers, scene packs, and generated character assets
- Live2D Cubism Core, Live2D sample/runtime code, and Live2D models
- API keys, mail credentials, release keystores, and signing passwords
- the large optional Termux bootstrap archive
- private handoff notes, debug captures, and local-only tools

Koyo in this repository is a clean public guide identity. She knows how Tiyo's role creation, model setup, image understanding, memory isolation, permissions, collaboration, and troubleshooting are intended to work. The private application's Koyo relationship, owner-specific biography, memories, portraits, and conversation history are not included. The `com.koyo.screenwarden` namespace and some `koyo_*` resource/protocol names are retained implementation identifiers.

## Requirements

- Android Studio with JDK 17
- Android SDK 35
- Android 8.0 or newer (API 26+)
- A provider API key for online model features
- Optional: Rust 1.95 and Android NDK to rebuild the native arm64 agent

## Build

On Windows:

```powershell
$env:JAVA_HOME = '<Android Studio installation>\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest assembleDebug
```

On macOS/Linux, point `JAVA_HOME` and `ANDROID_HOME` to your local installations and run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written under `app/build/outputs/apk/debug/`.

Release builds are deliberately unsigned. Keep signing configuration outside Git.

Version 1.3.0 is the first mobile-native Presence release. Platform credentials are
stored through Android Keystore and are never embedded in this repository.

## Configure a model

Open the model settings in Tiyo and provide:

1. an OpenAI-compatible base URL
2. a model identifier
3. your own API key
4. a permission mode, starting with `ask`

The default endpoint is DeepSeek's official API and the default model is `deepseek-v4-flash`. No provider credential is embedded in the APK. Credentials entered in the app are encrypted with an AES-GCM key held by Android Keystore.

Image understanding uses the currently active provider. Select a model that accepts image input before attaching a photo. Vision-capable providers receive original images directly; text-only providers use the explicit fallback route. Image generation has its own optional provider/key fields.

## Companion isolation

Each custom companion receives its own stable ID and storage namespace. Persona files, session history, generated assets, local memories, and relationship state are resolved through that identity. A custom companion does not inherit Koyo's relationship or another companion's private history. Tiyo is the application; Koyo is its built-in guide; a custom companion is the user's independent primary companion.

Koyo remains selectable from the companion studio and can join a multi-companion discussion as a product guide. Collaboration context contains only the current task, Koyo's public guide brief, and the roles' public positions. Private diaries and unrelated history stay isolated.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the data flow and [docs/OPEN_SOURCE_BOUNDARY.md](docs/OPEN_SOURCE_BOUNDARY.md) for the release boundary.

## Native agent

The arm64 prebuilt used by the Android app is at `app/src/main/jniLibs/arm64-v8a/libtiyo_agent.so`. Its corresponding source is under `native/agent/`.

To rebuild it on Windows:

```powershell
.\scripts\build-native-agent.ps1
```

The optional bootstrap archive used by a fuller shell environment is not distributed. See `app/src/main/assets/runtime/README.md`.

## Permissions and privacy

Tiyo exposes powerful optional permissions, including usage access, notifications, accessibility, location, microphone, storage, SMS, calendar, and Bluetooth. Features should remain disabled until the user grants the relevant permission. Review [PRIVACY.md](PRIVACY.md) before testing on a personal phone.

No telemetry or hosted Tiyo backend is required by the source tree, but data sent to a configured model, mail, weather, image, or TTS provider is governed by that provider. A local build cannot make third-party APIs private.

## Security

Do not report vulnerabilities in a public issue. Follow [SECURITY.md](SECURITY.md). Before publishing a fork, run a secret scanner against the complete Git history, not only the working tree.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). By contributing, you agree that your contribution is available under the MIT License.

## Licenses

Tiyo source code is released under the [MIT License](LICENSE). Third-party libraries and the bundled Vosk model keep their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `third_party/licenses/`.
