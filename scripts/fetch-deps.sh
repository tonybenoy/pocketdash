#!/usr/bin/env bash
# Downloads the TensorFlow Lite runtime used for the face-presence feature into ~/tools/tflite (the layout build.sh expects).
# The JDK and Android SDK (build-tools 34, platform android-34) are expected in ~/tools/jdk17 and ~/tools/sdk.
set -euo pipefail
V=2.14.0
DEST=${TFLITE_DIR:-$HOME/tools/tflite}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for a in tensorflow-lite tensorflow-lite-api; do
  curl -fL -o "$TMP/$a.aar" "https://repo1.maven.org/maven2/org/tensorflow/$a/$V/$a-$V.aar"
done
mkdir -p "$DEST/rt" "$DEST/api"
unzip -qo "$TMP/tensorflow-lite.aar" -d "$DEST/rt"
unzip -qo "$TMP/tensorflow-lite-api.aar" -d "$DEST/api"
echo "TensorFlow Lite $V installed in $DEST"
