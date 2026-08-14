# Architecture

## Identity boundary

`CompanionProfile` provides the stable companion ID. `CompanionScope` captures that identity at the start of an operation so a background job cannot switch namespaces if the user changes the active companion mid-flight.

The companion ID scopes:

- persona workspace and `TIYO.md`
- chat sessions and history
- local memory stores and extraction jobs
- avatar selection and generated asset packs
- relationship and collaboration state

The built-in Koyo guide uses ID `koyo`; the legacy pre-release ID `tiyo` migrates to it. Tiyo is the product rather than a companion identity. Custom profiles use normalized IDs and do not inherit Koyo's relationship history or private memory.

Koyo's public guide knowledge lives in `KoyoGuide`. It covers Tiyo setup and troubleshooting without owner-specific biography or relationship history. The companion studio keeps Koyo visible even while a custom companion is active, and collaboration prompts may pass only her public guide brief to a Koyo participant.

## Agent path

The Android UI sends a request to `TiyoAgentRuntime`, which starts or connects to the bundled arm64 Rust agent. Provider configuration is read from `TiyoAgentConfig`, while credentials are retrieved from `TiyoSecureStore` only when needed.

The Rust source is under `native/agent`. Android-facing tool execution remains subject to the configured permission mode and Android runtime permissions.

## Memory path

Conversation updates create scoped extraction jobs. Extracted atomic memories use stable semantic keys so an updated fact replaces the same logical record instead of creating unlimited duplicates. Cross-device memory synchronization is optional and requires an explicit gateway and pairing token.

Companion isolation is enforced through application-level namespace selection. It does not protect against a malicious modified build or direct filesystem access on a compromised device.

## Visual path

The public build uses a lightweight frame renderer and generated/custom 2D assets. Live2D code, binaries, and models are not present. A generated companion asset pack is reviewed by the existing quality gate before activation.

## Proactivity path

Device events and periodic workers create structured opportunities. Policy code decides whether an opportunity is eligible before a provider generates text. Rate limits, quiet conditions, permission state, and user settings should be checked before delivery.
