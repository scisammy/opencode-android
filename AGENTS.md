# AGENTS.md

## Build

```bash
bash scripts/setup-assets.sh   # download + patch proot binaries (required first time)
./gradlew assembleDebug        # builds to app/build/outputs/apk/debug/app-debug.apk
```

`setup-assets.sh` fetches UserLAnd proot/loader/talloc binaries for all 4 ABIs, patches ELF DT_NEEDED/DT_SONAME (`libtalloc.so.2` → `libtalloc.so`) via inline Python, and places them in `app/src/main/assets/` and `app/src/main/jniLibs/<abi>/`. Requires `curl` and `python3` (or `python`).

## Architecture

- **Single module**: `app/` only. Package: `com.ubuntuterminal`.
- **Single Activity**: `MainActivity.kt` handles setup, rootfs download, and terminal UI (no Jetpack Compose yet, despite README).
- **proot flow**: App downloads Alpine rootfs (not Ubuntu — despite the name), extracts to `filesDir/rootfs/`, then runs commands via proot with `/system/bin/linker64` (ptrace-based chroot, no root required).
- **Native libs**: `jniLibs/<abi>/libproot.so` etc. are executed directly by the OS loader. Assets fallback copies them out at runtime if missing.
- **No tests, no lint, no formatting** configured in the project.

## CI

`.github/workflows/build.yml`: JDK 17, runs `setup-assets.sh` then `./gradlew assembleDebug`. Publishes APK as artifact and to `continuous` release tag on push to main/master.

## Gotchas

- `minSdk = 24` (Android 7.0+). Proot binary variant `.a10` is for Android 10+, but assets are always `.a10` — the patching happens at build time.
- The proot command uses `/system/bin/linker64` as the interpreter (hardcoded for arm64/x86_64). This breaks on 32-bit devices if you add 32-bit proot paths.
- `extractNativeLibs = true` in manifest — required for proot binaries to be directly executable from the native library dir.
- `jniLibs` `.so` files must stay executable (755). The `setup-assets.sh` script sets this; manual copies may not.
