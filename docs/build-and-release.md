# Build and release

## Builds and CI

Use Gradle 9.5.0 through `./gradlew`, JDK 21, Android SDK platform 37
(`platforms;android-37.0`),
build-tools 36.0.0, NDK 30.0.16248370, and CMake 4.1.2. The Android Gradle
Plugin selects build-tools 36.0.0 automatically. In this restored workspace,
run `source .local-tools/env.sh` before Gradle. CI explicitly installs these
same components, validates the wrapper, and uses the Gradle Actions basic cache.

`Android CI` runs for pull requests and pushes to `main`. It runs static
analysis, core and both flavor unit tests, both Android lint variants, and both
debug assemblies. A `main` push uploads `localscribe-github-debug-apk`. No
production signing secret is needed for debug CI.

`Optional Android device tests` is manual. It provisions an API 35 software
emulator for Room, Compose, and audio instrumentation checks. The hosted AVD
never receives a model, so native inference correctly skips. For a native smoke
test, use a separately prepared self-hosted device/emulator with an app-readable
verified tiny model. No workflow downloads a Whisper model, particularly not a
multi-gigabyte one. Do not use `connected...AndroidTest` for this smoke check:
it may reinstall the app and erase the private fixture.

The optional workflow uses the default `AndroidJUnitRunner`, so it initializes
the integrated application graph used by Room and Compose tests. The custom
`EngineTestRunner` remains limited to the isolated native smoke invocation
below.

```bash
MODEL=/absolute/path/to/verified-ggml-tiny.bin
MODEL_SHA256=<verified-sha256>
SERIAL=<adb-serial>

test "$(sha256sum "$MODEL" | awk '{print $1}')" = "$MODEL_SHA256"
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest
adb -s "$SERIAL" install -r app/build/outputs/apk/github/debug/app-github-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
adb -s "$SERIAL" shell run-as io.github.surioustype.localscribe.debug mkdir -p files/models
cat "$MODEL" | adb -s "$SERIAL" shell run-as io.github.surioustype.localscribe.debug sh -c 'cat > files/models/ggml-tiny.bin'
adb -s "$SERIAL" shell am instrument -w -r \
  -e class io.github.surioustype.localscribe.audio.AudioNativeInstrumentationTest#nativeWhisperSmokeWithExternalVerifiedTinyModel \
  -e whisperModelPath /data/user/0/io.github.surioustype.localscribe.debug/files/models/ggml-tiny.bin \
  -e whisperModelSha256 "$MODEL_SHA256" \
  io.github.surioustype.localscribe.debug.test/io.github.surioustype.localscribe.audio.EngineTestRunner
```

This streams the verified model into the debuggable GitHub app's private
storage after the APK is installed. The target app can read that absolute path;
the direct `am instrument` call preserves it for the smoke test.

## Version policy

Releases use strict `vX.Y.Z` tags. The tag must equal `versionName` without the
`v`; `versionCode` must be:

```text
major * 1,000,000 + minor * 1,000 + patch
```

Tag components must use canonical decimal notation (`0` or a non-zero digit
followed by digits). Minor and patch are limited to `0..999`; `versionCode`
must be a positive canonical decimal value no greater than Android's supported
`2,100,000,000` limit. This makes the mapping injective and monotonic within
the supported release range. Validate before tagging:

```bash
scripts/version/validate-release.sh vX.Y.Z
scripts/version/validate-release.sh --version-name 1.2.3 --version-code 1002003 v1.2.3
```

## Production setup

Do not create the first signing key, protected environment, or release without
the repository owner's explicit approval. Once approved, create the `production`
GitHub Environment, add required reviewers, and store only
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD` there. Base64-encode the keystore locally; never
commit it, add it to an issue, or print it in a workflow log.

The tag workflow validates and builds with read-only repository permission,
then uploads an unsigned APK artifact. Its dependent `sign-and-publish` job
alone has `contents: write` and waits on the protected environment. That job
writes the key only in `RUNNER_TEMP`, signs with build-tools 36.0.0 `apksigner`,
verifies the signature, writes SHA-256, and removes the temporary key on exit.
GitHub generates the release notes, avoiding shell evaluation of untrusted note
text. Release assets are exactly
`localscribe-vX.Y.Z.apk` and `localscribe-vX.Y.Z.apk.sha256`.

The current `versionCode = 1` is development configuration. Before the first
production `v0.1.0` tag, set it to `1000`; until then the workflow stops at
validation.
