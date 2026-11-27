#!/usr/bin/env bash
set -euo pipefail

abi="${1:?Android ABI required}"
output="${2:?absolute output directory required}"
case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    armeabi-v7a) target=armv7-linux-androideabi ;;
    x86_64) target=x86_64-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac

source_dir="$(mktemp -d)"
trap 'rm -rf "$source_dir"' EXIT
git -C "$source_dir" init -q
git -C "$source_dir" fetch --depth 1 https://github.com/EasyTier/EasyTier.git refs/tags/v2.6.4
git -C "$source_dir" checkout --detach FETCH_HEAD
test "$(git -C "$source_dir" rev-parse HEAD)" = 8428a89d2dabc94c97d370ec607c6ca142473626

cd "$source_dir"
rustup target add "$target"
cargo ndk -t "$abi" --platform 29 build --locked --release -p easytier-ffi
native_path="$source_dir/target/$target/release"
cargo ndk -t "$abi" --platform 29 rustc --locked --release -p easytier-android-jni -- \
    -L "native=$native_path" -l dylib=easytier_ffi -C link-arg=-Wl,-z,defs

mkdir -p "$output/$abi"
install -m 644 "$native_path"/libeasytier_{ffi,android_jni}.so "$output/$abi/"
