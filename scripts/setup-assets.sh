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

    # Extract proot + loader + talloc (use .a10 variant for Android 10+)
    python3 -c "
import zipfile
z = zipfile.ZipFile('/tmp/userland-assets.zip')
z.extract('proot.a10', '/tmp/userland-extract')
z.extract('loader.a10', '/tmp/userland-extract')
z.extract('libtalloc.so.2.a10', '/tmp/userland-extract')
" 2>/dev/null || python -c "
import zipfile
z = zipfile.ZipFile('/tmp/userland-assets.zip')
z.extract('proot.a10', '/tmp/userland-extract')
z.extract('loader.a10', '/tmp/userland-extract')
z.extract('libtalloc.so.2.a10', '/tmp/userland-extract')
"

    # → assets (proot_<suffix>, loader_<suffix>, libtalloc_<suffix>)
    cp "/tmp/userland-extract/proot.a10" "$ASSETS/proot_${abi_suffix}"
    cp "/tmp/userland-extract/loader.a10" "$ASSETS/loader_${abi_suffix}"
    cp "/tmp/userland-extract/libtalloc.so.2.a10" "$ASSETS/libtalloc_${abi_suffix}"

    # → jniLibs/<abi>/ (lib<name>.so for direct exec)
    cp "/tmp/userland-extract/proot.a10" "$JNIDIR/libproot.so"
    cp "/tmp/userland-extract/loader.a10" "$JNIDIR/libproot-loader.so"
    cp "/tmp/userland-extract/libtalloc.so.2.a10" "$JNIDIR/libtalloc.so"

    chmod 755 "$ASSETS/proot_${abi_suffix}" "$ASSETS/loader_${abi_suffix}" \
              "$ASSETS/libtalloc_${abi_suffix}" \
              "$JNIDIR/libproot.so" "$JNIDIR/libproot-loader.so" \
              "$JNIDIR/libtalloc.so"

    # Patch proot DT_NEEDED & talloc DT_SONAME: libtalloc.so.2 → libtalloc.so
    cat > /tmp/patch_elf.py << 'PYPATCH'
import sys, os, struct
jni = sys.argv[1]

def read_u16(d, off): return struct.unpack('<H', d[off:off+2])[0]
def read_u32(d, off): return struct.unpack('<I', d[off:off+4])[0]
def read_u64(d, off): return struct.unpack('<Q', d[off:off+8])[0]
def is_64(d): return d[4] == 2

def find_dynstr(d):
    e_phoff = read_u64(d, 32) if is_64(d) else read_u32(d, 28)
    e_phentsize = read_u16(d, 54) if is_64(d) else read_u16(d, 42)
    e_phnum = read_u16(d, 56) if is_64(d) else read_u16(d, 44)
    e_shoff = read_u64(d, 40) if is_64(d) else read_u32(d, 32)
    e_shentsize = read_u16(d, 58) if is_64(d) else read_u16(d, 46)
    e_shnum = read_u16(d, 60) if is_64(d) else read_u16(d, 48)
    e_shstrndx = read_u16(d, 62) if is_64(d) else read_u16(d, 50)
    
    if e_shoff == 0 or e_shnum == 0 or e_shstrndx >= e_shnum:
        # No section headers - try to find via PHT
        for i in range(e_phnum):
            p_type = read_u32(d, e_phoff + i * e_phentsize)
            if p_type == 2:
                p_offset = read_u64(d, e_phoff + i * e_phentsize + 8) if is_64(d) else read_u32(d, e_phoff + i * e_phentsize + 4)
                p_filesz = read_u64(d, e_phoff + i * e_phentsize + 32) if is_64(d) else read_u32(d, e_phoff + i * e_phentsize + 16)
                # Found PT_DYNAMIC, scan for DT_STRTAB
                pos = p_offset
                while pos < p_offset + p_filesz:
                    d_tag = read_u64(d, pos) if is_64(d) else read_u32(d, pos)
                    d_val = read_u64(d, pos + 8) if is_64(d) else read_u32(d, pos + 4)
                    if d_tag == 5:  # DT_STRTAB
                        # d_val is vaddr, need file offset
                        for j in range(e_phnum):
                            pt = read_u32(d, e_phoff + j * e_phentsize)
                            pv = read_u64(d, e_phoff + j * e_phentsize + 16) if is_64(d) else read_u32(d, e_phoff + j * e_phentsize + 8)
                            po = read_u64(d, e_phoff + j * e_phentsize + 8) if is_64(d) else read_u32(d, e_phoff + j * e_phentsize + 4)
                            pms = read_u64(d, e_phoff + j * e_phentsize + 40) if is_64(d) else read_u32(d, e_phoff + j * e_phentsize + 20)
                            if pt == 1 and pv <= d_val < pv + pms:
                                return (int(d_val - pv + po), None)
                    if d_tag == 0:
                        break
                    pos += 16 if is_64(d) else 8
        return (None, None)
    
    # Get shstrtab
    shstr_off = read_u64(d, e_shoff + e_shstrndx * e_shentsize + 24) if is_64(d) else read_u32(d, e_shoff + e_shstrndx * e_shentsize + 16)
    
    for i in range(e_shnum):
        sh_name = read_u32(d, e_shoff + i * e_shentsize)
        s_end = d.find(b'\x00', shstr_off + sh_name)
        name = d[shstr_off + sh_name:s_end].decode('ascii', errors='replace')
        if name == '.dynstr':
            return (int(read_u64(d, e_shoff + i * e_shentsize + 24) if is_64(d) else read_u32(d, e_shoff + i * e_shentsize + 16)),
                    int(read_u64(d, e_shoff + i * e_shentsize + 32) if is_64(d) else read_u32(d, e_shoff + i * e_shentsize + 20)))
        if name == '.dynamic':
            dyn_off = int(read_u64(d, e_shoff + i * e_shentsize + 24) if is_64(d) else read_u32(d, e_shoff + i * e_shentsize + 16))
            dyn_size = int(read_u64(d, e_shoff + i * e_shentsize + 32) if is_64(d) else read_u32(d, e_shoff + i * e_shentsize + 20))
    return (None, None)

def find_dynamic_offset(d):
    e_phoff = read_u64(d, 32) if is_64(d) else read_u32(d, 28)
    e_phentsize = read_u16(d, 54) if is_64(d) else read_u16(d, 42)
    e_phnum = read_u16(d, 56) if is_64(d) else read_u16(d, 44)
    
    for i in range(e_phnum):
        p_type = read_u32(d, e_phoff + i * e_phentsize)
        if p_type == 2:
            p_offset = read_u64(d, e_phoff + i * e_phentsize + 8) if is_64(d) else read_u32(d, e_phoff + i * e_phentsize + 4)
            p_filesz = read_u64(d, e_phoff + i * e_phentsize + 32) if is_64(d) else read_u32(d, e_phoff + i * e_phentsize + 16)
            return (int(p_offset), int(p_filesz))
    return (None, None)

def patch_proot(path):
    with open(path, 'rb') as f:
        d = bytearray(f.read())
    
    str_off, str_sz = find_dynstr(d)
    dyn_off, dyn_sz = find_dynamic_offset(d)
    if str_off is None or dyn_off is None:
        print(f'  ERROR: could not find .dynstr or PT_DYNAMIC in {path}')
        return False
    
    stride = 16 if is_64(d) else 8
    patched = False
    pos = dyn_off
    while pos < dyn_off + dyn_sz:
        d_tag = read_u64(d, pos) if is_64(d) else read_u32(d, pos)
        d_val = read_u64(d, pos + 8) if is_64(d) else read_u32(d, pos + 4)
        if d_tag == 1:  # DT_NEEDED
            off = str_off + d_val
            end = d.find(b'\x00', off)
            name = d[off:end].decode('latin-1')
            if name == 'libtalloc.so.2':
                # 16 bytes for 64-bit, 16 for 32-bit (original + null)
                orig_len = end - off + 1
                new_name = b'libtalloc.so\x00' + b'\x00' * (orig_len - 13)
                assert len(new_name) == orig_len, f'len mismatch {len(new_name)} vs {orig_len}'
                d[off:off+orig_len] = new_name
                print(f'  patched DT_NEEDED libtalloc.so.2 -> libtalloc.so in {os.path.basename(path)}')
                patched = True
                break
        elif d_tag == 0:
            break
        pos += stride
    
    if not patched:
        print(f'  WARNING: no libtalloc.so.2 DT_NEEDED found in {os.path.basename(path)}')
        return False
    
    with open(path, 'wb') as f:
        f.write(d)
    return True

def patch_talloc_soname(path):
    with open(path, 'rb') as f:
        d = bytearray(f.read())
    
    str_off, str_sz = find_dynstr(d)
    dyn_off, dyn_sz = find_dynamic_offset(d)
    if str_off is None or dyn_off is None:
        print(f'  ERROR: could not find .dynstr or PT_DYNAMIC in {path}')
        return False
    
    stride = 16 if is_64(d) else 8
    patched = False
    pos = dyn_off
    while pos < dyn_off + dyn_sz:
        d_tag = read_u64(d, pos) if is_64(d) else read_u32(d, pos)
        d_val = read_u64(d, pos + 8) if is_64(d) else read_u32(d, pos + 4)
        if d_tag == 14:  # DT_SONAME
            off = str_off + d_val
            end = d.find(b'\x00', off)
            name = d[off:end].decode('latin-1')
            if name == 'libtalloc.so.2':
                orig_len = end - off + 1
                new_name = b'libtalloc.so\x00' + b'\x00' * (orig_len - 13)
                assert len(new_name) == orig_len, f'len mismatch {len(new_name)} vs {orig_len}'
                d[off:off+orig_len] = new_name
                print(f'  patched DT_SONAME libtalloc.so.2 -> libtalloc.so in {os.path.basename(path)}')
                patched = True
                break
        elif d_tag == 0:
            break
        pos += stride
    
    if not patched:
        print(f'  WARNING: no libtalloc.so.2 DT_SONAME found in {os.path.basename(path)}')
        return False
    
    with open(path, 'wb') as f:
        f.write(d)
    return True

patch_proot(jni + '/libproot.so')
patch_talloc_soname(jni + '/libtalloc.so')
PYPATCH

    python3 /tmp/patch_elf.py "$JNIDIR"

    # Fallback: symlink libtalloc.so.2 → libtalloc.so in case ELF patching failed
    ln -sf libtalloc.so "$JNIDIR/libtalloc.so.2"

    rm -rf /tmp/userland-assets.zip /tmp/userland-extract
    echo "  -> assets: proot_${abi_suffix}, loader_${abi_suffix}, libtalloc_${abi_suffix}"
    echo "  -> jniLibs/$jni_abi: libproot.so, libproot-loader.so, libtalloc.so"
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
