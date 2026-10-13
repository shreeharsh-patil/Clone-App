#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/27.1.12297006}"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android33-clang"
export RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
for pair in aarch64-linux-android:arm64-v8a x86_64-linux-android:x86_64; do
  target="${pair%:*}"; abi="${pair#*:}"
  cargo build --manifest-path rust-core/Cargo.toml --locked --release --target "$target"
  mkdir -p "app/src/main/jniLibs/$abi"
  cp "rust-core/target/$target/release/libparallelnest_core.so" "app/src/main/jniLibs/$abi/"
done
