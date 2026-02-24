#!/bin/bash
# Builds the APK using the Windows Android SDK (installed via Android Studio).
# Run this from WSL after Android Studio is installed on Windows.
set -e

# ── Detect Windows username ───────────────────────────────────────────────────
WIN_USER=$(powershell.exe -NoProfile -Command "[System.Environment]::UserName" 2>/dev/null | tr -d '\r\n')
if [[ -z "$WIN_USER" ]]; then
  echo "ERROR: Could not detect Windows username via PowerShell."
  exit 1
fi
echo "Windows user: $WIN_USER"

# ── Find Android SDK on Windows (via WSL path) ────────────────────────────────
SDK_WSL="/mnt/c/Users/$WIN_USER/AppData/Local/Android/Sdk"
if [[ ! -d "$SDK_WSL" ]]; then
  echo ""
  echo "ERROR: Android SDK not found at:"
  echo "  $SDK_WSL"
  echo ""
  echo "Please install Android Studio first:"
  echo "  https://developer.android.com/studio"
  echo ""
  echo "Then open Android Studio → SDK Manager → SDK Tools → check:"
  echo "  ✓ NDK (Side by Side)"
  echo "  ✓ CMake"
  echo ""
  exit 1
fi
echo "Android SDK found: $SDK_WSL"

# ── Write local.properties with Windows SDK path ──────────────────────────────
WIN_SDK_PATH=$(wslpath -w "$SDK_WSL" | sed 's/\\/\\\\/g')
echo "sdk.dir=$WIN_SDK_PATH" > local.properties
echo "SDK path written to local.properties"

# ── Get Windows path of this project ─────────────────────────────────────────
PROJECT_WIN=$(wslpath -w "$(pwd)")
echo "Project (Windows path): $PROJECT_WIN"
echo ""

# ── Run gradlew.bat on Windows via PowerShell ─────────────────────────────────
echo "=== Starting build (this may take 5-10 min on first run) ==="
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "
  \$ErrorActionPreference = 'Stop'
  Set-Location '$PROJECT_WIN'
  & '.\gradlew.bat' assembleDebug --no-daemon 2>&1
  if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }
"

# ── Result ────────────────────────────────────────────────────────────────────
APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  SIZE=$(du -h "$APK" | cut -f1)
  echo ""
  echo "✓ Build successful!"
  echo "  APK: $(pwd)/$APK ($SIZE)"
  echo "  Windows path: $(wslpath -w "$(pwd)/$APK")"
else
  echo ""
  echo "✗ Build failed - no APK found."
  exit 1
fi
