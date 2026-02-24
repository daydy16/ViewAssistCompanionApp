#!/bin/bash
# Sets up Android SDK in WSL (ARM64) and builds the APK.
set -e

ANDROID_SDK_ROOT="$HOME/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"

echo "================================================"
echo "  Android SDK Setup + APK Build (WSL / ARM64)"
echo "================================================"
echo ""

# ── 1. Download command-line tools if not present ─────────────────────────────
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [[ ! -f "$SDKMANAGER" ]]; then
  echo "[1/5] Downloading Android command-line tools..."
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  TMP_ZIP="/tmp/cmdline-tools.zip"

  curl -L --progress-bar "$CMDLINE_TOOLS_URL" -o "$TMP_ZIP"

  echo "Extracting..."
  unzip -q "$TMP_ZIP" -d "$ANDROID_SDK_ROOT/cmdline-tools"
  # Google zips to a folder called "cmdline-tools" → rename to "latest"
  mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest" 2>/dev/null || true
  rm "$TMP_ZIP"
  echo "  ✓ Command-line tools installed"
else
  echo "[1/5] Command-line tools already installed, skipping."
fi

# ── 2. Accept licenses ─────────────────────────────────────────────────────────
echo ""
echo "[2/5] Accepting SDK licenses..."
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null 2>&1 || true
echo "  ✓ Licenses accepted"

# ── 3. Install required SDK components ────────────────────────────────────────
echo ""
echo "[3/5] Installing SDK components (platform, build-tools, NDK, CMake)..."
echo "      This may take 5-15 minutes on first run..."
echo ""

"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"

echo "  ✓ SDK components installed"

# ── 4. Write local.properties ─────────────────────────────────────────────────
echo ""
echo "[4/5] Writing local.properties..."
cd "$(dirname "$0")"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
echo "  ✓ local.properties -> $ANDROID_SDK_ROOT"

# ── 5. Build APK ──────────────────────────────────────────────────────────────
echo ""
echo "[5/5] Building APK..."
echo ""

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"

./gradlew assembleDebug --no-daemon

# ── Result ────────────────────────────────────────────────────────────────────
APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  SIZE=$(du -h "$APK" | cut -f1)
  echo ""
  echo "================================================"
  echo "  ✓ BUILD SUCCESSFUL"
  echo "================================================"
  echo "  APK size: $SIZE"
  echo "  Location: $(pwd)/$APK"
else
  echo ""
  echo "✗ Build failed - no APK found."
  echo "  Run './gradlew assembleDebug --stacktrace' for details."
  exit 1
fi
