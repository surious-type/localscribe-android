# Task F2 fix-round 1 source re-review

## Findings

### F2-fix1-1 — High: benchmark cancellation relinquishes ownership before the native run has stopped

`BenchmarkRunController.onStop()` cancels the active coroutine and immediately sets both
`activeJob = null` and `isBusy = false`
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/ui/UiControllers.kt:76-80`).
Cancellation is cooperative, so the benchmark may still be unwinding native inference or waiting to
release the shared execution mutex when the activity resumes. The UI can then start another run.
Worse, the cancelled run's `finally` block later clears `isBusy` and `activeJob` again (`:65-71`),
which can erase ownership of that newer run and allow a third run or prevent `ON_STOP` from cancelling
the run that is actually active. The current stop test advances cancellation to completion before it
could start a replacement, so it does not exercise this race
(`BenchmarkRunControllerTest.kt:14-26`).

Keep the cancelled job as the active owner until its `finally` completes, and reject new work while
it is cancelling. Make terminal cleanup conditional on the completing job still being the active
generation. Add a test that cancels a run whose `finally` is deliberately suspended, attempts a new
start, and proves no second run begins until cleanup finishes.

### F2-fix1-2 — High: Resume bypasses notification permission and foreground-launch failure handling

The initial start path requests `POST_NOTIFICATIONS` before creating a job and uses
`TranscriptionStartController`, but a paused or pending job's Resume button directly calls
`ContextCompat.startForegroundService`
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:250-253`).
If permission was denied or revoked on Android 13+, resumed work again has no visible drawer controls.
The direct call is also outside `runCatching`, so a foreground-service launch rejection can escape the
click handler instead of producing the sanitized failure used for a new transcription. The staged
permission tests cover only `TranscriptionStartController`; they cannot detect this second launch
path.

Route Resume through the same permission-aware foreground-launch controller (without creating a new
job), surface denial or launch rejection in the job row, and add API 33+ coverage for permission
revocation between pause and resume.

### F2-fix1-3 — Medium: stopping the activity does not release an in-flight or prepared player

`TranscriptScreen` calls `player.onStop()` at `ON_STOP`
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:290-295`),
but `TranscriptPlaybackController.onStop()` only delegates to `pause()`
(`ui/playback/TranscriptPlaybackController.kt:101-103`). If asynchronous preparation is still in
progress, `pause()` cannot pause or cancel it; preparation completes in the background and retains the
`MediaPlayer`. If playback is already prepared, the backend also remains allocated until the
composable is disposed. This does not satisfy the brief's requirement to release media and coroutine
resources on lifecycle stop, and the stop test asserts only `pauseCalls`, not preparation cancellation
or backend release (`TranscriptPlaybackControllerTest.kt:73-84`).

Release the backend and cancel preparation/polling on stop while retaining only the desired logical
position, then recreate the backend on the next play/seek. Test `ON_STOP` during suspended preparation
and after ready playback, including a subsequent successful resume.

### F2-fix1-4 — Medium: the scoped state holder is only partially used, so export and action state still disappear

The new `LocalScribeUiStateViewModel` persists destination, source selection, transcript selection,
query, export format, and status (`LocalScribeUiStateViewModel.kt:13-40`), but the composables never
read or write its `exportFormat` or `statusMessage`. `TranscriptScreen` instead keeps `pendingExport`
in plain `remember` state (`LocalScribeApp.kt:282-288`), so recreation while the SAF picker is open
loses the chosen format and the returned URI is silently ignored. Import/export/share failures are
also either stored in local `remember` state or discarded (`:153-155`, `:285-288`, `:322-326`). Model
selection, source language, benchmark selection/status, and quality results remain local composable
state as well (`:187-189`, `:343-346`). The state-holder test only proves that manually calling its
setters restores values; it does not prove the production UI uses those values.

Move the promised durable fields and user-visible operation results into screen-scoped state holders,
bind the production callbacks to them, and surface sanitized success/failure results. Add a recreation
test around a pending SAF export and at least one failed export/share operation.

### F2-fix1-5 — Medium: returning from unknown-source settings still does not continue or explain installation

The GitHub platform opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` and returns without launching the
verified APK when permission is missing
(`.review/F2-working/app/src/github/kotlin/io/github/surioustype/localscribe/updates/AndroidGithubUpdateSupport.kt:104-111`).
The Settings UI invokes `requestInstall()` from an ordinary button and registers no activity-result or
resume handler (`LocalScribeApp.kt:456-458`). After granting permission, the user is returned to the
same ready state with no explanation that Install must be pressed again. This is the unresolved return
path called out in original finding F2-7.

Represent the permission handoff in UI state and, on return, either continue the verified install or
show an explicit retry action and denial state. Cover both grant and denial returns in the GitHub UI
flow.

### F2-fix1-6 — Medium: benchmark comparison and demo provenance requirements remain unimplemented

The Models screen still renders every historical benchmark record in one flat list
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:419-420`)
and never uses the staged compatibility helper. Records from different devices, hashes, samples, or
configs therefore appear adjacent without an actual compatible comparison. The quality result shows
reference/recognized text and errors (`:411-417`) but no fixture source, license, or provenance, and
the UI never identifies the unavailable RU/mixed demos required by the brief. A static About sentence
claiming that provenance is in the manifest (`:459-460`) does not expose that information to the user.

Build a comparison view from an explicit compatible key and label all compared dimensions. Expose the
demo manifest metadata in quality/About UI and render RU/mixed fixtures as unavailable rather than
omitting them. Add presentation tests with deliberately incompatible records and the unavailable
fixtures.

### F2-fix1-7 — Medium: the dark-theme Boolean cannot represent the required system/light/dark modes

Theme selection computes dark mode as `darkPreference || systemDark`
(`.review/F2-working/app/src/main/kotlin/io/github/surioustype/localscribe/ui/UiControllers.kt:24-35`).
On a device using system dark mode, switching the UI's “Dark theme” control off still produces a dark
scheme. Conversely, there is no persisted way to distinguish “follow system” from an explicit light
choice, despite the brief requiring system light/dark behavior and persisted appearance selection.
The current test does not cover switching to light while the system is dark
(`UiPresentationTest.kt:12-17`).

Persist a three-state theme mode (`SYSTEM`, `LIGHT`, `DARK`), derive dynamic/static schemes from that
mode, and test all modes against both system appearances and recreation.
