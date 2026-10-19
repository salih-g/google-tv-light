#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle): aapt2 + javac + d8 + apksigner.
#   launcher/build.sh            -> launcher/build/tvhome.apk
# Install with: .venv/bin/python -I tvclean.py install launcher/build/tvhome.apk --yes
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" "$B/assets/wallpapers"
# Only the <scene>-<slot>.jpg frames ship; CREDITS.md stays in the repo.
cp wallpapers/*.jpg "$B/assets/wallpapers/"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A "$B/assets" --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(( $(wc -c < "$B/tvhome.apk") / 1024 )) KB)"
