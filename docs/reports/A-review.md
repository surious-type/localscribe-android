# Task A review — bootstrap and frozen contracts

## Verdict

**Approved for the bootstrap boundary; no blocking findings.** The reviewed bootstrap satisfies
the frozen build, flavor, privacy, and contract requirements. This is intentionally not a final
review of Task B algorithms or the later Android implementations.

## Contract and build assessment

- `:app` uses application ID and namespace `io.github.surioustype.localscribe`, with `minSdk =
  26`, and depends on the pure Kotlin `:core` module.
- The distribution dimension produces `github` and `play` variants. `REQUEST_INSTALL_PACKAGES`
  is declared only in `app/src/github/AndroidManifest.xml`.
- The core models and ports cover source metadata, job/chunk lifecycle and recovery, raw segments,
  inference/VAD configuration, verified model descriptors and installed artifacts, benchmark/demo
  records, typed failures, and coroutine/Flow repository boundaries. `AppUpdateManager` refers to
  update types only, while the model ports refer to model types only, preserving the required
  independence.
- The documented deterministic API surface agrees with the intended Task B ownership. `AppVersion`
  and `ReleaseParser` are already present as Task B work in the shared tree; they must remain out
  of any Task A commit/review scope so parallel work cannot overwrite that implementation.

## Privacy and permission assessment

- The main manifest has no microphone permission. Its audio permissions are scoped to
  `READ_MEDIA_AUDIO` and `READ_EXTERNAL_STORAGE` through API 32; notifications and foreground
  service permissions are declared for the planned long-running local processing service.
- `android:allowBackup="false"` and `android:fullBackupContent="false"` prevent backup, while
  `data_extraction_rules.xml` excludes every relevant data domain from cloud backup and device
  transfer. This protects private audio/transcripts once Room and app-private files are added.
- The FileProvider is non-exported, requires URI grants, and exposes only `cache/exports/` and
  `cache/updates/` ([file_paths.xml](../../app/src/main/res/xml/file_paths.xml)).
- The bootstrap contains no credentials, telemetry, analytics, upload adapters, or embedded model
  assets. `INTERNET` is necessary for the future public model/update download adapters; enforcement
  that those adapters do not upload user content remains a Task D/final-integration concern.

## Deferred-reference scope

`MissingClass` is suppressed only on the three intentional future declarations:
`LocalScribeApplication`, `MainActivity`, and `service.TranscriptionService` in
[AndroidManifest.xml](../../app/src/main/AndroidManifest.xml). Each suppression is attached to the
declaration that currently lacks a class; it does not constitute a project-wide lint disable.
The service is non-exported and declares both `mediaProcessing` and `specialUse`, including the
required special-use explanation. C must implement the API-specific start/type handling and time
limit behavior; F must supply the application/activity classes.

## Verification considered

I relied on Task A's recorded successful focused configuration, core-compilation, manifest-flavor,
ktlint, and Android Lint checks. I did not rerun them, per review scope. The reported aggregate
`staticAnalysis` failure is attributable to Task B's concurrent in-progress files, not to this
bootstrap diff.
