#!/bin/sh
# Cross-compiles leandvb (leansdr) and ldpc_tool (Blockstream/LDPC) for Android arm64 with the NDK.
# Sources: fetched here at fixed commits (the same as ESP32-DATV-RX tools/install.sh) into android/native/build/src,
# with ESP32-DATV-RX's leandvb-ldpc-helper.patch and our leansdr-android.patch. Output:
# android/app/src/main/jniLibs/arm64-v8a/lib*.so – executables named lib*.so so that Android installs them into
# nativeLibraryDir, where an app may exec them; and libdvbs2enc.so, our DVB-S2 encoder (android/native/dvbs2enc) as a
# JNI library for the TX tab.
#   android/native/build-native.sh       (NDK from ANDROID_NDK_HOME, ANDROID_NDK or the newest in ~/Android/Sdk/ndk)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LEANSDR_URL=https://github.com/pabr/leansdr.git
LEANSDR_COMMIT=84c59e1c7a1a79338d5722d63f28640cc9d350f3   # branch "work", DVB-S2 receiver
LDPC_URL=https://github.com/Blockstream/LDPC.git
LDPC_COMMIT=25799d96f5bee9635c534cee710e7b8cfff94de1
HELPER_PATCH="$ROOT/third_party/ESP32-DATV-RX/tools/leandvb-ldpc-helper.patch"   # partial pipe reads/writes
NDK=${ANDROID_NDK_HOME:-${ANDROID_NDK:-$(ls -d "$HOME"/Android/Sdk/ndk/* 2>/dev/null | sort -V | tail -1)}}
API=28                       # aligned_alloc (ldpc_tool) is in bionic from API 28
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang++"
OUT="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
OBJ="$ROOT/android/native/build"
SRC="$OBJ/src"
[ -x "$CXX" ] || { echo "no NDK clang at $CXX (set ANDROID_NDK_HOME)"; exit 1; }
[ -f "$HELPER_PATCH" ] || { echo "missing $HELPER_PATCH: git submodule update --init third_party/ESP32-DATV-RX"; exit 1; }
mkdir -p "$OUT" "$SRC"

# fetch(dir, url, commit): a clean checkout of exactly that commit
fetch() {
  [ -d "$1/.git" ] || git init -q "$1"
  if ! git -C "$1" cat-file -e "$3^{commit}" 2>/dev/null; then
    git -C "$1" fetch -q --depth 1 "$2" "$3"
  fi
  git -C "$1" checkout -q -f "$3"
  git -C "$1" clean -q -fdx
}

echo "== sources: leansdr ${LEANSDR_COMMIT%${LEANSDR_COMMIT#???????}}, LDPC ${LDPC_COMMIT%${LDPC_COMMIT#???????}}"
fetch "$SRC/leansdr" "$LEANSDR_URL" "$LEANSDR_COMMIT"
git -C "$SRC/leansdr" apply "$HELPER_PATCH"
# clang rejects GCC's initialised VLAs; apps cannot read pipe-max-size, so the helper pipes stayed at 64 kB and the
# scheduler stopped at 333 kS/s: the patch halves the size until the kernel takes it (1 MiB)
patch -s -d "$SRC/leansdr" -p1 < "$ROOT/android/native/leansdr-android.patch"
fetch "$SRC/LDPC" "$LDPC_URL" "$LDPC_COMMIT"

echo "== leandvb ($NDK)"
"$CXX" -O3 -I"$SRC/leansdr/src" -DVERSION='"leansdr-work-android"' -DLEANSDR_EXTENSIONS -D'exp10f(x)=powf(10.0f,(x))' \
  -Wno-sign-compare -Wno-array-bounds -Wno-unused-variable -Wno-deprecated -static-libstdc++ \
  "$SRC/leansdr/src/apps/leandvb.cc" -o "$OUT/libleandvb.so"

echo "== ldpc_tool (NEON)"
F="-std=c++11 -Ofast -fno-exceptions -fno-rtti -static-libstdc++"
for o in tables_handler itls_handler mods_handler; do
  "$CXX" $F -c "$SRC/LDPC/$o.cc" -o "$OBJ/$o.o"
done
# NEON: LDPC's simd.hh tests __ARM_NEON__, which aarch64 compilers do not define; the NEON build is bit-exact with
# the generic one and twice as fast on a Snapdragon 8 Gen 1. The generic one stays in build/ for comparison.
"$CXX" $F -D__ARM_NEON__ "$SRC/LDPC/ldpc_tool.cc" "$OBJ"/tables_handler.o "$OBJ"/itls_handler.o "$OBJ"/mods_handler.o \
  -o "$OUT/libldpc_tool.so"
"$CXX" $F "$SRC/LDPC/ldpc_tool.cc" "$OBJ"/tables_handler.o "$OBJ"/itls_handler.o "$OBJ"/mods_handler.o -o "$OBJ/ldpc_tool_generic"
echo "== dvbs2enc (JNI)"
"${CXX%++}" -std=c11 -O2 -Wall -Wextra -Werror -shared -fPIC -I"$ROOT/android/native/dvbs2enc" \
  "$ROOT/android/native/dvbs2enc/dvbs2enc.c" "$ROOT/android/native/dvbs2enc/dvbs2enc_jni.c" -o "$OUT/libdvbs2enc.so"
ls -la "$OUT"
