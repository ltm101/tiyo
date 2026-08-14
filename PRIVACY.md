# Privacy notes

Tiyo is a local-first Android application, but several optional features can transmit data to services selected by the user.

## Data stored on the device

Depending on enabled features, the app can store companion profiles, persona files, chat sessions, memory entries, generated assets, tasks, screen-time summaries, step summaries, weather context, notification-derived drafts, and peripheral configuration.

Provider API keys, mail authorization codes, pairing tokens, image-generation keys, and TTS keys are stored through `TiyoSecureStore`: values are AES-GCM encrypted and the encryption key is held by Android Keystore. Other preferences and content files are not necessarily encrypted at rest.

## Data that can leave the device

- prompts, chat history, tool context, and attached images sent to the active model provider
- prompts and reference images sent to an image-generation provider
- text sent to a configured TTS provider
- mail content and reports sent through a configured mail account
- location coordinates sent to the configured weather service
- memory snapshots sent to a gateway configured by the user

The project does not control those providers. Review their terms, retention policies, and data residency before use.

## Sensitive Android permissions

The manifest declares optional access for usage statistics, notifications, accessibility, location, activity recognition, microphone, broad file access, SMS, calendar, Bluetooth, alarms, vibration, and network access. These permissions support distinct features and should not be granted as a bundle without review.

Accessibility and notification access can expose private message content. SMS and broad storage access can cause material harm if misused. Keep high-impact agent actions in confirmation mode and test on a non-primary device first.

## Deleting data

Uninstalling the public application ID (`app.tiyo.opensource`) removes its normal app-private storage and Android Keystore entry. Files exported to shared storage, messages already sent to third parties, provider-side records, and external gateway copies must be removed separately.

## Maintainer checklist

Before distributing a fork:

- remove unused permissions and features
- use a fresh application ID and signing key
- scan the full Git history for secrets and personal assets
- publish a privacy policy matching the actual build and providers
- disclose every network endpoint and analytics SDK added by the fork
