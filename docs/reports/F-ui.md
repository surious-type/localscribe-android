# Task F2 — application and Compose integration

## Staged implementation

- Added an application-owned explicit graph with one `Mutex` injected into the transcription
  coordinator, benchmark manager, and installed-model file store. Deletion rejects a descriptor
  referenced by any pending, running, or paused primary or VAD job.
- Startup runs Room interrupted-work recovery before composing the UI or exposing actions.
- Added Compose onboarding, recordings/SAF import/share intake, source validation/start, job
  controls, model catalog/download/delete/benchmark/quality controls, transcript search/play/
  pause/scrub/seek/export/share, and settings/update controls. The benchmark caller cancels when
  its composable leaves composition.
- Added staged Compose smoke tests for onboarding/navigation and a small mutex-identity guard.

## Fix-round checklist

- F2-1: added the missing `withLock` import.
- F2-2: staged a single graph recovery gate shared by the activity and service; service starts its
  foreground notification first, then awaits recovery before queueing work. The staged service
  contract change has not been copied to compiled source.
- F2-3: transcript derives one assembled segment list from chunks plus raw segments for display,
  filtering, SAF export and sharing.
- F2-6: foreground-service API launch and a startup cache-respecting update check are staged.
- F2-7/F2-8: UI now reads `BuildConfig.VERSION_NAME`, shows release notes/store-managed state,
  applies persisted dark/dynamic color preferences, and enables edge-to-edge.
- F2-4: transcript now consumes the independently staged lifecycle-safe playback controller;
  player preparation, seek-before-play, polling, stop and close are covered by its scoped tests.
- F2-5: benchmark and quality actions share one active job, cancel on `ON_STOP`, and reset in
  `finally` after every terminal path.
- F2-6: notification permission is requested before a new job is persisted or foreground service
  launch is attempted; denial leaves no stranded pending job and explains the required action.
- F2-9: job cards expose durable chunk progress and actionable failures; VAD has real download,
  retry, cancellation and guarded deletion controls; model storage and benchmark evidence are
  rendered. Transcript copy and individual export actions avoid a narrow non-wrapping row.

- F2-10: `LocalScribeUiStateViewModel` owns SavedStateHandle-backed destination, selected source,
  transcript selection and search state; the Compose root/home/transcript bind to that state.
  Export writing remains on `Dispatchers.IO`.

## Completion wiring

- `LocalScribeApp` now consumes the production presentation, transcription-start and benchmark
  lifecycle controllers. The benchmark owner is cancelled on lifecycle stop and clears its busy
  state after every terminal path; the transactional start controller removes a newly created job
  when foreground-service launch fails.
- `TranscriptionService` invokes `ServiceStartRecoveryGate` before its actual serial queue request,
  so a service-only cold start shares the graph's recovery gate with activity startup.
- Import and start actions use the same responsive action component exercised by the Compose tests.
  The tests cover a 320dp/1.8x-font phone and a 900dp tablet width.
- `AppGraph` passes `modelMutex = executionMutex` to the renamed installed-model store argument;
  the same graph-owned mutex still goes to the coordinator and benchmark manager.

## Validation

The integrated implementation passed the final clean JVM matrix: 39 core, 110 GitHub, and 95 Play
tests with no failures, errors, or skips. `staticAnalysis`, both debug lint tasks, both debug APKs,
both unsigned release APKs, and the GitHub Android-test APK also passed. API 35 emulator checks
covered the notification-granted launch and the two-process permission-revocation recovery path.
Responsive Compose tests cover a 320dp phone at 1.8x font scale and a 900dp tablet width.

## Known limitations

- Update UI reflects the flavor manager state; installer handoff is delegated to D's verified
  manager and needs flavor/device verification.
