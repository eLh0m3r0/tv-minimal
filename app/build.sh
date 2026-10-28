#!/usr/bin/env bash
# Reproducible APK build without Gradle: aapt2 -> javac -> R8 -> zipalign -> apksigner.
# Usage: ./fetch_deps.sh && ./build.sh
# Env: JAVA_HOME (JDK 17), ANDROID_SDK (with build-tools/34.0.0 and platforms/android-34),
#      VERSION_NAME/VERSION_CODE, TVM_KEYSTORE + TVM_KEYSTORE_PASS_FILE + TVM_KEY_ALIAS (your own key, never committed).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk17}
export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_SDK:-$HOME/.local/share/android}
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
VERSION_NAME=${VERSION_NAME:-1.0.0}
VERSION_CODE=${VERSION_CODE:-1}
KS=${TVM_KEYSTORE:-$HOME/.config/tv-minimal-signing/release.jks}
KSPASS=${TVM_KEYSTORE_PASS_FILE:-$(dirname "$KS")/password}
KEY_ALIAS=${TVM_KEY_ALIAS:-tvminimal}
OUT=build/out
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1793000000}

[ -x "$JAVA_HOME/bin/javac" ] || { echo "JDK 17 not found: set JAVA_HOME" >&2; exit 1; }
[ -x "$BT/aapt2" ] && [ -f "$AJAR" ] || { echo "Android SDK build-tools 34.0.0 / android-34 not found: set ANDROID_SDK" >&2; exit 1; }
compgen -G 'libs/runtime/*' >/dev/null || { echo "libs/runtime is empty: run ./fetch_deps.sh" >&2; exit 1; }
[ -f "$KS" ] && [ -f "$KSPASS" ] || { echo "signing key not found: set TVM_KEYSTORE and TVM_KEYSTORE_PASS_FILE (see README)" >&2; exit 1; }

rm -rf build/aar build/res build/gen build/classes build/dex build/apk "$OUT"
mkdir -p build/aar build/res build/gen build/classes build/dex build/apk "$OUT"

# 1) unpack dependencies
LIBJARS=()
RES_ARGS=()
EXTRA_PKGS=()
for f in libs/runtime/*; do
  n=$(basename "$f"); n=${n%.*}
  case "$f" in
    *.jar) LIBJARS+=("$f") ;;
    *.aar)
      d=build/aar/$n; mkdir -p "$d"; unzip -q -o "$f" -d "$d"
      [ -f "$d/classes.jar" ] && LIBJARS+=("$d/classes.jar")
      if [ -d "$d/res" ] && [ -n "$(find "$d/res" -type f | head -1)" ]; then
        "$BT/aapt2" compile --dir "$d/res" -o "build/res/$n.zip"
        RES_ARGS+=(-R "build/res/$n.zip")
        EXTRA_PKGS+=("$(grep -o 'package="[^"]*"' "$d/AndroidManifest.xml" | head -1 | cut -d'"' -f2)")
      fi ;;
  esac
done

# 2) resources + manifest
"$BT/aapt2" compile --dir res -o build/res/app.zip
EXTRA=$(IFS=:; echo "${EXTRA_PKGS[*]}")
"$BT/aapt2" link -o build/apk/res.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay "${RES_ARGS[@]}" build/res/app.zip --java build/gen ${EXTRA:+--extra-packages "$EXTRA"} \
  --proguard build/gen/aapt-rules.pro --no-version-vectors

# 3) compile
CP="$AJAR:$(IFS=:; echo "${LIBJARS[*]}")"
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR:$BT/core-lambda-stubs.jar" -classpath "$CP" \
  -Xlint:-options -d build/classes $(find src/core src/main build/gen -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -f build/classes/cz/tvminimal/home/HomeActivity.class ] || { echo "javac failed"; exit 1; }
(cd build/classes && "$JAVA_HOME/bin/jar" cf ../app-classes.jar .)

# 4) R8 (shrink, no obfuscation for readable stack traces)
PG=(--pg-conf proguard.pro --pg-conf build/gen/aapt-rules.pro)
for p in build/aar/*/proguard.txt; do [ -f "$p" ] && PG+=(--pg-conf "$p"); done
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --lib "$AJAR" \
  --output build/dex "${PG[@]}" build/app-classes.jar "${LIBJARS[@]}"

# 5) package, align, sign
cp build/apk/res.apk build/apk/unsigned.apk
/usr/bin/python3 -I tools/add_dex.py build/apk/unsigned.apk build/dex
"$BT/zipalign" -f -p 4 build/apk/unsigned.apk build/apk/aligned.apk
APK=$OUT/tv-minimal-home-$VERSION_NAME-release-signed.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "file:$KSPASS" --ks-key-alias "$KEY_ALIAS" --out "$APK" build/apk/aligned.apk
"$BT/apksigner" verify --print-certs "$APK" | grep -E 'Signer #1 certificate (DN|SHA-256)'
sha256sum "$APK"
ls -l "$APK"
