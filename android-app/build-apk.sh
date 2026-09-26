#!/bin/bash
# build-apk.sh — build Xavier's Drive v1.0.1 APK WITHOUT Gradle.
# Toolchain: aapt2 + javac + d8 + zipalign + apksigner (build-tools 34, JDK17).
set -e

SDK=/tmp/my-project/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
JDK=/tmp/my-project/jdk17
PROJ=/home/z/my-project/android-app
OUT=$PROJ/build
KS=/home/z/my-project/.secure/upload-keystore.jks
KSPASS=$(cat /home/z/my-project/.secure/keystore.password)

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources..."
$BT/aapt2 compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link (manifest + resources -> base APK + R.java)..."
$BT/aapt2 link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[3/6] javac (Java 17 -> Android-compatible bytecode)..."
find "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
find "$PROJ/src" -name '*.java' >> "$OUT/sources.txt"
$JDK/bin/javac --release 11 -nowarn \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "[4/6] d8 -> classes.dex..."
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
$BT/d8 --release --lib "$PLATFORM" --min-api 24 \
  --output "$OUT/dex" \
  $(cat "$OUT/classlist.txt")

echo "[5/6] package + zipalign..."
cd "$OUT"
cp base.apk unsigned.apk
cd dex && zip -q -u ../unsigned.apk classes.dex && cd ..
$BT/zipalign -f 4 unsigned.apk aligned.apk

echo "[6/6] apksigner sign..."
$BT/apksigner sign \
  --ks "$KS" --ks-pass "pass:$KSPASS" \
  --ks-key-alias stxaviers-upload \
  --key-pass "pass:$KSPASS" \
  --out "$PROJ/xavierdrive1.0.1.apk" \
  aligned.apk

$BT/apksigner verify --print-certs "$PROJ/xavierdrive1.0.1.apk" | head -4
ls -la "$PROJ/xavierdrive1.0.1.apk"
echo "APK BUILD DONE"
