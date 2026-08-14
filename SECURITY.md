# Security policy

## Supported versions

The project is currently alpha. Only the latest `main` revision is eligible for security fixes.

## Reporting a vulnerability

Do not include credentials, personal memory, screenshots, or exploit details in a public issue. Contact the repository maintainer privately using the security-reporting channel configured on the hosting platform. If no private channel exists yet, open a public issue containing only the sentence "Security contact needed".

Include the affected revision, Android version, reproduction conditions, impact, and a minimal proof of concept with all personal data removed.

## Security model

- provider credentials are encrypted with Android Keystore-backed AES-GCM storage
- release signing material is not part of the repository
- high-impact agent actions should default to explicit confirmation
- companion memory isolation is an application boundary, not an Android sandbox boundary
- a rooted or compromised device, malicious accessibility service, hostile provider, or modified APK is outside the protection offered by the app

Do not ship a release until the entire Git history has passed credential and large-file review.
