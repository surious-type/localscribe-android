# G delivery report — 2026-09-19

## Fix rounds 1–2

The release checksum now follows the already reviewed app/core updater contract:
`localscribe-vX.Y.Z.apk.sha256`. This is the intentional delivery-contract
resolution of the product brief's shorter `.sha256` example; no core or D files
were changed. Release validation/build is read-only and uploads an unsigned APK
artifact. Only the dependent protected `production` job receives signing secrets
and `contents: write`; it checks out no repository code, downloads that artifact,
signs/verifies it, and creates the GitHub release.

CI now uses the explicit AGP unit-test task names
`testGithubDebugUnitTest` and `testPlayDebugUnitTest`. All workflows install the
actual SDK package `platforms;android-37.0`, as confirmed by the restored SDK
inventory, plus build-tools 36.0.0, NDK 30.0.16248370, and CMake 4.1.2. The
manual device workflow validates the wrapper and has no unusable hosted-model
input; its hosted AVD runs non-model instrumentation tests while native inference
is reserved for a prepared self-hosted device/emulator.

The version validator accepts canonical decimal tag components only, validates a
positive canonical bounded `versionCode` before Bash arithmetic, and rejects
leading-zero aliases, zero, and oversized values before they can overflow. Its
regression script covers those cases and now runs in both CI and release
validation. Development remains `versionName=0.1.0`, `versionCode=1`; before
the first production `v0.1.0`, set `versionCode=1000` without changing it here.

Native-smoke documentation now installs the debug app/test APKs, streams a
host-verified model through `run-as` into the debug app's private storage, and
uses direct `am instrument` with the app-private absolute path. This avoids the
reinstall/uninstall behaviour of `connected...AndroidTest` erasing the fixture.

## Static checks

* Snapshot created at `.review/G-fix1-base` before edits.
* Snapshot created at `.review/G-fix2-base` before round-2 edits.
* Read `G-review.md`, actual Gradle configuration, and local
  `platforms/android-37.0/package.xml` inventory.
* Official immutable action tags include checkout v6
  `d23441a48e516b6c34aea4fa41551a30e30af803`, setup-java v5
  `b6effb05e454b25005698d916606bdc6ffcbf961`, Gradle Actions v6
  `4733eaac7c1b0da527e4206b7671e0061de1ce37`, upload-artifact v4.6.2
  `ea165f8d65b6e75b540449e92b4886f43607fa02`, and download-artifact v4.3.0
  `d3f86a106a0bac45b974a628896c90dbdf5c8093`.
* Ran `bash -n scripts/version/validate-release.sh
  scripts/version/test-validate-release.sh` and
  `scripts/version/test-validate-release.sh`; canonical leading-zero, zero, and
  oversized-code rejection probes also exited non-zero as required.
* Parsed all three workflows with the local Node `yaml` parser, confirmed both
  CI and release workflows invoke the version regression script, confirmed the
  obsolete shell-domain model path is absent from the docs, and ran
  `git diff --check`.

## Limitations

No Gradle, GitHub workflow, emulator/physical-device test, signing, or release
ran in this fix round. The reserved release/build execution slot remains with
root/D work. Root integration must execute the workflow-equivalent Gradle
checks and publish CI before treating them as validated.
