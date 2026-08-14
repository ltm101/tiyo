# Open-source release boundary

The public edition is created from a clean repository with no shared Git history with the private application.

## Public

- Android source and resources required to build the alpha app
- public Koyo built-in guide with Tiyo product knowledge
- custom companion identity, memory, collaboration, and asset-pack logic
- native arm64 agent binary plus corresponding Rust source
- Vosk small Chinese model and license notice
- tests and native build script

## Not public

- owner-specific Koyo relationship, biography, diary, memory database, conversation history, and private persona extensions
- personal names and owner-specific onboarding/model presets
- private API/mail/TTS/image credentials or signing material
- private portraits, stickers, generated rooms, debug captures, and handoff notes
- Live2D Cubism Core, Live2D runtime/sample code, and Live2D models
- optional private bootstrap archive and local-only maintenance tools

## Before every public release

1. scan the complete Git history for credentials and personal paths
2. inspect every file larger than 10 MiB and reject files larger than the host limit
3. build from a clean checkout
4. run Android unit tests and assemble the debug APK
5. confirm release artifacts are unsigned unless a maintainer supplies external signing
6. review manifest permissions and all network endpoint defaults
7. verify that each bundled model, binary, font, and image is redistributable
