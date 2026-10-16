#!/usr/bin/env bash
# Build SunmiPrint.apk from the sources in this directory.
# Toolchain: JDK8 (javac/jarsigner) + Android SDK build-tools (aapt2/d8/apksigner/zipalign).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BT=/home/puffercat/Android/Sdk/build-tools/30.0.3
JDK=/home/puffercat/liangmian/tools/jdk8
AJAR=/home/puffercat/Android/Sdk/platforms/android-33/android.jar

# Optional keystore override, e.g. the AOSP test platform key.
KS="${KS:-$HERE/keystore/ks.jks}"
KS_ALIAS="${KS_ALIAS:-sunmiprint}"
KS_PASS="${KS_PASS:-android}"

cd "$HERE"
SRC=src/cc/wuersan008/smprint/MainActivity.java

rm -rf build/classes build/dex build/base.apk build/aligned.apk build/SunmiPrint.apk
mkdir -p build/classes build/dex

echo "[1/5] javac"
"$JDK/bin/javac" -source 1.7 -target 1.7 -bootclasspath "$AJAR" -encoding UTF-8 -nowarn \
    -d build/classes "$SRC"

echo "[2/5] d8"
"$BT/d8" --min-api 23 --lib "$AJAR" --output build/dex \
    $(find build/classes -name '*.class')

echo "[3/5] aapt2 link"
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$AJAR" \
    --min-sdk-version 23 --target-sdk-version 23 --version-code 1 --version-name 1.0 2>/dev/null
(cd build/dex && zip -q -u ../base.apk classes.dex)

echo "[4/5] zipalign"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

echo "[5/5] sign (keystore: $KS)"
if [ ! -f "$KS" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" -keyalg RSA \
        -keysize 2048 -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=SunmiPrint,O=wuersan008" 2>/dev/null
fi
JAVA_HOME="$JDK" "$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out build/SunmiPrint.apk build/aligned.apk

ls -la build/SunmiPrint.apk
echo "OK -> $HERE/build/SunmiPrint.apk"
