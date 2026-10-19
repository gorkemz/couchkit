#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Downloads the official upstream release APK (jqssun/android-airplay-server v0.0.33), checks its
# SHA-256 and copies its unmodified native libraries (all ABIs) into app/src/main/jniLibs.
# Only needed for `./gradlew assembleRelease -PprebuiltNative=true` (no NDK/CMake build).
# Source for these binaries: tag v0.0.33 (commit e347ee51ff3cb164d6e758496a428e3ab9b1df02) and the
# submodule commits pinned in this repository; see receiver/CHANGES.md.
set -euo pipefail

URL="https://github.com/jqssun/android-airplay-server/releases/download/v0.0.33/app-release.apk"
APK_SHA256="2203dcc76ad884265e6e01c2147c917f100e93f37b91bf728bb3eed466396b32"
ABIS="${ABIS:-armeabi-v7a arm64-v8a x86_64}"
# expected hashes of the armeabi-v7a libraries (the APK hash above covers all of them)
LIBS="363c233d180e925ce28774f7168e4c722b1caae87b8323267edf4cc5796d6448  libairplay_native.so
37d4ffc8c166bd8e670971985bc888e89279c771fcbf90ec70a592ad9645c9d7  libc++_shared.so
e4974e014c9672d18947a8f0e03989769847db8fd02823e722260bb4ffc09dd1  libcrypto.so
599f988dfbc3793d7c6bc2621c02f179eac3b091a9181f65ead1cf30bb570e11  liboboe.so"

here="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

sha() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

echo "Downloading $URL"
curl -fL --retry 3 -o "$tmp/upstream.apk" "$URL"
got="$(sha "$tmp/upstream.apk" | cut -d' ' -f1)"
[ "$got" = "$APK_SHA256" ] || { echo "SHA-256 mismatch: $got" >&2; exit 1; }

unzip -q -o "$tmp/upstream.apk" "lib/*" -d "$tmp/x"
(cd "$tmp/x/lib/armeabi-v7a" && echo "$LIBS" | sha -c -) || { echo "library hash mismatch" >&2; exit 1; }

# only the app's own libraries; AndroidX ones come from their AARs at build time
for abi in $ABIS; do
  dest="$here/app/src/main/jniLibs/$abi"; mkdir -p "$dest"
  echo "$LIBS" | while read -r _ name; do cp "$tmp/x/lib/$abi/$name" "$dest/"; done
  echo "OK: $abi -> $dest"
done
echo "Build with: ./gradlew assembleRelease -PprebuiltNative=true"
