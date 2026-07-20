#!/bin/bash
set -e
BASE="$(cd "$(dirname "$0")/.." && pwd)"

# ABI mapping: zip suffix → assets suffix → jniLibs dir
declare -A ABI_MAP
ABI_MAP[arm64-v8a]="arm64:arm64-v8a"
ABI_MAP[armeabi-v7a]="arm:armeabi-v7a"
ABI_MAP[x86_64]="x86_64:x86_64"
ABI_MAP[x86]="x86:x86"

download_userland_assets() {
    local zip_suffix="$1"       # arm64-v8a, armeabi-v7a, x86_64, x86
    local mapping="${ABI_MAP[$zip_suffix]}"
    local abi_suffix="${mapping%%:*}"   # arm64, arm, x86_64, x86
    local jni_abi="${mapping##*:}"      # arm64-v8a, armeabi-v7a, x86_64, x86

    local ASSETS="$BASE/app/src/main/assets"
    local JNIDIR="$BASE/app/src/main/jniLibs/$jni_abi"
    mkdir -p "$ASSETS" "$JNIDIR"

    local url="https://github.com/CypherpunkArmory/UserLAnd-Assets-Support/releases/download/v1.5.1/${zip_suffix}-assets.zip"
    echo "Downloading UserLAnd assets ($zip_suffix)..."
    curl -sL "$url" -o /tmp/userland-assets.zip

    # Extract proot + loader (use .a10 variant for Android 10+)
    python3 -c "
import zipfile
z = zipfile.ZipFile('/tmp/userland-assets.zip')
z.extract('proot.a10', '/tmp/userland-extract')
z.extract('loader.a10', '/tmp/userland-extract')
" 2>/dev/null || python -c "
import zipfile
z = zipfile.ZipFile('/tmp/userland-assets.zip')
z.extract('proot.a10', '/tmp/userland-extract')
z.extract('loader.a10', '/tmp/userland-extract')
"

    # → assets (proot_<suffix>, loader_<suffix>)
    cp "/tmp/userland-extract/proot.a10" "$ASSETS/proot_${abi_suffix}"
    cp "/tmp/userland-extract/loader.a10" "$ASSETS/loader_${abi_suffix}"

    # → jniLibs/<abi>/ (lib<name>.so for direct exec)
    cp "/tmp/userland-extract/proot.a10" "$JNIDIR/libproot.so"
    cp "/tmp/userland-extract/loader.a10" "$JNIDIR/libproot-loader.so"

    chmod 755 "$ASSETS/proot_${abi_suffix}" "$ASSETS/loader_${abi_suffix}" \
             "$JNIDIR/libproot.so" "$JNIDIR/libproot-loader.so"

    rm -rf /tmp/userland-assets.zip /tmp/userland-extract
    echo "  -> assets: proot_${abi_suffix}, loader_${abi_suffix}"
    echo "  -> jniLibs/$jni_abi: libproot.so, libproot-loader.so"
}

rm -rf /tmp/userland-extract && mkdir -p /tmp/userland-extract

download_userland_assets arm64-v8a
download_userland_assets armeabi-v7a
download_userland_assets x86_64
download_userland_assets x86

echo ""
echo "=== Assets ==="
ls -la "$BASE/app/src/main/assets/"{proot_*,loader_*}
echo ""
echo "=== jniLibs ==="
find "$BASE/app/src/main/jniLibs" -type f -ls
echo "Done"
