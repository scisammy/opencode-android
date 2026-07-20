#!/bin/bash
set -e
BASE="$(cd "$(dirname "$0")/.." && pwd)"  # project root
ASSETS="${1:-$BASE/app/src/main/assets}"
mkdir -p "$ASSETS"

# ABI mapping: arch → assets suffix → jniLibs dir
declare -A ABI_MAP
ABI_MAP[aarch64]="arm64:arm64-v8a"
ABI_MAP[armv7a]="arm:armeabi-v7a"
ABI_MAP[x86_64]="x86_64:x86_64"
ABI_MAP[i686]="x86:x86"

download_and_extract() {
    local arch="$1"
    local mapping="${ABI_MAP[$arch]}"
    local abi_suffix="${mapping%%:*}"        # arm64, arm, x86_64, x86
    local jni_abi="${mapping##*:}"           # arm64-v8a, armeabi-v7a, x86_64, x86
    local url="https://github.com/green-green-avk/build-proot-android/raw/master/packages/proot-android-${arch}.tar.gz"

    local JNIDIR="$BASE/app/src/main/jniLibs/$jni_abi"
    mkdir -p "$JNIDIR" "$ASSETS"

    echo "Downloading proot (${arch})..."
    curl -sL "$url" | tar xz -C /tmp/proot-tmp

    # → assets (proot_<suffix>, loader_<suffix>)
    cp "/tmp/proot-tmp/root/bin/proot" "$ASSETS/proot_${abi_suffix}"
    cp "/tmp/proot-tmp/root/libexec/proot/loader" "$ASSETS/loader_${abi_suffix}"

    # → jniLibs/<abi>/ (lib<name>.so)
    cp "/tmp/proot-tmp/root/bin/proot" "$JNIDIR/libproot.so"
    cp "/tmp/proot-tmp/root/libexec/proot/loader" "$JNIDIR/libproot-loader.so"

    chmod 755 "$ASSETS/proot_${abi_suffix}" "$ASSETS/loader_${abi_suffix}" \
             "$JNIDIR/libproot.so" "$JNIDIR/libproot-loader.so"

    rm -rf /tmp/proot-tmp/root
    echo "  -> assets: proot_${abi_suffix}, loader_${abi_suffix}"
    echo "  -> jniLibs/$jni_abi: libproot.so, libproot-loader.so"
}

rm -rf /tmp/proot-tmp && mkdir /tmp/proot-tmp
download_and_extract aarch64
download_and_extract armv7a
download_and_extract x86_64
download_and_extract i686
echo ""
echo "=== Assets ==="
ls -la "$ASSETS"/{proot_*,loader_*}
echo ""
echo "=== jniLibs ==="
find "$BASE/app/src/main/jniLibs" -type f -ls
echo "Done"
