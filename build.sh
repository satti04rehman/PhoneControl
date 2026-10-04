#!/usr/bin/env bash
# Build PhoneControl.apk with only aapt2 + javac + d8 + apksigner (no gradle).
set -euo pipefail
cd "$(dirname "$0")"

BT="$HOME/Android/Sdk/build-tools/34.0.0"
PL="$HOME/Android/Sdk/platforms/android-34/android.jar"

rm -rf build && mkdir -p build/classes

"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip
"$BT/aapt2" link -o build/base.apk \
    -I "$PL" \
    --manifest app/src/main/AndroidManifest.xml \
    build/res.zip \
    --min-sdk-version 21 --target-sdk-version 34

javac -source 8 -target 8 -nowarn -cp "$PL" -d build/classes \
    $(find app/src/main/java -name '*.java') 2>&1 | grep -vE 'deprecat|warning|^Note' || true

# shell glob does not expand inside quotes; hand d8 the real file list
mapfile -t CLASSES < <(find build/classes -name '*.class')
"$BT/d8" --min-api 21 --lib "$PL" --output build "${CLASSES[@]}"

cp build/base.apk build/app-unsigned.apk
(cd build && zip -q app-unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

[ -f debug.keystore ] || keytool -genkeypair -v -keystore debug.keystore -alias pc \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=Abdul, OU=Personal, O=Abdul, L=Rawalpindi, S=PK, C=PK" >/dev/null 2>&1

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out PhoneControl.apk build/app-aligned.apk

"$BT/apksigner" verify PhoneControl.apk
echo "built: $(pwd)/PhoneControl.apk"