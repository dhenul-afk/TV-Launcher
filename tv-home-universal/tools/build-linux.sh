#!/usr/bin/env bash
# Build Linux untuk TV Home Universal (padanan tools/Build-Windows.ps1).
# Hanya membangun APK debug. Tidak menyentuh perangkat apa pun.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.local/toolchain"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "ERROR: JDK tidak ditemukan di $JAVA_HOME. Jalankan tools/setup-toolchain.sh dulu." >&2
  exit 1
fi
if [ ! -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
  echo "ERROR: platforms;android-34 belum terpasang. Jalankan tools/setup-toolchain.sh dulu." >&2
  exit 1
fi
if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
  echo "ERROR: build-tools;34.0.0 belum terpasang. Jalankan tools/setup-toolchain.sh dulu." >&2
  exit 1
fi

AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
D8="$ANDROID_HOME/build-tools/34.0.0/d8"
ZIPALIGN="$ANDROID_HOME/build-tools/34.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

LAUNCHER="$ROOT/launcher"
BUILD="$ROOT/.local/build-linux"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/dist"

echo "[build] aapt2 compile"
"$AAPT2" compile --dir "$LAUNCHER/res" -o "$BUILD/res.zip"

echo "[build] aapt2 link"
(
  cd "$LAUNCHER"
  "$AAPT2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --java "$BUILD/gen" \
    -A assets \
    --min-sdk-version 26 --target-sdk-version 34 \
    "$BUILD/res.zip"
)

echo "[build] javac"
mapfile -t SOURCES < <(find "$LAUNCHER/src" "$BUILD/gen" -name '*.java' | sort)
javac -parameters -encoding UTF-8 --release 11 -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" "${SOURCES[@]}"

echo "[build] jar"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .

echo "[build] d8"
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" "$BUILD/classes.jar"

echo "[build] assemble apk"
# Salin semua entri base.apk (menormalkan backslash hasil aapt2), lalu tambah classes.dex.
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
seen = set()
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        name = item.filename.replace('\\', '/')
        if name == 'classes.dex' or name in seen:
            continue
        seen.add(name)
        info = zipfile.ZipInfo(name, date_time=item.date_time)
        info.external_attr = item.external_attr
        zout.writestr(info, zin.read(item.filename))
    zout.write(dex, 'classes.dex')
PY

echo "[build] zipalign"
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KEYSTORE="$ROOT/.local/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "[build] create debug keystore"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TV Home Universal Development"
fi

OUT="$ROOT/dist/TV-Home-Universal-debug.apk"
echo "[build] sign"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --out "$OUT" "$BUILD/aligned.apk"
"$APKSIGNER" verify --verbose "$OUT"

echo "[build] sha256"
sha256sum "$OUT"
echo "APK ready: $OUT"
echo "Build only. Tidak ada perubahan pada perangkat."
