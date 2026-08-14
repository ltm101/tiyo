param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$NdkVersion = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$nativeRoot = Join-Path $repoRoot "native\agent"
$manifest = Join-Path $nativeRoot "Cargo.toml"
$targetTriple = "aarch64-linux-android"
$androidApi = 26

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $AndroidSdk = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk) -or -not (Test-Path -LiteralPath $AndroidSdk)) {
    throw "Set ANDROID_HOME or pass -AndroidSdk with a valid Android SDK path"
}
if (-not (Test-Path -LiteralPath $manifest)) {
    throw "Native Agent source is missing: $manifest"
}

$ndkRoot = Join-Path $AndroidSdk "ndk"
if ([string]::IsNullOrWhiteSpace($NdkVersion)) {
    $NdkVersion = Get-ChildItem -LiteralPath $ndkRoot -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1 -ExpandProperty Name
}
$ndk = Join-Path $ndkRoot $NdkVersion
$toolchain = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang = Join-Path $toolchain "aarch64-linux-android$androidApi-clang.cmd"
$clangxx = Join-Path $toolchain "aarch64-linux-android$androidApi-clang++.cmd"
if (-not (Test-Path -LiteralPath $clang)) {
    throw "Android NDK clang was not found: $clang"
}

$env:CC_aarch64_linux_android = $clang
$env:CXX_aarch64_linux_android = $clangxx
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $clang

rustup target add $targetTriple
cargo build `
    --manifest-path $manifest `
    --release `
    --target $targetTriple `
    -p tiyo-ui `
    --bin tiyo

$sourceBinary = Join-Path $nativeRoot "target\$targetTriple\release\tiyo"
$targetDirectory = Join-Path $repoRoot "app\src\main\jniLibs\arm64-v8a"
$targetBinary = Join-Path $targetDirectory "libtiyo_agent.so"
if (-not (Test-Path -LiteralPath $sourceBinary)) {
    throw "Cargo completed without producing $sourceBinary"
}
New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
Copy-Item -LiteralPath $sourceBinary -Destination $targetBinary -Force
Write-Host "Native Agent copied to $targetBinary"
