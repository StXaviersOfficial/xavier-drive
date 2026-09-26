#!/bin/bash
# build-apk.sh — build XavierDrive v1.0.3 APK WITHOUT Gradle.
# Toolchain: aapt2 + javac + d8 + zipalign + apksigner (build-tools 34, JDK17).
#
# v1.0.3 adds the Credential Manager native sign-in stack, so this build
# now merges AARs (Google Maven) the manual way:
#   - every AAR is unpacked (classes.jar + res/ + libs/*.jar)
#   - all res/ dirs are aapt2-compiled and linked together with the app res
#   - library code references <library-package>.R — we generate copy-R
#     classes (same constants, library package) so the IDs resolve
#   - everything goes through one d8 pass (auto multidex if ever needed)
set -e

SDK=/tmp/my-project/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
JDK=/tmp/my-project/jdk17
PROJ=/home/z/my-project/xavier-drive-fresh/android-app
OUT=$PROJ/build
AARS=/tmp/my-project/aars
KS=/home/z/my-project/.secure/upload-keystore.jks
KSPASS=$(cat /home/z/my-project/.secure/keystore.password)
VERSION=1.0.3

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/libs"

# ── 0. unpack the AAR/JAR library stack ────────────────────────────────
echo "[0/7] unpacking libraries..."
AAR_LIST="credentials-1.2.2 credentials-play-services-auth-1.2.2 googleid-1.1.0 play-services-auth-20.7.0 play-services-auth-api-phone-18.0.1 play-services-auth-base-18.0.4 play-services-base-18.0.1 play-services-basement-18.2.0 play-services-tasks-18.0.1 play-services-fido-20.0.1 fragment-1.0.0 loader-1.0.0"
for name in $AAR_LIST; do
  d="$OUT/libs/$name"
  mkdir -p "$d"
  unzip -o -q "$AARS/$name.aar" -d "$d" 'classes.jar' 'res/*' 'libs/*' 2>/dev/null || true
done
# plain jars (kotlin stdlib + coroutines + annotations)
cp "$AARS/kotlin-stdlib-1.8.22.jar" "$AARS/kotlinx-coroutines-core-jvm-1.7.1.jar" "$AARS/annotation-1.5.0.jar" "$OUT/libs/" 2>/dev/null || true

# flat classpath for javac/d8: every extracted classes.jar + libs/*.jar + plain jars
CLASSPATH_ENTRIES=()
DEX_INPUTS=()
for name in $AAR_LIST; do
  d="$OUT/libs/$name"
  if [ -f "$d/classes.jar" ]; then
    CLASSPATH_ENTRIES+=("$d/classes.jar")
    DEX_INPUTS+=("$d/classes.jar")
  fi
  if [ -d "$d/libs" ]; then
    for j in "$d/libs/"*.jar; do
      [ -f "$j" ] && CLASSPATH_ENTRIES+=("$j") && DEX_INPUTS+=("$j")
    done
  fi
done
for j in kotlin-stdlib-1.8.22.jar kotlinx-coroutines-core-jvm-1.7.1.jar; do
  CLASSPATH_ENTRIES+=("$OUT/libs/$j")
  DEX_INPUTS+=("$OUT/libs/$j")
done

CP=$(IFS=:; echo "${CLASSPATH_ENTRIES[*]}:$PLATFORM")

# ── 1. aapt2 compile: app res + every AAR res ──────────────────────────
echo "[1/7] aapt2 compile resources..."
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/app-res.zip"
RES_ZIPS=("$OUT/app-res.zip")
for name in $AAR_LIST; do
  rdir="$OUT/libs/$name/res"
  if [ -d "$rdir" ]; then
    "$BT/aapt2" compile --dir "$rdir" -o "$OUT/$name-res.zip"
    RES_ZIPS+=("$OUT/$name-res.zip")
  fi
done

# ── 2. aapt2 link: one resource table, one manifest ────────────────────
echo "[2/7] aapt2 link..."
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --auto-add-overlay \
  "${RES_ZIPS[@]}"

# ── 3. copy-R: library packages get R classes with the merged IDs ──────
echo "[3/7] generating library R classes..."
python3 - "$OUT/gen/com/stxaviers/app/R.java" <<'PYEOF'
import sys, os, re
src_path = sys.argv[1]
src = open(src_path).read()
body = re.split(r'^package\s+[\w.]+\s*;', src, maxsplit=1, flags=re.M)[1]  # drop everything up to package
for pkg in ['com.google.android.gms', 'androidx.credentials']:
    d = os.path.join(os.path.dirname(sys.argv[1]), '..', '..', '..', '..', 'r-java', *pkg.split('.'))
    d = os.path.abspath(d)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'R.java'), 'w').write('package %s;\n%s' % (pkg, body))
    print('  wrote', os.path.join(d, 'R.java'))
PYEOF

# ── 4. javac ────────────────────────────────────────────────────────────
echo "[4/7] javac..."
find "$OUT/gen" "$OUT/r-java" -name '*.java' > "$OUT/sources.txt"
find "$PROJ/src" -name '*.java' >> "$OUT/sources.txt"
"$JDK/bin/javac" --release 11 -nowarn \
  -classpath "$CP" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

# ── 5. d8: app classes + the whole library stack, one dex pass ─────────
echo "[5/7] d8..."
find "$OUT/classes" -name '*.class' > "$OUT/classlist.txt"
"$BT/d8" --release --lib "$PLATFORM" --min-api 24 \
  --output "$OUT/dex" \
  $(cat "$OUT/classlist.txt") \
  "${DEX_INPUTS[@]}"

# ── 6. package + zipalign ───────────────────────────────────────────────
echo "[6/7] package + zipalign..."
cd "$OUT"
cp base.apk unsigned.apk
# aapt2 link ships a stub classes.dex — remove it so the real dex(es)
# always get injected (zip -u alone would no-op with exit 12)
zip -q -d unsigned.apk 'classes.dex' 'classes2.dex' 'classes3.dex' 2>/dev/null || true
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" *.dex)
"$BT/zipalign" -f 4 unsigned.apk aligned.apk

# ── 7. sign ─────────────────────────────────────────────────────────────
echo "[7/7] apksigner sign..."
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass "pass:$KSPASS" \
  --ks-key-alias stxaviers-upload \
  --key-pass "pass:$KSPASS" \
  --out "$PROJ/xavierdrive$VERSION.apk" \
  aligned.apk

"$BT/apksigner" verify --print-certs "$PROJ/xavierdrive$VERSION.apk" | head -4
ls -la "$PROJ/xavierdrive$VERSION.apk"
echo "APK BUILD DONE — v$VERSION"
