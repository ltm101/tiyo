# Optional runtime bundle

The open-source APK does not include the historical Termux bootstrap archive
because it was large, app-id-specific, and not reproducible from this repository
alone

The native Agent uses Android's system shell without this bundle

Maintainers who build an optional Python/Node/Git runtime must place an archive at
`runtime/bootstrap-aarch64.zip`, compile every path for the public application id,
and update `TiyoRuntime.VERSION` and `TiyoRuntime.SHA256`
