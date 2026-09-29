# Task C fix round 2 re-review

No actionable Task C findings remain in the scoped fix 2 review.

## CLOSED — Pause/thermal control survives shared execution-mutex contention

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:79-117`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:227-258`

`execute()` now creates and registers its `ActiveExecution` under `controlMutex` before attempting `executionMutex.withLock`. A pause that arrives while model deletion or benchmark work holds the shared mutex therefore finds the pending execution and records `PAUSE`; the execution checks that request immediately after acquiring the shared mutex and exits before reading the job, transitioning it to `RUNNING`, or loading a model. Tracking a list of controls also prevents a later waiting execution from overwriting an earlier execution's control. Non-cancellable outer cleanup removes only the matching control. The new locked-mutex regression directly covers the reported ordering and verifies that the job/chunk stay pending and the model is not loaded.

## CLOSED — Queued jobs no longer replace active notification controls

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionService.kt:57-100`, `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionServiceControlRouting.kt:3-15`, `app/src/test/kotlin/io/github/surioustype/localscribe/service/TranscriptionServiceControlRoutingTest.kt:11-39`

While an execution is active, `notificationJobIdFor()` resolves every newly queued start/resume notification to the active job ID, so its pause/resume/stop intents remain attached to the work actually running. Ownership changes to the queued job only through `SerialExecutionQueue.onStarted`, where the service immediately rebuilds the foreground notification and replaces the progress observer. The routing regression covers A active, B queued, and the switch to B after A releases.

## Previously closed findings remain closed

- `SerialExecutionQueue` still retains same-job resume and different-job start requests while prior work unwinds.
- Uncontrolled coroutine cancellation still durably pauses the owned running job before propagation and model cleanup.
- `TranscriptionCoordinator` still requires explicit shared-mutex injection; graph identity verification remains assigned to F2.

## Verification evidence

The reported focused run passed all 12 tests: eight coordinator tests, three serial-queue tests, and one service control-routing test. These include red/green regressions for both residual findings. Persistence/schema behavior did not change, so the prior seven Room/migration device tests remain applicable. Repository lint is currently blocked by five `NewApi` errors in D's GitHub update implementation; no C fix-round lint error was reported, and that separate D scope is not a Task C finding.
