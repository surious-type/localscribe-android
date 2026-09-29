# H final fixes

## Scope

This wave closes the six findings in `H-final-review.md` without altering the
accepted transcription, native, update, or distribution algorithms.

1. Selecting a MediaStore source now writes that exact source record to the
   durable source store before job creation. A cold repository lookup therefore
   resolves the row without requiring a UI refresh.
2. Missing/revoked sources and missing pinned transcription or VAD models pause
   a job with the dependency failure; completed chunks remain completed. The
   paused UI can explicitly reauthorize or relink the stored source ID. Relink
   asks for the same recording and rejects display-name or duration mismatch;
   it is not a byte-identity proof.
3. Once model publication moves the artifact, registers metadata, and records
   `COMPLETED`, later cancellation/exception delivery retains that committed
   file/row/state rather than deleting the file.
4. Terminal job stop commands are harmless in both coordinator and Room, and
   the UI only shows Stop for pending, running, and paused jobs.
5. An inbound shared URI is consumed through saved state across recreation, and
   `onNewIntent` supplies later inbound URIs to the composition.
6. Setup exposes Off and the installed Silero VAD choice. The selected
   descriptor ID and exact installed hash are persisted in `VadConfig`.

## Regression coverage

- `AndroidAudioRepositoryPersistenceTest` is an Android/Room regression. It
  inserts app-owned WAV media into MediaStore, refreshes and selects its live
  row, creates a foreign-key transcription job, recreates the repository, and
  resolves the source before refresh.
- `TranscriptionCoordinatorTest` covers dependency pause retaining a completed
  checkpoint and harmless late stop for a completed job.
- `ModelDownloadManagerTest` gates metadata registration, cancels during
  durable publication, and asserts installed row, file, and visible COMPLETED
  state agree.
- `LocalScribeUiStateViewModelTest` covers consumed inbound audio across saved
  state recreation and a subsequent different inbound URI, and restores the
  selected VAD descriptor for setup.
- `RoomTranscriptionRepositoryTest` adds terminal Room cancel idempotence; it
  is compiled with the Android target and awaits the same device execution.

## Verification checkpoint

Executed successfully on 2026-09-27:

```text
source .local-tools/env.sh && ./gradlew :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.models.ModelDownloadManagerTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest \
  --tests io.github.surioustype.localscribe.ui.LocalScribeUiStateViewModelTest \
  --tests io.github.surioustype.localscribe.ui.TranscriptionStartControllerTest

BUILD SUCCESSFUL (30 actionable tasks; 28 tests: 11 model download, 10
coordinator, 5 start controller, 2 saved-state; 0 skipped/failures/errors)

source .local-tools/env.sh && ./gradlew :app:compileGithubDebugAndroidTestKotlin

BUILD SUCCESSFUL (33 actionable tasks)
```

The Android test compile is successful. The MediaStore and terminal-Room
instrumentation tests await the final single persistent emulator execution.
No Android test was executed in this wave. JVM XML results are retained under
`app/build/test-results/testGithubDebugUnitTest/`; Gradle's HTML report is at
`app/build/reports/tests/testGithubDebugUnitTest/index.html`.
