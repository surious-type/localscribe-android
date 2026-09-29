# Task F2 fix-round 2 integrated-source review

## Findings

### F2-fix2-1 — High: benchmark ownership can be stranded before the job is assigned

`BenchmarkRunController.start()` captures a `lateinit` `launchedJob` from the coroutine and only
assigns `activeJob` after `scope.launch` returns
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/UiControllers.kt:79-95`). On an immediate
dispatcher, which is a valid execution mode for a UI scope, a block that completes or throws before
its first suspension reaches `finally` before `launchedJob` has been initialized. Cleanup then throws
`UninitializedPropertyAccessException`; after `launch` returns, the already-completed job is stored as
`activeJob` and `isBusy` remains true permanently. The tests use the queued `runTest` dispatcher and
therefore cannot exercise this ordering (`BenchmarkRunControllerTest.kt:30-42`).

Publish the owner before execution can start, for example by creating a lazy job, assigning it to
`activeJob`, and then starting it, while retaining the identity guard. Add an immediate-dispatcher
test for both synchronous completion and synchronous failure. The cancellation test should also put
its suspending cleanup in `NonCancellable`; `releaseCleanup.await()` in an already-cancelled context
does not model the native non-cancellable unload that motivated the ownership fix
(`BenchmarkRunControllerTest.kt:44-76`).

### F2-fix2-2 — Medium: lifecycle stop displays the current position but resumes from a stale one

`TranscriptPlaybackController.onStop()` copies the polled position into the public state, releases the
backend, but never copies it into `requestedPositionMs`
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/playback/TranscriptPlaybackController.kt:102-109`).
The next `play()` recreates the backend and seeks to the old requested position, normally zero or the
last manual scrub, via `ensurePrepared()` (`:124-142`). The UI consequently shows the stop position
until the user presses Play, then jumps backwards. The stop test checks only backend recreation and
playing state, not the resumed seek position (`TranscriptPlaybackControllerTest.kt:77-94`), and there
is still no stop-during-suspended-preparation case.

Store the current logical position in `requestedPositionMs` before releasing the backend. Extend the
test to advance the fake backend position, stop, recreate, and assert that the new backend seeks to
that position; also cover stop while `prepare()` is suspended and verify the abandoned backend closes.

### F2-fix2-3 — Medium: the promised durable screen state and operation feedback remain partial

The pending SAF format and transcript status are now connected to `SavedStateHandle`, which fixes the
specific lost-format callback. However, source model/language/error state remains plain `remember`
state (`app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:330-334`), and model
selection, benchmark status, and the quality result do too (`:740-743`). Activity recreation still
silently resets those user choices/results despite the F2 contract requiring UI work through scoped
state holders. Error/result wiring is also incomplete: an `ACTION_SEND`/`ACTION_VIEW` import discards
the result entirely (`:244-250`), and transcript sharing reports failure but no success (`:702-712`).

Move those screen choices and sanitized action results into scoped state holders and bind the
production callbacks to them. Add recreation coverage around the actual CreateDocument result and
the source/models screens, plus UI assertions for inbound-import failure and share success/failure;
the current state-holder unit test only proves manually invoked setters survive a reused handle.

### F2-fix2-4 — Medium: saved benchmark comparison is anchored to the oldest record

Room emits benchmark records newest first
(`app/src/main/kotlin/io/github/surioustype/localscribe/data/Daos.kt:163-165`), but the Models screen
chooses `benchmarks.lastOrNull()` as its comparison reference
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:954-960`). Once historical
records contain another hash, sample, device, or configuration, the screen groups around the oldest
run and can hide every newly completed compatible run. This makes the just-finished benchmark appear
not to have been saved even though its record exists.

Anchor the group to `firstOrNull()` if the intended view is the latest run, or expose explicit grouped
comparison keys and let the user choose a group. Add a presentation test with an old incompatible
record followed by two current compatible records in DAO order. The fixture provenance shown after a
quality run and the explicit RU/mixed unavailable notice are otherwise present in the integrated UI.

## Accepted source closures pending execution evidence

- Resume now checks/request notification permission and routes launch failures through the shared
  controller without deleting the paused job.
- Player stop now cancels preparation/polling and closes the backend; the retained-position defect is
  isolated above.
- SAF export format and export success/failure use saved UI state; production recreation coverage is
  still required as described above.
- Returning from unknown-source settings exposes an explicit Install retry path. Grant and denial
  instrumentation coverage requested by fix round 1 is still absent.
- Appearance mode is persisted as `SYSTEM`, `LIGHT`, or `DARK`, and explicit light correctly overrides
  a dark system theme.

This was a read-only review of the integrated source plus the requested report write. No Gradle, Git,
device, or duplicate test command was run. Compilation and runtime evidence remain with the parent
validation run.
