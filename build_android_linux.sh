#!/bin/bash
set -e
NDK="${ANDROID_NDK_HOME:-/opt/android-ndk}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="output/android/arm64-v8a"
mkdir -p "$OUT"
export GOARCH=arm64 GOOS=android CGO_ENABLED=1
export CC="$TC/aarch64-linux-android35-clang"
export CXX="$TC/aarch64-linux-android35-clang++"
export CGO_CFLAGS="-march=armv8-a -O2"
export CGO_CXXFLAGS="-march=armv8-a -O2"
export CGO_LDFLAGS="-Wl,-rpath,/system/lib64 -Wl,-rpath,/vendor/lib64"
go build -ldflags="-s -w -linkmode external -extldflags '-Wl,-rpath,/system/lib64 -Wl,-rpath,/vendor/lib64' -checklinkname=0" -o "$OUT/openflux" .
