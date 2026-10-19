#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds both apps and copies them to dist/ for the installer.
#   ./scripts/build.sh                 receiver with upstream prebuilt native libs (armeabi-v7a)
#   ./scripts/build.sh --native-source receiver native code built from the submodules (all ABIs)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE=prebuilt
[ "${1:-}" = "--native-source" ] && NATIVE=source

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
for d in launcher receiver; do
  if [ ! -f "$ROOT/$d/local.properties" ] && [ -z "${ANDROID_HOME:-}" ]; then
    for sdk in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
      [ -d "$sdk" ] && { echo "sdk.dir=$sdk" > "$ROOT/$d/local.properties"; break; }
    done
  fi
done

echo "== launcher"
(cd "$ROOT/launcher" && ./gradlew -q assembleStandardRelease)
cp "$ROOT/launcher/app/build/outputs/apk/standard/release/app-standard-release.apk" "$ROOT/dist/couchkit-home.apk"

echo "== receiver ($NATIVE native libs)"
if [ "$NATIVE" = prebuilt ]; then
  [ -f "$ROOT/receiver/app/src/main/jniLibs/armeabi-v7a/libairplay_native.so" ] || "$ROOT/receiver/scripts/fetch-prebuilt-native.sh"
  (cd "$ROOT/receiver" && ./gradlew -q assembleRelease -PprebuiltNative=true)
else
  (cd "$ROOT" && git submodule update --init --depth 1)
  (cd "$ROOT/receiver" && ./gradlew -q assembleRelease)
fi
cp "$ROOT/receiver/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/couchkit-cast.apk"
ls -l "$ROOT/dist/"*.apk
