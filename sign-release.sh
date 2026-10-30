#!/bin/bash
# Portable signer for notPipe+ (adjust the 3 paths below).
# Order matters: zipalign FIRST (v1 signing invalidates alignment).
JDK8=${JDK8:-/path/to/jdk8}
BT=${BT:-/path/to/android-sdk/build-tools/25.0.0}
SRC="$(cd "$(dirname "$0")" && pwd)"
APK="$SRC/app/build/outputs/apk/notPipePlus-$1-release-unsigned.apk"

"$BT/zipalign" -f 4 "$APK" "$APK.aligned"
"$JDK8/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$SRC/notpipeplus.keystore" --ks-pass pass:notpipeplus --key-pass pass:notpipeplus \
  --min-sdk-version 3 --v1-signing-enabled true --v2-signing-enabled false "$APK.aligned"
mv "$APK.aligned" "$SRC/notPipePlus-$1-release.apk"
echo "signed: $SRC/notPipePlus-$1-release.apk"
