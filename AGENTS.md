# AGENTS.md

## Build

```bash
./gradlew assembleDebug    # builds to app/build/outputs/apk/debug/app-debug.apk
```

No build-time asset setup needed — proot/busybox/scripts are downloaded at runtime from `CypherpunkArmory/UserLAnd-Assets-Ubuntu` GitHub releases.

## Architecture

- **Single module**: `app/` only. Package: `com.ubuntuterminal`.
- **Single Activity**: `MainActivity.kt` handles everything (UI, downloads, proot execution).
- **Runtime flow**: App downloads pre-built assets + Ubuntu rootfs from UserLAnd-Assets-Ubuntu releases at first launch, extracts them to `filesDir/support/` and `filesDir/rootfs/`, then runs commands via `support/execInProot.sh`.
- **No bundled native binaries** — everything is fetched at runtime.
- **No tests, no lint, no formatting** configured in the project.

## Key Dependencies

- **proot/busybox/scripts**: Downloaded from `CypherpunkArmory/UserLAnd-Assets-Ubuntu` (release `v0.0.12`)
- **Ubuntu rootfs**: Same release (`{arch}-rootfs.tar.gz`)
- **No Gradle dependencies** beyond `appcompat` and `material`

## CI

`.github/workflows/build.yml`: JDK 17, runs `./gradlew assembleDebug`. Publishes APK as artifact and to `continuous` release tag on push to main/master.

## Gotchas

- Assets are downloaded at runtime, not build time. First launch requires internet.
- `execInProot.sh` expects `ROOT_PATH`, `ROOTFS_PATH`, `LIB_PATH` env vars set by the caller.
- The rootfs tarball may contain hardlinks — Android's tar can't create them, but the rootfs still works.
