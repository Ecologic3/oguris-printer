#!/bin/sh
# Builds OgurisPrinter-1.1.1.apk with the manual pipeline (no Gradle).
set -e
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/workspace/android-apk/toolchain/amazon-corretto-17.0.20.12.1-linux-x64"
SDK="$HOME/workspace/android-apk/toolchain/sdk"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
export PATH="$JAVA_HOME/bin:$BT:$PATH"

rm -rf build/classes build/dex build/gen
mkdir -p build/classes build/dex build/gen
rm -f build/res.zip build/unsigned.apk build/aligned.apk

aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/unsigned.apk -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml --java build/gen \
    --version-code 3 --version-name 1.1.1 build/res.zip
javac -encoding UTF-8 -source 8 -target 8 -cp "$ANDROID_JAR" \
    -d build/classes $(find src build/gen -name '*.java')
d8 --min-api 26 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks oguris-printer.keystore --ks-key-alias oguris-printer \
    --ks-pass pass:ogurisprinter --key-pass pass:ogurisprinter \
    --out OgurisPrinter-1.1.1.apk build/aligned.apk
apksigner verify OgurisPrinter-1.1.1.apk
echo "Built OgurisPrinter-1.1.1.apk"
