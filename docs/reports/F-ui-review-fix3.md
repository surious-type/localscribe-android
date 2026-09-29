# Task F2 fix-round 3 scoped re-review

## Findings

### F2-fix3-1 — Low: stop-during-preparation remains untested

The retained-position defect is fixed: `onStop()` now copies the displayed position into
`requestedPositionMs`, and the recreation test verifies that the next backend seeks to `3_200ms`
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/playback/TranscriptPlaybackController.kt:102-109`,
`app/src/test/kotlin/io/github/surioustype/localscribe/ui/playback/TranscriptPlaybackControllerTest.kt:78-97`).
The controller also cancels preparation and closes the current backend through
`releaseCurrentBackend()`.

However, the fix-round 2 request to cover lifecycle stop while `prepare()` is suspended is still
open. The seven controller tests contain no suspending fake preparation, so they do not prove that
`onStop()` closes the abandoned backend or that a late preparation completion cannot republish ready
or playing state. Add a deferred `prepare()` test that calls `onStop()` before completion, asserts one
close, releases preparation, and verifies that the stopped state remains authoritative.

### F2-fix3-2 — Low: durable production callbacks still lack recreation and UI coverage

The source/model/language/message choices, benchmark selection/status, quality result, pending export
format, and shared operation status are now stored in `SavedStateHandle`
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeUiStateViewModel.kt:15-29,76-95`).
The production import, CreateDocument, export, and share callbacks now write through that holder
(`app/src/main/kotlin/io/github/surioustype/localscribe/ui/LocalScribeApp.kt:244-250,616-642,723-734`).

The only new coverage still invokes holder setters directly and reconstructs the model over the same
handle (`app/src/test/kotlin/io/github/surioustype/localscribe/ui/LocalScribeUiStateViewModelTest.kt:8-48`).
It does not exercise an Activity recreation with a pending CreateDocument result, source/models UI
restoration, inbound-import failure feedback, or share success/failure feedback. Add Compose or
instrumentation coverage through the real launchers/callbacks so a production wiring regression does
not pass while the holder unit test remains green.

## Closure verdict

- **F2-fix2-1 closed.** The benchmark job is created lazily, published as `activeJob`, and only then
  started. The identity guard remains in `finally`; immediate completion/failure and non-cancellable
  cancellation cleanup are covered. The parent-reported four controller tests passed.
- **F2-fix2-2 source defect closed; requested regression coverage partial.** Stop position is retained
  and asserted on recreation. Finding F2-fix3-1 is the remaining test obligation. The parent-reported
  seven playback controller tests passed.
- **F2-fix2-3 source wiring closed; requested production coverage partial.** Durable state and
  success/failure callbacks are wired. Finding F2-fix3-2 remains.
- **F2-fix2-4 closed.** `latestBenchmarkComparison()` anchors to the first record in newest-first DAO
  order, and the presentation test includes two current compatible records followed by an older
  incompatible record.

No direct source regression was found in the scoped changes. The separate notification-permission
instrumentation execution is outside this re-review verdict. This was a source-and-test inspection
plus report write only; no Git or Gradle command was run.
