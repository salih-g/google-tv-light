#!/usr/bin/env bash
# Build the browser APK with the bare SDK tools (no Gradle): aapt2 + javac + d8 + apksigner.
#   browser/build.sh            -> browser/build/tvbrowser.apk
# Refresh the blocklist first if it is older than a month: python3 -I browser/tools/build-blocklist.py
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets/wallpapers"
cp assets/* "$B/assets/"
cp ../launcher/wallpapers/*.jpg "$B/assets/wallpapers/"   # the start page shares the launcher's wallpapers
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A "$B/assets" --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
# The wallpaper engine is shared with the launcher: copy it into this package at build time.
mkdir -p "$B/gen/dev/salih/tvbrowser"
sed 's/^package dev\.salih\.tvhome;/package dev.salih.tvbrowser;/' ../launcher/src/dev/salih/tvhome/Wallpapers.java \
  > "$B/gen/dev/salih/tvbrowser/Wallpapers.java"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
KS=../launcher/debug.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --out "$B/tvbrowser.apk" "$B/aligned.apk"
echo "built $B/tvbrowser.apk ($(( $(wc -c < "$B/tvbrowser.apk") / 1024 )) KB)"
