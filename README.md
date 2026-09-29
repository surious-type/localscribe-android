# LocalScribe

LocalScribe is a local-first Android audio transcription project. It is being
built to import recordings from device storage, process speech on the device
with Whisper, and retain transcripts locally. It adds no analytics, telemetry,
cloud transcription, or user-audio upload path. Internet use is limited to an
explicit model download, update metadata, or an APK download.

## Current state

The repository contains the Android foundation, domain layer, persistence,
model/update adapters, and native/audio work in active integration. Delivery
workflows are configured but have not run on GitHub. Integrated builds, lint,
device tests, and release validation have not completed; no installable or
production-signed APK is claimed here.

## Build locally

The checked-in Gradle wrapper is 9.5.0. Use JDK 21 (the project emits Java 17
bytecode), SDK platform 37, build-tools 36.0.0, NDK 30.0.16248370, and CMake
4.1.2. In the restored workspace:

```bash
source .local-tools/env.sh
./gradlew :app:assembleGithubDebug
```

Targeted checks:

```bash
./gradlew :core:test :app:testGithubDebugUnitTest :app:testPlayDebugUnitTest
./gradlew staticAnalysis :app:lintGithubDebug :app:lintPlayDebug
```

The GitHub debug APK is at
`app/build/outputs/apk/github/debug/app-github-debug.apk`. Successful pushes to
`main` upload it as the `localscribe-github-debug-apk` workflow artifact.

## Models and privacy

Models are separate from application releases and download only after an
explicit user request. The catalog uses `ggerganov/whisper.cpp` on Hugging Face;
each model has an expected length and SHA-256, both verified before atomic
installation. Multilingual Small is the default recommendation. See
[the model-system document](docs/model-system.md) for sources, hashes, and the
catalog trust boundary.

## Releases

Pushing a `vX.Y.Z` tag starts the protected `production` release workflow. The
tag must match `versionName`, and `versionCode` follows
`major * 1,000,000 + minor * 1,000 + patch`. The current development code does
not yet meet that release rule: set it to `1000` before the first production
`v0.1.0` tag. Minor and patch are each limited to `0..999`.

Before the first production key or release, the repository owner must explicitly
approve that security-sensitive action. Then create a GitHub Environment named
`production`, require manual reviewers, and add environment secrets
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD`. The workflow creates and verifies
`localscribe-vX.Y.Z.apk` plus `localscribe-vX.Y.Z.apk.sha256`. See
[docs/build-and-release.md](docs/build-and-release.md) for setup details.
