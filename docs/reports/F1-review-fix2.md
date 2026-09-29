# Task F1 bounded re-review — fix round 2

Date: 2026-09-21

## Verdict

**Accepted for the bounded F1 scope.** The remaining cancel-during-unload persistence race is
closed. F1-2 and F1-3 retain their closed disposition from fix round 1. No F1 review finding
remains open.

## Finding disposition

### F1-1 — Closed: cancellation is restored before persistence

After the unconditional non-cancellable unload and its primary/cleanup failure handling, `runOne`
now calls `currentCoroutineContext().ensureActive()` before reading the completed result or calling
`benchmarkStore.upsert`
(`app/src/main/kotlin/io/github/surioustype/localscribe/benchmark/AndroidBenchmarkManager.kt:109-117`).
If cancellation first arrives while unload is suspended, the restored caller context therefore
throws `CancellationException` before the store boundary. Existing behavior remains intact: every
created engine receives an unload attempt, cleanup failure cannot replace an existing primary
failure, and persistence occurs only after successful cleanup and the cancellation guard.

The added regression blocks inside fake-engine unload, cancels the benchmark, then releases unload
(`app/src/test/kotlin/io/github/surioustype/localscribe/benchmark/AndroidBenchmarkManagerTest.kt:115-131`).
It verifies propagated cancellation, `CANCELLED` run state, and an empty store. Because the test
waits on a signal emitted from `unloadModel`, it also proves cleanup was entered before cancellation
was released. This directly covers the ordering left open by fix round 1.

### F1-2 — Remains closed

Public benchmark and quality entry points continue to publish sanitized typed running and terminal
state. Fix round 2 does not weaken that behavior; the new regression also confirms that the added
guard is translated to `CANCELLED`, rather than `FAILED`.

### F1-3 — Remains closed

WER and aligned token differences continue to use the shared normalized token stream. Fix round 2
does not alter that path.

## Evidence and scope

The updated implementation report records 17/17 focused manager and fixture tests passing. The
current manager and test source were inspected specifically because `.review/F1-fix2.diff` was
empty due to a packaging defect; source inspection was explicitly authorized for this re-review.
No Gradle, native inference, source edit, or git operation was performed. No device benchmark or
speed, thermal, or memory-performance claim was made or reviewed.
