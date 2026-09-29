# Local environment restoration — 2026-09-19

The earlier `/tmp/localscribe-tools` installation was lost at reboot. Build tools are now restored under the ignored repository directory `.local-tools`, so they survive reboot. Application sources and build configuration were not recreated or changed during restoration.

Activate from the repository root:

```bash
source .local-tools/env.sh
```

The activation script quotes the full path (including Cyrillic directory names), selects the installed JDK, and sets Android state, SDK paths and Gradle user home inside `.local-tools`.

| Tool | Restored version |
|---|---|
| Temurin JDK | 21.0.12.1+1 |
| Gradle | 9.5.0 |
| Android command tools | 19.0 |
| Android SDK platform | android-37.0, revision 2 |
| Android build tools | 37.0.0 |
| Android platform tools | 37.0.1 |
| Android NDK | 30.0.16248370 |
| CMake | 4.1.2 |

JDK and Gradle SHA-256 hashes matched official metadata; command-tools SHA-1 matched Google's repository metadata. SDK packages were installed through the official SDK manager with the same accepted licenses. Two duplicate directories from overlapping package installers were removed after both installers finished; canonical platform and build-tools directories were retained.

Verification succeeded: `javac -version`, `gradle --version`, `sdkmanager --list_installed`, `cmake --version`, NDK `clang --version`, and `aapt2 version`; SDK platform `android.jar` exists. This confirms tool availability, not an application build or test result. Existing tests and the long native test were not rerun as part of environment restoration. Build ownership was handed to the implementation agent.

Installed package inventory and download/install logs are under `.local-tools`. Emulator restoration remains in progress as described below.

## Emulator handoff

The official SDK-manager download for `emulator` and `system-images;android-35;aosp_atd;x86_64` was interrupted when its agent finished; the lead confirmed no SDK installer process remained and resumed it in exec session **76046**. The installation log is `.local-tools/emulator-install.log`; partial downloads are in `.local-tools/android-sdk/.temp`. These paths are durable. No emulator or AVD has been started in this restoration, and `/dev/kvm` is absent.

Wait for the existing installation process to finish before retrying it. If it terminates unsuccessfully, reactivate `env.sh` and rerun:

```bash
sdkmanager --sdk_root="$ANDROID_HOME" 'emulator' 'system-images;android-35;aosp_atd;x86_64'
```

After installation, `.local-tools/prepare-emulator.sh` creates the AVD if missing and starts a headless software-emulated API35 x86_64 device. Its state remains in `.local-tools/avd` and `.local-tools/android-user`. Socket/network permissions are required for emulator and ADB. Check `adb devices` and `adb -e shell getprop sys.boot_completed` before device tests. No instrumentation/native test replay was performed.

The implementation agent's Gradle invocation additionally requested and installed Android build tools 36.0.0 automatically; both 36.0.0 and 37.0.0 can coexist. The restoration agent did not duplicate that installer.

## Subsequent lead verification

The resumed installer (session76046) exited successfully. The lead started `.local-tools/prepare-emulator.sh` in session24836; boot readiness is not yet verified. Logs are in `.local-tools/emulator.log`.

The lead subsequently verified `adb -e shell getprop sys.boot_completed` returned `1`; emulator-5554 is ready.
