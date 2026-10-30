#!/usr/bin/env bash
# Instalasi toolchain build lokal (project-local, tanpa root).
# JDK: Eclipse Temurin 21 (Linux x64 tarball)
# Android SDK: cmdline-tools + platforms;android-34 + build-tools;34.0.0
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.local/toolchain"
mkdir -p "$TOOLS"
cd "$TOOLS"

log() { echo "[setup] $*"; }

# --- JDK ---
if [ ! -x "$TOOLS/jdk/bin/javac" ]; then
  log "downloading Temurin JDK 21..."
  curl -fsSL -o jdk.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p jdk
  tar -xzf jdk.tar.gz -C jdk --strip-components=1
  rm -f jdk.tar.gz
  log "JDK ready: $($TOOLS/jdk/bin/javac -version)"
fi

# --- Android SDK ---
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$TOOLS/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "downloading Android cmdline-tools..."
  curl -fsSL -o cmdline.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q cmdline.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f cmdline.zip
fi

SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
  log "installing platforms;android-34..."
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" "platforms;android-34" >"$TOOLS/sdk-platform.log" 2>&1
fi
if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  log "installing build-tools;34.0.0..."
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" "build-tools;34.0.0" >"$TOOLS/sdk-buildtools.log" 2>&1
fi

log "done."
log "ANDROID_HOME=$ANDROID_HOME"
ls "$ANDROID_HOME"
