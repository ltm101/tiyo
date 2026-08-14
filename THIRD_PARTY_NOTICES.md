# Third-party notices

This file is a practical inventory, not legal advice. Dependency metadata and the files shipped with each artifact remain authoritative.

## Android application dependencies

- AndroidX Core, AppCompat, WorkManager, and CardView — Apache License 2.0
- OkHttp 4.12.0 — Apache License 2.0
- Vosk Android 0.3.75 — Apache License 2.0
- JNA 5.18.1 — dual licensed LGPL-2.1-or-later or Apache License 2.0; this project elects Apache License 2.0 where permitted
- Bouncy Castle `bcprov-jdk15on` 1.70 — Bouncy Castle License (MIT-style)
- Jakarta Mail `com.sun.mail:jakarta.mail:2.0.1` — distributed under the licenses included in the artifact, historically CDDL-1.1 or GPL-2.0 with Classpath Exception
- JUnit 4.13.2 — Eclipse Public License 1.0 (test dependency only)
- `org.json` test artifact — JSON License (test dependency only)

## Bundled model

`app/src/main/assets/model-cn` is `vosk-model-small-cn-0.22`, published by Alpha Cephei for Android/Raspberry Pi under Apache License 2.0.

The Apache License 2.0 text is included at `third_party/licenses/APACHE-2.0.txt`.

## Native agent

The source under `native/agent` is part of this Tiyo release and is covered by the root MIT License except where a source file or Cargo dependency states otherwise. Cargo dependencies retain their respective upstream licenses. Use `cargo deny` or an equivalent license inventory before producing a public binary release.

## Excluded proprietary content

No Live2D Cubism Core binary, Live2D model, private character art, private sticker pack, or personal generated scene is distributed in this repository.
