#!/bin/sh
# Builds the root helper (pcctool) for arm64 with the Android NDK and places it in the app as libpcctool.so.
# Usage: ANDROID_NDK_HOME=/path/to/ndk ./native/build.sh
set -e
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your Android NDK folder}"
DIR="$(cd "$(dirname "$0")" && pwd)"
HOST="$(ls "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/" | head -1)"
CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android28-clang"
OUT="$DIR/../app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$CC" -O2 -fPIE -pie -w -o "$OUT/libpcctool.so" "$DIR/pcctool.c" -ldl
echo "Built $OUT/libpcctool.so"
