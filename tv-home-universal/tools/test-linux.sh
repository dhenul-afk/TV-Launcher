#!/usr/bin/env bash
# Host logic tests (padanan tools/Test-Windows.ps1). Tidak membutuhkan perangkat atau emulator.
# Catatan: org.json diunduh sebagai dependensi UJI saja; APK memakai org.json bawaan Android.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.local/toolchain"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

LIB="$TOOLS/lib"
mkdir -p "$LIB"
JSON_VERSION="20240303"
JSON_JAR="$LIB/json-$JSON_VERSION.jar"
if [ ! -f "$JSON_JAR" ]; then
  echo "[test] downloading org.json (test dependency only)..."
  curl -fsSL -o "$JSON_JAR" \
    "https://repo1.maven.org/maven2/org/json/json/$JSON_VERSION/json-$JSON_VERSION.jar"
fi
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "ERROR: $ANDROID_JAR tidak ditemukan. Jalankan tools/setup-toolchain.sh dulu." >&2
  exit 1
fi

CLASSES="$ROOT/.local/test-classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"

SRC="$ROOT/launcher/src/io/github/tvhome/universal"
SOURCES=(
  AppOrder AppMovePolicy GreetingText HomeOverridePolicy CarouselPolicy WatchTimerPolicy
  AppVisibility FootballData WeatherModel
)

echo "[test] compile"
javac -encoding UTF-8 --release 11 \
  -cp "$JSON_JAR:$ANDROID_JAR" \
  -d "$CLASSES" \
  $(for name in "${SOURCES[@]}"; do printf '%s ' "$SRC/$name.java"; done) \
  "$ROOT"/tests/*.java

FAILED=0
for TEST in LogicTest CarouselTest TimerTest AppVisibilityTest FootballTest WeatherTest; do
  echo "[test] $TEST"
  if ! java -cp "$CLASSES:$JSON_JAR:$ANDROID_JAR" "io.github.tvhome.universal.$TEST"; then
    FAILED=1
    echo "[test] GAGAL: $TEST" >&2
  fi
done

if [ "$FAILED" -ne 0 ]; then
  echo "[test] ada test yang gagal." >&2
  exit 1
fi
echo "[test] semua host test lulus."
