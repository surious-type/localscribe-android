# Task F1 bounded re-review — fix round 1

Date: 2026-09-21

## Verdict

**Changes required.** F1-2 and F1-3 are closed. F1-1 is substantially improved, but one
cancellation/persistence race remains within its original lifecycle scope.

## Finding disposition

### F1-1 — Partially closed: cancellation first observed during cleanup can still persist a record

The fix now creates the completed record in memory, always attempts unload, suppresses a cleanup
failure onto an existing primary failure, and writes to the store only after unload succeeds
(`app/src/main/kotlin/io/github/surioustype/localscribe/benchmark/AndroidBenchmarkManager.kt:90-121`).
The supplied regressions directly cover load failure, cancellation already raised by inference
plus unload failure, and successful inference plus unload failure. Those parts of F1-1 are closed.

The path after `withContext(NonCancellable) { engine.unloadModel() }` does not explicitly restore
the caller's cancellation check before `benchmarkStore.upsert`. If the caller is cancelled for the
first time while unload is suspended, `primaryFailure` is still null and cleanup may return
normally; a store implementation that completes without a cancellable suspension can then persist
the completed record despite cancellation. This violates the existing requirement that caller
cancellation propagate without a record.

Check the restored caller context for cancellation after cleanup and before persistence. Add a
regression whose unload suspends, cancel the benchmark while it is in unload, release unload, and
assert `CancellationException`, one unload attempt, `CANCELLED` run state, and an empty store.

### F1-2 — Closed: public run paths now publish sanitized typed state

Direct `benchmark` and `runQualityDemo` both execute through `runWithState`; it publishes running,
completed, cancelled, and failed terminal states with model/progress identity
(`AndroidBenchmarkManager.kt:50-55`, `:123-134`). Batch execution retains per-model progress and
now exposes `DomainFailure` rather than a raw throwable (`:57-73`, `:144`). The focused tests cover
quality work while suspended, direct terminal state, sanitized failure, batch completion, and
cancellation while waiting for the shared mutex. No raw platform message is placed in run state.

### F1-3 — Closed: alignment and WER share normalization

`QualityMetrics.normalizedWords` exposes the same NFKC, locale-stable lowercase, punctuation, and
spacing normalization used by WER, and `AlignedTokenDifferences` consumes that stream
(`core/src/main/kotlin/io/github/surioustype/localscribe/core/domain/QualityMetrics.kt:9-29`;
`AndroidBenchmarkManager.kt:145-150`). Raw reference and recognized strings remain separately
available in `QualityDemoResult`. The added app regression equates non-match edits with word
errors, and the core regression covers Unicode normalization, case, punctuation, and repeated
spacing.

## Evidence and scope

The updated report records 16/16 focused tests: 13 manager tests and 3 fixture tests, with zero
failures, errors, or skips. It also cites the additive normalized-token test in the previously
reported 39-test core run. This reviewer inspected `.review/F1-fix1.diff`, the updated F1 report,
and the three original findings only; no Gradle, native inference, source, or git operation was
run. No device benchmark or speed, thermal, or memory-performance claim was made or reviewed.
