#!/bin/sh
# Builds the Android spike APK without Gradle: the Rust core with cargo-ndk
# (rustup's toolchain in ~/.cargo/bin), then aapt2, javac, d8, zipalign and
# apksigner from the SDK. Output: android/build/firmwave.apk
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-37.0/android.jar"
B=build
export PATH="$HOME/.cargo/bin:$PATH"

rm -rf "$B"
mkdir -p "$B/classes" "$B/dex" "$B/apk/lib/arm64-v8a"

(cd rust && ANDROID_HOME="$SDK" cargo ndk -t arm64-v8a -P 28 build --release)
cp rust/target/aarch64-linux-android/release/libmflr_android.so "$B/apk/lib/arm64-v8a/"

"$BT/aapt2" compile --dir app/res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest app/AndroidManifest.xml \
    --min-sdk-version 28 --target-sdk-version 33 "$B/res.zip"

javac --release 17 -Xlint:-options -cp "$JAR" -d "$B/classes" $(find app/src -name '*.java')
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')

cp "$B/base.apk" "$B/unsigned.apk"
cp "$B/dex/classes.dex" "$B/apk/"
(cd "$B/apk" && zip -q -r ../unsigned.apk classes.dex lib)
"$BT/zipalign" -p -f 4 "$B/unsigned.apk" "$B/aligned.apk"

# RELEASE=1: signed with the release key (its folder: RELEASE_KEY_DIR, by default ~/Documents/M-Flasher +
# signing; the password read from its file, never on the command line). Otherwise the debug key: a phone
# with a build of one key refuses a build of the other on top of it.
# The key keeps its M-Flasher + folder and names: FirmWave keeps the app's ID, so it keeps its key too.
if [ "${RELEASE:-}" = 1 ]; then
    RK="${RELEASE_KEY_DIR:-$HOME/Documents/M-Flasher + signing}"
    "$BT/apksigner" sign --ks "$RK/m-flasher-plus-release.jks" --ks-key-alias m-flasher-plus \
        --ks-pass "file:$RK/m-flasher-plus-release.password" \
        --out "$B/firmwave.apk" "$B/aligned.apk"
    "$BT/apksigner" verify --print-certs "$B/firmwave.apk" | grep -m1 "SHA-256"
else
    KS="$HOME/.android/debug.keystore"
    if [ ! -f "$KS" ]; then
        mkdir -p "$HOME/.android"
        keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
            -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
    fi
    "$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
        --out "$B/firmwave.apk" "$B/aligned.apk"
fi
echo "built $B/firmwave.apk"
