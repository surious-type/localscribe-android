# Task F2 staged source review

Date: 2026-09-21

## Verdict

**Changes required before integration.** The staged graph uses one `Mutex` instance for the
coordinator, benchmark manager, and model file store, and new jobs resolve the repository's latest
installed revision while resumed jobs retain their stored hash. However, the staged source has a
compile blocker, does not gate service execution on startup recovery, renders and exports raw
overlapping segments, and has broken player and benchmark lifecycle handling. Several required UI
flows are also still absent despite being listed as delivered in the staged report.

## Findings

### F2-1 — Blocker: `AppGraph` does not import the `withLock` extension it calls

`AppGraph.recoverBeforeUse` calls `recoveryMutex.withLock` at
`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/di/AppGraph.kt:94`, but the
file imports `Mutex` only (`:31`) and does not import `kotlinx.coroutines.sync.withLock`. This staged
source will not resolve that call.

Import `kotlinx.coroutines.sync.withLock`. Keep the build gap explicit until the integrated source
is compiled; this review did not run Gradle.

### F2-2 — High: cold-process service execution can bypass interrupted-state recovery

Recovery is invoked only from `MainActivity.onCreate` before `setContent`
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/MainActivity.kt:20-25`).
`LocalScribeApplication.transcriptionServiceDependencies` exposes the graph directly
(`LocalScribeApplication.kt:7-9`), and the real `TranscriptionService` starts accepting start/resume
requests as soon as it is created. A service entry into a cold process therefore has no dependency
on `recoverBeforeUse`; a persisted `RUNNING` job and `PROCESSING` chunk can reach the coordinator
before Room converts them to `PAUSED`/`PENDING`. The coordinator rejects a `RUNNING` job as not
startable, so the required process-death resume path can fail based on component launch order.

Make recovery a process-owned gate shared by the activity and service, and await it before the
service queues any start/resume request. Add a graph/service test that begins with interrupted Room
state, invokes the service path without constructing the activity, and proves recovery completes
before execution. The current mutex test checks field identity by reflection only; it does not cover
the required recovery or cancellation ordering.

### F2-3 — High: transcript display and every export use raw overlapping chunk segments

`TranscriptScreen` collects `observeSegments` and passes that list directly to filtering, rendering,
SAF export, and share (`LocalScribeApp.kt:205-216`, `:232`, `:258-265`). The real Room adapter exposes
stored raw segments; it does not call `TranscriptAssembler`
(`app/src/main/kotlin/io/github/surioustype/localscribe/data/RoomTranscriptionRepository.kt:35-36`).
Since the planner creates three-second overlaps, completed transcripts and TXT/MD/SRT/VTT/JSON
exports can repeat boundary speech. This bypasses the domain assembler built specifically to remove
those duplicates.

Observe the job's chunks, assemble `chunks + rawSegments` with `TranscriptAssembler`, and use that
single assembled list for search, display, copy, share, and all export formats. Add a UI/ViewModel
regression with duplicate text across two overlapping chunks and assert it appears once in the
display and exported document.

### F2-4 — High: recomposition releases the newly created `MediaPlayer`

The cleanup effect is keyed by `player` (`LocalScribeApp.kt:219`). Creating a player assigns it to
that state at `:240-244`, which changes the effect key. Disposal of the previous effect then runs
`player?.release()` (`:224`); because the lambda reads the current delegated state, it releases the
new player that triggered recomposition. The polling effect and later seek/play calls can then hit a
released player. Playback preparation is also synchronous on the composition's main coroutine
(`:238-245`), so a slow provider can block the UI. Clicking a transcript segment before pressing
Play only updates the displayed position because `player` is null (`:258-260`).

Move player ownership into a lifecycle-aware holder/ViewModel, prepare it off main, keep cleanup
keyed to the screen/source rather than the mutable player instance, and create/prepare the player
for segment seeking as well as Play. Surface sanitized preparation/access failures. Add a lifecycle
test covering create, play, recomposition, segment seek before initial play, `ON_STOP`, and disposal.

### F2-5 — High: a benchmark continues when the app backgrounds without foreground execution

The Models screen cancels `benchmarkJob` only when the composable leaves composition
(`LocalScribeApp.kt:283-285`). Sending the activity to the background does not dispose its
composition, so native inference continues without a foreground service, contrary to the F1
handoff and F2 requirement to use foreground execution or stop safely. In addition,
`benchmarkJob` is never reset to null after success, failure, or cancellation (`:320-327`), leaving
the batch benchmark button permanently disabled after its first run. Quality-demo launches can
also replace this reference without an explicit busy-state policy (`:302-309`).

Observe lifecycle stop and cancel/await the active benchmark, or move it to an appropriately
declared foreground execution path. Represent one explicit benchmark busy state, reject competing
quality/batch launches, and clear the job in `finally`. Add lifecycle and completion/failure tests;
do not run real inference in those tests.

### F2-6 — High: the foreground-notification permission and launch path are not implemented

The manifest declares `POST_NOTIFICATIONS`, but F2 never requests it. `MainActivity` only exposes an
unused media-permission check, and the UI starts/resumes transcription with `Context.startService`
(`LocalScribeApp.kt:177`, `:193`). On Android 13+, a user who has not granted notification
permission will not see the required drawer notification or its pause/resume/stop controls even
though the foreground service may run. Using the foreground-service launch API also makes the
required immediate promotion explicit on API 26+.

Request notification permission at the user-triggered point before the first transcription, handle
denial with a clear state, and launch through `ContextCompat.startForegroundService`. Add an API
33+ instrumentation path for allowed and denied permission states and assert job creation does not
silently strand a pending job when service launch fails.

### F2-7 — High: the update screen uses a hard-coded version and has no automatic-check trigger

Both display and version comparison use `AppVersion("0.1.0")`
(`LocalScribeApp.kt:363`, `:366`) rather than the built variant's `BuildConfig.VERSION_NAME`. This is
already inaccurate for the debug suffix and will become stale on the next release. The automatic
toggle is persisted, but no startup/lifecycle path ever calls a non-forced check, so enabling it
does not perform automatic checks. The screen also omits release notes, displays the Play manager's
disabled state only as the raw word `disabled`, and does not resume installer handoff after the
unknown-sources settings activity returns (`:367-370`); the user must infer that a second Install
tap is required.

Use the installed build version, trigger the manager's cache-respecting automatic check at the
chosen launch event, render the flavor-specific disabled/store-managed state and release notes,
and handle return from unknown-source settings so a verified ready APK proceeds or presents a
clear retry action. Cover GitHub and Play UI states without duplicating D's verification logic.

### F2-8 — Medium: persisted appearance settings do not affect the app

The dynamic-color and dark-theme switches only write `SharedPreferences`
(`LocalScribeApp.kt:354-361`). `LocalScribeApp` always installs a parameterless `MaterialTheme`
(`:87`), never reads those values into a color scheme, never follows system dark mode, and
`MainActivity` does not enable edge-to-edge. The onboarding branch is outside even that theme
provider (`:82-87`). The controls therefore promise behavior that never occurs.

Define the persisted theme mode required by the product (including system light/dark), apply dynamic
light/dark schemes where supported with a static fallback, wrap onboarding in the same theme, and
enable edge-to-edge with appropriate insets. Add a state-restoration/theme-selection test.

### F2-9 — Medium: required transcript, progress, model, and demo information is missing

Several user-visible requirements represented as delivered in `.review/F2-working/docs/reports/F-ui.md`
have no implementation in the staged UI:

- Transcript has no copy action, and the five export buttons are placed in one non-wrapping row
  (`LocalScribeApp.kt:236-249`), which will clip on ordinary phone widths and at larger font scales.
- Job cards show only model/status and a raw lowercased failure enum, with no persisted chunk
  progress or user-facing thermal/source/model recovery guidance (`:186-197`).
- Models does not show installed-space totals, benchmark hardware/memory/thermal evidence, or an
  actual compatible comparison. It lists all historical records together (`:339-344`) without
  using F1's compatibility key, so records from different hashes/configurations/samples appear
  comparable.
- VAD and unavailable RU/mixed demos are merely described in static text; VAD cannot be downloaded,
  retried, or deleted, and the quality screen does not show fixture provenance/license/source
  (`:317-318`, `:331-337`, `:372-373`).

Implement these as real screen state derived from the existing flows and demo manifest. Use adaptive
layouts (wrapping/overflow menus or width classes), user-facing failure text, and compatibility
filtering for comparison. Exercise phone/tablet widths and large font scale in Compose tests.

### F2-10 — Medium: UI work bypasses the required scoped state holders and performs blocking export on main

All screen state and actions live directly in one 379-line composable file; there are no scoped
ViewModels despite the F2 contract. Configuration changes reset navigation, selected source,
transcript search, export choice, and status messages. `TranscriptExporter.write` performs
`openOutputStream` and the complete write synchronously (`TranscriptExporter.kt:19-21`), while its
caller launches on the main composition scope (`LocalScribeApp.kt:214-217`). Large transcripts or
slow document providers can block the UI, and write/share failures are silently discarded.

Move graph interaction and durable screen state into scoped ViewModels with lifecycle-aware flows,
dispatch export/file/player IO off main, retain meaningful state across recreation, and expose
sanitized success/error state. Extend the two existing smoke tests: the brief explicitly requires
critical import and start-state coverage, while `MainActivityTest` currently covers onboarding and
two destination labels only.

## Source evidence and verification boundary

This was a bounded source review of the F2-owned files in `.review/F2-working`, the real repository
adapter APIs, F2/F1 contracts and reports, and the relevant product, architecture, model, and update
requirements. No Gradle build, device test, screenshot, native inference, source mutation outside
this report, or git operation was run. Compilation and device behavior therefore remain explicit
verification gaps; this report does not claim an unobserved test failure.
