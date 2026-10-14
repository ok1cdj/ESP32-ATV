#!/bin/sh
# Cross-compiles leandvb (leansdr) and ldpc_tool (Blockstream/LDPC) for Android arm64 with the NDK.
# Sources: third_party/ESP32-DATV-RX/build/{leansdr,LDPC}, as tools/install.sh of ESP32-DATV-RX left them (leansdr
# 84c59e1 with leandvb-ldpc-helper.patch, LDPC 25799d9). Output: android/app/src/main/jniLibs/arm64-v8a/lib*.so –
# executables named lib*.so so that Android installs them into nativeLibraryDir, where an app may exec them.
#   android/native/build-native.sh           (NDK from ANDROID_NDK or the newest one in ~/Android/Sdk/ndk)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/third_party/ESP32-DATV-RX/build"
NDK=${ANDROID_NDK:-$(ls -d "$HOME"/Android/Sdk/ndk/* | sort -V | tail -1)}
API=28                       # aligned_alloc (ldpc_tool) is in bionic from API 28
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang++"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OBJ="$ROOT/android/native/build"
mkdir -p "$OUT" "$OBJ"
[ -f "$SRC/leansdr/src/apps/leandvb.cc" ] && [ -f "$SRC/LDPC/ldpc_tool.cc" ] ||
  { echo "missing sources: run third_party/ESP32-DATV-RX/tools/install.sh first"; exit 1; }
git -C "$SRC/leansdr" diff --quiet && { echo "leansdr without leandvb-ldpc-helper.patch"; exit 1; }

echo "== leandvb ($NDK)"
# a copy of the sources with android/native/leansdr-android.patch (clang rejects GCC's initialised VLAs; pipe sizes without pipe-max-size)
rm -rf "$OBJ/leansdr" && cp -r "$SRC/leansdr/src" "$OBJ/leansdr"
patch -s -d "$OBJ/leansdr" -p2 < "$ROOT/android/native/leansdr-android.patch"
"$CXX" -O3 -I"$OBJ/leansdr" -DVERSION='"leansdr-work-android"' -DLEANSDR_EXTENSIONS -D'exp10f(x)=powf(10.0f,(x))' \
  -Wno-sign-compare -Wno-array-bounds -Wno-unused-variable -Wno-deprecated -static-libstdc++ \
  "$OBJ/leansdr/apps/leandvb.cc" -o "$OUT/libleandvb.so"

echo "== ldpc_tool (NEON)"
F="-std=c++11 -Ofast -fno-exceptions -fno-rtti -static-libstdc++"
for o in tables_handler itls_handler mods_handler; do
  "$CXX" $F -c "$SRC/LDPC/$o.cc" -o "$OBJ/$o.o"
done
# NEON: LDPC's simd.hh tests __ARM_NEON__, which aarch64 compilers do not define; the NEON build is bit-exact with
# the generic one and twice as fast (docs/android-log.md, F2). The generic one stays in build/ for comparison.
"$CXX" $F -D__ARM_NEON__ "$SRC/LDPC/ldpc_tool.cc" "$OBJ"/tables_handler.o "$OBJ"/itls_handler.o "$OBJ"/mods_handler.o \
  -o "$OUT/libldpc_tool.so"
"$CXX" $F "$SRC/LDPC/ldpc_tool.cc" "$OBJ"/tables_handler.o "$OBJ"/itls_handler.o "$OBJ"/mods_handler.o -o "$OBJ/ldpc_tool_generic"
ls -la "$OUT"
