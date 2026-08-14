# Contributing

Tiyo is in alpha. Small, reviewable changes with tests are preferred.

## Development workflow

1. Create a branch from `main`
2. Keep credentials and signing files outside the repository
3. Run `gradlew testDebugUnitTest assembleDebug`
4. If native code changed, run the Rust tests and rebuild the arm64 binary
5. Explain permission, privacy, migration, and memory-isolation effects in the pull request

## Project rules

- never add a personal persona, biography, memory archive, chat log, portrait, or private asset pack
- never embed API keys, mail authorization codes, tokens, passwords, or keystores
- keep custom companion persona, chat, memory, and assets scoped by companion ID
- require confirmation for destructive, external, financial, account, SMS, and broad file actions
- do not add proprietary SDKs or assets without an explicit redistribution review
- preserve the no-Live2D public boundary unless the required SDK and model licenses are independently cleared

## Code style

Kotlin targets Java 17. Rust formatting follows `native/agent/rustfmt.toml`. Add unit tests for deterministic policy, parser, memory, and identity behavior.

Contributions are accepted under the repository's MIT License. Third-party files must retain their original license and attribution.
