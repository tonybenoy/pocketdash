#!/usr/bin/env bash
# Builds dashboard.apk without Gradle. Toolchain lives in ~/tools (JDK 17 + Android SDK).
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/tools/jdk17 PATH=$HOME/tools/jdk17/bin:$PATH
SDK=$HOME/tools/sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
OUT=$HOME/tools/build-dashboard   # build off the slow /mnt/c mount
KS=$HOME/tools/debug.keystore

rm -rf "$OUT"; mkdir -p "$OUT/classes"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=dashboard" >/dev/null 2>&1

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 -A assets -0 .gz -0 .tflite "$OUT/res.zip"
TF=$HOME/tools/tflite
javac -source 8 -target 8 -Xlint:-options -cp "$JAR:$TF/api/classes.jar:$TF/rt/classes.jar" -d "$OUT/classes" src/com/tonybenoy/dashboard/*.java
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class') "$TF/api/classes.jar" "$TF/rt/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 -I -c 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a"); z.write(sys.argv[2],"classes.dex"); z.write(sys.argv[3],"lib/arm64-v8a/libtensorflowlite_jni.so")' "$OUT/unsigned.apk" "$OUT/classes.dex" "$TF/rt/jni/arm64-v8a/libtensorflowlite_jni.so"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out dashboard.apk "$OUT/aligned.apk"
echo "built: $(pwd)/dashboard.apk"
