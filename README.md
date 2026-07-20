# Ubuntu Terminal for Android

An Android app that provides a full Ubuntu terminal experience using **proot** - no root required.

## How it works

1. The app downloads an Ubuntu root filesystem on first launch
2. Uses [proot](https://github.com/proot-me/proot) to create a chroot-like environment without root privileges
3. Provides a terminal emulator (based on VT100/xterm) with full keyboard support
4. All processing happens on-device - no servers or internet required after setup

## Features

- Full Ubuntu 24.04 LTS userland
- `apt-get` package manager support
- Compile and run C, Python, Go, Rust, and more
- Runs bash, vim, nano, htop, and other terminal apps
- No root required
- No external dependencies

## Quick Start

### Prerequisites

- Android 7.0+ (API 24+)
- 500MB+ free storage for Ubuntu rootfs

### Building from Source

```bash
# Clone the repository
git clone https://github.com/YOUR_USERNAME/ubuntu-terminal-android.git
cd ubuntu-terminal-android

# Build with Gradle
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### Installing

1. Build the APK (see above) or download from [Releases](../../releases)
2. Enable "Install from Unknown Sources" in Android settings
3. Install the APK
4. Open "Ubuntu Terminal" app
5. Click "Start Setup" to download the Ubuntu rootfs
6. Once setup completes, you'll get an Ubuntu terminal

## Project Structure

```
ubuntu-terminal-android/
├── app/
│   ├── build.gradle.kts              # App build configuration
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/
│       │   ├── com/ubuntuterminal/    # App code
│       │   │   ├── MainActivity.kt    # Entry point
│       │   │   ├── SetupActivity.kt   # First-run setup
│       │   │   ├── TerminalActivity.kt# Terminal UI
│       │   │   ├── TerminalService.kt # Foreground service
│       │   │   └── TermuxJNI.java     # JNI bridge for PTY
│       │   ├── com/termux/terminal/   # Terminal emulator engine
│       │   └── com/termux/view/       # Terminal rendering
│       └── jni/
│           └── termux-jni.c          # PTY native code (CMake)
├── scripts/
│   ├── build-proot.sh                # Download proot binaries
│   └── download-ubuntu-rootfs.sh     # Download Ubuntu rootfs
├── .github/workflows/build.yml       # GitHub Actions CI/CD
├── build.gradle.kts                  # Root build file
└── settings.gradle.kts
```

## Technology

| Component | Technology |
|-----------|------------|
| UI | Jetpack Compose (planned) / Android Views |
| Terminal Emulator | Custom VT100/xterm emulator |
| Linux Environment | proot (ptrace-based chroot) |
| PTY | JNI + Linux pseudo-terminals |
| Build System | Gradle + CMake + NDK |
| CI/CD | GitHub Actions |

## License

MIT License - see [LICENSE](LICENSE) for details.

## Credits

- [proot](https://github.com/proot-me/proot) - ptrace-based chroot
- [Termux](https://termux.com/) - terminal emulator and environment for Android
- [proot-distro](https://github.com/termux/proot-distro) - Linux distro management
