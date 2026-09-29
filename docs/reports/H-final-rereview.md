# H final fixes rereview

## Result

The six-finding fix wave is not fully closed. Four findings are closed in the
reviewed source, while the dependency-remediation path still has terminal
branches and the model-publication fix introduces an inconsistent partial
commit when publication itself fails.

## Residual findings

### High — dependency failures raised by audio/model validation still make the job terminal

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:142-180,212-228,251-269,368-385` and `app/src/main/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngine.kt:40-50,166-188`.
- The new explicit lookup branches pause when the source row/model row is
  already absent, but recoverable failures raised after those checks still go
  through the generic catch and `failJob()`. In particular, revoking source
  access after `getSource()` but before `readWindow()` produces
  `SOURCE_PERMISSION_REQUIRED`/`SOURCE_MISSING`, and checksum/file corruption
  discovered by `engine.loadModel()` produces `MODEL_CORRUPTED` or
  `MODEL_CHECKSUM_MISMATCH`. All of those become `FAILED`, which has no resume
  transition, so completed checkpoints are again stranded.
- The added coordinator regression covers only `getSource() == null`; it does
  not cover an access exception from `readWindow()` or corruption reported by
  `loadModel()`.
- Action: after mapping an execution exception to `DomainFailure`, route
  `SOURCE_PERMISSION_REQUIRED`, `SOURCE_MISSING`, `MODEL_CORRUPTED`, and
  `MODEL_CHECKSUM_MISMATCH` through the durable pause path, returning any
  claimed chunk to `PAUSED`. Add regressions for a completed checkpoint plus a
  claimed chunk when the audio read loses permission, and for a corrupt pinned
  model rejected during load; restoring the dependency must resume only the
  unfinished chunk.

### Medium — a publication error after the atomic move is silently treated as committed

- Path: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:250-312`.
- `published` is set immediately after `Files.move()`, before
  `installedModels.register()` and the `COMPLETED` update succeed. Any Room or
  state-publication exception after the move reaches `catch (Exception)`, sees
  `published == true`, and returns without cleanup or a terminal state. A
  registration failure therefore leaves a final file with no installed row
  while the download remains `VERIFYING`; a failure updating `COMPLETED` leaves
  the file and row registered while the visible download is still
  `VERIFYING`.
- The cancellation regression proves the intended post-commit cancellation
  behavior, but its fake registration always succeeds after the gate and does
  not exercise either partial-commit state.
- Action: distinguish file-moved, metadata-registered, and terminally-published
  stages. Only suppress a later cancellation after metadata and `COMPLETED`
  have both succeeded. On a publication-stage error, either roll back every
  completed stage or durably reconcile forward to one consistent terminal
  state. Add throwing-register and throwing-terminal-update regressions that
  assert the file, installed row, and visible download state agree.

## Verified closures

- **MediaStore durability:** `AndroidAudioRepository.getSource()` persists the
  exact selected in-memory MediaStore record before returning it to job
  creation. The Android regression creates the foreign-key job and resolves the
  source from a fresh repository without refresh.
- **Terminal Stop:** the UI limits Stop to `PENDING`, `RUNNING`, and `PAUSED`;
  both coordinator and Room cancellation return harmlessly for every terminal
  job state.
- **Shared-intent recreation:** inbound URIs are keyed through
  `SavedStateHandle`, the import effect is keyed by the URI, and `onNewIntent`
  updates the composition for later shares. The same launch URI is not imported
  again on activity recreation.
- **VAD job configuration:** setup exposes Off and installed Silero choices;
  job creation resolves the selected installed descriptor and persists its
  exact hash in `VadConfig`, which the coordinator resolves by descriptor plus
  hash before loading.

The Room transition table correctly continues to disallow direct
`PENDING -> PAUSED`: production `execute()` transitions a startable job to
`RUNNING` before dependency checks. Tests that need a paused fixture must
therefore create it through `PENDING -> RUNNING -> PAUSED`; a direct fixture
pause is a test setup defect, not a production transition gap.

## Review limit

This was the requested source-only rereview of the six closures and their
direct regressions. No Gradle, adb, or Git command was run. Device results must
be reported from the separately executed emulator run; they do not close the
two source findings above.
