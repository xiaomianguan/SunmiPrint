#!/usr/bin/env bash
#
# Build SunmiPrint.apk from the sources in this directory.
#
# Everything it needs is discovered at runtime, so it works on any machine that has
# a JDK and the Android SDK installed (any recent version - no paths are hardcoded):
#
#   JDK            javac, keytool, jar        (JAVA_HOME, PATH, Android Studio's jbr, ...)
#   build-tools    aapt2, d8, zipalign, apksigner
#   platform jar   platforms/android-*/android.jar
#
# Optional environment overrides (all autodetected by default):
#   JDK_HOME      JDK/JRE directory            SDK_ROOT      Android SDK root
#   BUILD_TOOLS   build-tools directory        ANDROID_JAR   platform android.jar
#   KS KS_ALIAS KS_PASS                       signing keystore / alias / password
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

say()  { printf '%s\n' "$*"; }
die()  { printf 'build.sh: %s\n' "$*" >&2; exit 1; }
step() { printf '\n[%s] %s\n' "$1" "$2"; }

# 'sort -V' is GNU-only; fall back to a plain reverse sort elsewhere (macOS).
if printf '1.0\n' | sort -V >/dev/null 2>&1; then SORT_V='sort -Vr'; else SORT_V='sort -r'; fi

# --------------------------------------------------------------- JDK
# Resolve symlinks without relying on 'readlink -f' (absent on macOS).
resolve() {
    local p="$1" t
    while [ -L "$p" ]; do
        t="$(readlink "$p")"
        case "$t" in
            /*) p="$t" ;;
            *)  p="$(dirname "$p")/$t" ;;
        esac
    done
    printf '%s' "$p"
}

find_jdk() {
    local c d
    if [ -n "${JDK_HOME:-}" ]; then
        [ -x "$JDK_HOME/bin/javac" ] || die "JDK_HOME=$JDK_HOME does not contain bin/javac"
        printf '%s' "$JDK_HOME"; return 0
    fi
    if command -v javac >/dev/null 2>&1; then
        c="$(resolve "$(command -v javac)")"
        printf '%s' "$(dirname "$(dirname "$c")")"; return 0
    fi
    for d in \
        "$HOME/android-studio/jbr" \
        "$HOME/Android/android-studio/jbr" \
        "/opt/android-studio/jbr" \
        "/usr/local/android-studio/jbr" \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/.jdks"/* "$HOME/jdk"* "$HOME/jdk-"* "$HOME/java"* \
        /usr/lib/jvm/* /opt/jdk* /opt/java* /usr/local/jdk* /usr/local/java* \
        "$HOME/Library/Java/JavaVirtualMachines"/*/Contents/Home \
        /Library/Java/JavaVirtualMachines/*/Contents/Home \
    ; do
        [ -x "$d/bin/javac" ] && { printf '%s' "$d"; return 0; }
    done
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] && { printf '%s' "$JAVA_HOME"; return 0; }
    die "no JDK with javac found. Install a JDK (8 or newer), or set JDK_HOME=/path/to/jdk"
}

# --------------------------------------------------------------- SDK
find_sdk() {
    local d
    if [ -n "${SDK_ROOT:-}" ]; then
        [ -d "$SDK_ROOT/build-tools" ] || die "SDK_ROOT=$SDK_ROOT does not look like an Android SDK (no build-tools/)"
        printf '%s' "$SDK_ROOT"; return 0
    fi
    for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "$HOME/Android/Sdk" "$HOME/Android/sdk" "$HOME/android-sdk" \
             "$HOME/Library/Android/sdk" "$HOME/.local/share/android-sdk" \
             /opt/android-sdk /opt/android-sdk-linux /usr/lib/android-sdk \
    ; do
        [ -n "$d" ] && [ -d "$d/build-tools" ] && { printf '%s' "$d"; return 0; }
    done
    die "no Android SDK found. Install one, or set SDK_ROOT=/path/to/sdk"
}

find_build_tools() {
    local sdk="$1" v
    if [ -n "${BUILD_TOOLS:-}" ]; then
        [ -d "$BUILD_TOOLS" ] || die "BUILD_TOOLS=$BUILD_TOOLS is not a directory"
        printf '%s' "$BUILD_TOOLS"; return 0
    fi
    for v in $(ls -1 "$sdk/build-tools" 2>/dev/null | $SORT_V); do
        if [ -x "$sdk/build-tools/$v/aapt2" ] && [ -x "$sdk/build-tools/$v/d8" ] \
        && [ -x "$sdk/build-tools/$v/zipalign" ] && [ -x "$sdk/build-tools/$v/apksigner" ]; then
            printf '%s' "$sdk/build-tools/$v"; return 0
        fi
    done
    die "no build-tools with aapt2+d8+zipalign+apksigner under $sdk/build-tools. Install one: sdkmanager 'build-tools;30.0.3'"
}

find_android_jar() {
    local sdk="$1" v
    if [ -n "${ANDROID_JAR:-}" ]; then
        [ -f "$ANDROID_JAR" ] || die "ANDROID_JAR=$ANDROID_JAR does not exist"
        printf '%s' "$ANDROID_JAR"; return 0
    fi
    for v in $(ls -1 "$sdk/platforms" 2>/dev/null | sed -n 's/^android-\([0-9][0-9]*\)$/\1/p' | sort -nr); do
        [ -f "$sdk/platforms/android-$v/android.jar" ] && { printf '%s' "$sdk/platforms/android-$v/android.jar"; return 0; }
    done
    die "no platforms/android-*/android.jar under $sdk/platforms. Install one: sdkmanager 'platforms;android-33'"
}

say "SunmiPrint build - autodetecting toolchain"

JDK="$(find_jdk)" || exit 1
JAVAC="$JDK/bin/javac"
KEYTOOL="$JDK/bin/keytool"
JAR="$JDK/bin/jar"
[ -x "$KEYTOOL" ] || die "$JDK/bin/keytool is missing (need a full JDK, not just a JRE)"
export JAVA_HOME="$JDK"        # d8 / apksigner are jar wrappers and need this
say "  JDK          $JDK  ($("$JAVAC" -version 2>&1 | head -1))"

SDK="$(find_sdk)" || exit 1
BT="$(find_build_tools "$SDK")" || exit 1
AJAR="$(find_android_jar "$SDK")" || exit 1
say "  build-tools  $BT"
say "  android.jar  $AJAR"

# javac flags differ across JDK generations: JDK 8 takes -source/-target 1.7 with
# -bootclasspath, JDK 9+ dropped both for the Android build style. Probe and pick.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf 'public class Probe {}\n' > "$TMP/Probe.java"
if "$JAVAC" -source 1.7 -target 1.7 -bootclasspath "$AJAR" -d "$TMP" "$TMP/Probe.java" >/dev/null 2>&1; then
    JAVAC_FLAGS=(-source 1.7 -target 1.7 -bootclasspath "$AJAR")
elif "$JAVAC" -source 8 -target 8 -classpath "$AJAR" -d "$TMP" "$TMP/Probe.java" >/dev/null 2>&1; then
    JAVAC_FLAGS=(-source 8 -target 8 -classpath "$AJAR")
elif "$JAVAC" -classpath "$AJAR" -d "$TMP" "$TMP/Probe.java" >/dev/null 2>&1; then
    JAVAC_FLAGS=(-classpath "$AJAR")
else
    die "cannot compile against $AJAR with $JAVAC"
fi
say "  javac flags  ${JAVAC_FLAGS[*]}"

if command -v zip >/dev/null 2>&1; then ZIP="zip -q"; else ZIP=""; fi
say "  zip          ${ZIP:-<absent, using jar from the JDK>}"

# Optional keystore override, e.g. the AOSP test platform key.
KS="${KS:-$HERE/keystore/ks.jks}"
KS_ALIAS="${KS_ALIAS:-sunmiprint}"
KS_PASS="${KS_PASS:-android}"

cd "$HERE"
SRC=src/cc/wuersan008/smprint/MainActivity.java
[ -f "$SRC" ] || die "$SRC not found"

rm -rf build/classes build/dex build/base.apk build/aligned.apk build/SunmiPrint.apk
mkdir -p build/classes build/dex

step 1/5 javac
"$JAVAC" "${JAVAC_FLAGS[@]}" -encoding UTF-8 -nowarn -d build/classes "$SRC"

step 2/5 d8
"$BT/d8" --min-api 23 --lib "$AJAR" --output build/dex \
    $(find build/classes -name '*.class')

step 3/5 "aapt2 link"
"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$AJAR" \
    --min-sdk-version 23 --target-sdk-version 23 --version-code 1 --version-name 1.0
if [ -n "$ZIP" ]; then
    (cd build/dex && $ZIP -u ../base.apk classes.dex)
else
    (cd build/dex && "$JAR" uf ../base.apk classes.dex >/dev/null)
fi

step 4/5 zipalign
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

step 5/5 "sign (keystore: $KS)"
if [ ! -f "$KS" ]; then
    say "  creating a new keystore"
    mkdir -p "$(dirname "$KS")"
    "$KEYTOOL" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" -keyalg RSA \
        -keysize 2048 -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=SunmiPrint,O=wuersan008"
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out build/SunmiPrint.apk build/aligned.apk

"$BT/apksigner" verify --print-certs build/SunmiPrint.apk >/dev/null \
    || die "the signed APK failed verification"

say ""
ls -la build/SunmiPrint.apk
say ""
say "OK -> $HERE/build/SunmiPrint.apk"
say "Install with:  adb shell settings put global p1_security_trans_mode 0 && \\"
say "                 adb install -r build/SunmiPrint.apk && \\"
say "                 adb shell settings put global p1_security_trans_mode 1"
