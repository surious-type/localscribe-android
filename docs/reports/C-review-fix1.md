# Task C fix round 1 re-review

## OPEN P1 — Pause/thermal control is still lost while execution waits for the shared mutex

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:82-98`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:207-230`

The fix publishes `activeExecution` before the first repository read, so it closes the tested Room-suspension race. Publication still occurs inside `executionMutex.withLock`, however. If benchmark/model work holds the required application mutex, `execute()` suspends at line 82 before creating or publishing its control. A thermal callback can then call `pause()` for the service's already published `activeJobId`; it observes a `PENDING` job with no matching active execution and returns. When the mutex becomes available, transcription transitions to `RUNNING` despite the severe/critical thermal pause. Register a pending execution/control before waiting for the shared mutex, or retain per-job control requests that are consumed immediately after acquisition. Add a regression that locks the injected mutex, starts `execute()`, requests pause, releases the mutex, and verifies no model load or `RUNNING` transition.

## CLOSED — Start/resume requests are retained while prior execution unwinds

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/service/SerialExecutionQueue.kt:21-59`, `app/src/test/kotlin/io/github/surioustype/localscribe/service/SerialExecutionQueueTest.kt:13-68`

`SerialExecutionQueue` registers requests synchronously and starts the next request from the active execution's `finally`. Same-job resume and a different queued job therefore survive the prior coroutine's unwind. The focused tests exercise both orderings.

## OPEN P1 direct regression — A queued job replaces the active job's notification controls before it starts

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionService.kt:85-100`, `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionService.kt:178-205`

Every queued start/resume immediately calls `startForegroundCompat(jobId, ...)`, even when another job remains active. Starting B while A unwinds therefore changes the visible notification and its pause/resume/stop intents to B before the queue invokes `onStarted(B)`. If A's progress Flow does not emit again, the notification continues to present B while A is executing. Tapping pause for that notification is a no-op for pending B at the coordinator, then stops the service and externally pauses A; stop cancels B while teardown pauses A. Keep the notification/actions bound to `activeJobId` until the queue actually starts B, and update it from `onStarted`. Add a service-level test around notification/control routing; the pure queue tests do not cover this integration.

## CLOSED — External service cancellation durably pauses owned running work

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:183-203`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:245-257`

An uncontrolled `CancellationException` now enters a `NonCancellable` cleanup that pauses the currently owned `RUNNING` job before propagating cancellation. The matching coordinator test verifies both job and chunk become `PAUSED` and the model unloads.

## CLOSED at the Task C boundary — Shared execution mutex injection is mandatory

**Path:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:68-78`

The private default mutex was removed, so production construction must supply the application mutex. The graph-level identity assertion remains correctly deferred to F2, where the coordinator, benchmark orchestration, and `InstalledModelFileStore` are composed; its absence is an integration evidence dependency, not an open Task C implementation defect.

## Verification evidence

The reported fix run passed all ten focused tests (seven coordinator and three serial-queue tests), the exact-final-source coordinator rerun passed all seven tests, and app ktlint passed. Those runs cover the implemented Room-read barrier, external cancellation cleanup, and queue retention. They do not cover mutex contention before coordinator ownership publication or service notification/action routing. The earlier seven Room/migration device tests remain the applicable persistence evidence; they were not rerun in this review round.
