# H final whole-project review

## Findings

### High — MediaStore recordings cannot create a durable transcription job

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/audio/AndroidAudioRepository.kt:43-64,119-183`, `app/src/main/kotlin/io/github/surioustype/localscribe/data/Entities.kt:22-32`, and `app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:354-401`.
- `refresh()` puts MediaStore rows only in the process-local `mediaSources` flow. Selecting one reaches `SourceControls`, but `createJob()` inserts a `transcription_jobs.sourceId` that has never been inserted into `audio_sources`; Room's foreign key rejects the job before the service starts. If the foreign-key failure were bypassed, `getSource()` would still lose the row on process death until an asynchronous UI refresh happened, making checkpoint resume race with a false `source_missing` failure.
- Reproduction: grant media-audio permission, select any row whose ID starts with `media:`, install a model, and tap **Start transcription**. The job insert fails with a foreign-key constraint and the UI reports that the service could not start. A repository regression can use a MediaStore-shaped record present only in `mediaSources`, then assert both job creation and lookup from a new repository instance.
- Action: persist the exact selected MediaStore `AudioSourceRecord` before creating the job (or persist MediaStore query results with bounded cleanup), so the Room source row and job/chunk plan are durable together. Add coverage for starting from a MediaStore row and resolving that source after recreating the repository before any UI refresh.

### High — recoverable source/model loss is made terminal, so completed checkpoints cannot resume

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:142-179,313-315`, `core/src/main/kotlin/io/github/surioustype/localscribe/core/domain/JobTransitions.kt:13-21`, and `app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:507-554,570-579`.
- Missing/revoked source access and missing/corrupt model revisions call `failJob()`. `FAILED` has no outgoing transition, and the UI exposes Resume only for `PENDING`/`PAUSED`, even though its messages tell the user to re-import or re-download and then resume. A long job that has already committed chunks therefore cannot continue from those checkpoints after a recoverable dependency problem.
- Reproduction: complete the first chunk of a multi-chunk job, revoke its SAF permission (or remove/corrupt the exact model artifact), then continue execution. The job becomes `FAILED`; restoring the dependency does not expose or permit Resume.
- Action: classify dependency-remediation failures such as `SOURCE_PERMISSION_REQUIRED`, restorable `SOURCE_MISSING`, `MODEL_CORRUPTED`, and `MODEL_CHECKSUM_MISMATCH` as a durable pause, using the existing `pauseJob(..., reason)` path so the in-flight chunk returns to a resumable state. Keep genuinely terminal inference/configuration failures as `FAILED`. Add a coordinator/repository test that retains a completed chunk, pauses on dependency loss, restores the exact source/model identity, and resumes only unfinished chunks. The UI also needs an actual source reauthorization/relink action for the stored source rather than creating an unrelated source ID.

### Medium — cancellation during model publication can leave Room pointing at a deleted artifact

- Path: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:250-293`.
- The atomic move, Room registration, and `COMPLETED` update run in `NonCancellable`, but the surrounding `withContext(ioDispatcher)` can deliver a pending cancellation when that block returns. The cancellation handler then sees `published == true`, deletes `finalFile`, and publishes `CANCELLED`; the already-registered installed-model row is not removed. The Models UI can consequently show an installed model whose file was deleted.
- Reproduction: use a suspending fake `InstalledModelRepository.register`, cancel after the atomic move while registration is suspended, release registration, and let the dispatcher switch back occur. Assert the current implementation can finish with a registered row, missing file, and `CANCELLED` download state.
- Action: once durable publication starts, make its file move, metadata registration, and terminal `COMPLETED` result the authoritative outcome; do not route a cancellation delivered after that commit through the partial-download cleanup. Add the gated regression above and assert row, file, and visible terminal state agree.

### Medium — Stop is offered for terminal jobs and can crash on an invalid Room transition

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:499-563`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:304-310`, and `app/src/main/kotlin/io/github/surioustype/localscribe/data/RoomTranscriptionRepository.kt:134-149`.
- Every job row renders **Stop**, including `COMPLETED` and `FAILED`. Tapping it calls `cancelJob()`; only an already-`CANCELLED` job is treated idempotently, so completed/failed rows attempt forbidden transitions to `CANCELLED` and throw `PersistenceStateException` from a UI coroutine.
- Reproduction: open a completed job row and tap **Stop**. The repository rejects `COMPLETED -> CANCELLED`. The same applies to `FAILED`.
- Action: show Stop only for `PENDING`, `RUNNING`, or `PAUSED`, and make the coordinator/repository command harmless for all terminal states as a defensive boundary. Cover the terminal-row controls and direct duplicate/late stop calls.

### Medium — recreating an Activity re-imports the same shared audio and may copy it repeatedly

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/MainActivity.kt:18-40` and `app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:255-263`.
- `receivedUri` is derived from the unchanged launch intent and imported from `LaunchedEffect(Unit)` with no consumed-intent key. Activity recreation runs the effect again. For a temporary share grant, each run can copy the complete input into private storage under a new UUID and add another source row; rotations can therefore duplicate a file up to the 2 GiB import limit until storage is exhausted.
- Reproduction: launch through `ACTION_SEND` with a provider that grants read access but not a persistable grant, wait for import, then recreate the Activity. The source count and private imported-audio file count increase again.
- Action: consume each inbound intent once using a saved token/URI state (and handle subsequent intents explicitly), then add an Activity-recreation test that asserts one row and one private copy. This is separate from the existing holder-only state restoration test.

### Medium — the downloadable VAD model is never selectable or used by production jobs

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:370-381,910-963` and `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:169-179`.
- The Models screen advertises and downloads optional Silero VAD, and the engine/coordinator can load a hash-pinned VAD, but production job creation always constructs `InferenceConfig` without `vad`. There is no UI or preference that places the installed VAD descriptor/hash into the job. Users can spend bandwidth/storage on the model, yet no transcription can use it; the documented preprocessing path is therefore not integrated.
- Reproduction: install the VAD model, create a transcription from every available setup control, and inspect the persisted job: `vadModelId`/`vadModelHash` remain null.
- Action: add an explicit Off/installed-VAD choice to transcription setup, persist the selected descriptor and exact hash in `VadConfig`, and cover job creation plus coordinator loading. If VAD is intentionally deferred, remove the download affordance and describe it as unsupported rather than presenting a dead feature.

## Reviewed closures and limits

- The D integration residual is correctly closed in source: `publishCancellationIfOwner()` checks the exact lazy-job reservation while holding `jobsMutex`, removes that same reservation with its `CANCELLED` publication, and the deterministic replacement regression at `ModelDownloadConcurrencyTest.kt:314-363` proves a former owner cannot overwrite replacement `COMPLETED`. This is distinct from the later cancellation-during-publication finding above.
- Previously accepted C, E, F1, and G review fixes were not reopened without new evidence. Native ownership remains serialized through the JNI handle lifecycle mutex/registry and Kotlin engine mutex; logging is disabled at the whisper boundary. Model and update subsystems remain independent, Play has no installer permission, GitHub update verification checks exact release URLs, length/hash, package/version, and signer lineage, and backup/data-transfer rules exclude private app data.
- The reported H JVM result is 39 core, 101 GitHub, and 86 Play tests passing with zero skips. Those checks do not exercise the Android/Room/Activity triggers above. Device verification and the final lint/APK gates were still in progress when this source review was written and must be reported separately from source correctness.
- Two low testing obligations from `F-ui-review-fix3.md` remain: stop while player preparation is suspended, and real launcher/callback recreation/feedback coverage. The shared-import recreation finding above is an observed production defect within the latter untested area; the suspended-player item remains a coverage limitation only.
- The 71-minute native smoke was intentionally not repeated. Its retained result proves one verified Tiny load/inference/unload on the software emulator, not speech accuracy, physical-device performance, thermal behavior, or screen-off endurance.
- `versionName = 0.1.0` with development `versionCode = 1` intentionally cannot pass the `v0.1.0` release validator; the documented pre-release change to `1000`, production signing key/environment setup, and first release approval remain user-owned release steps rather than a hidden passing claim.
