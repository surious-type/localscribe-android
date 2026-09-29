# Task C review findings

## P1 — A pause can be lost while a just-started execution is still `PENDING`

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:87-112`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:199-218`

`execute()` reads the job before taking `controlMutex`, while `pause()` takes that mutex and deliberately returns for a `PENDING` job. If the service has launched `execute()` but its first Room read suspends, a following pause acquires `controlMutex`, observes `PENDING`, and returns; `execute()` then transitions the job to `RUNNING` and registers `activeExecution`. The user-visible pause has been lost. Moving the durable transition and registration under the same mutex only protects pauses that arrive after `execute()` reaches that mutex. Make start registration visible before the first suspension, or persist/record a pending pause request that `execute()` must consume before transitioning to `RUNNING`. Add a test with a barrier in the initial `getJob()` to cover start followed immediately by pause.

## P1 — Start/resume commands are silently dropped while the previous execution coroutine unwinds

**Path:** `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionService.kt:73-89,121-143`

Every start/resume command calls `startForegroundCompat()`, but `startExecution()` returns without action whenever `executionJob` is still active. After pause/timeout, native cancellation and model unload can keep that old job active briefly. A rapid resume in that interval therefore creates/updates the foreground notification but launches no execution; the old coroutine later exits and its `finally` clears `activeJobId`. A start for a different pending job is dropped the same way. Serialize command completion as well as command dispatch: retain the requested next job, or cancel/join the prior execution before launching the requested one, and scope cleanup to an execution generation. Add service tests for pause→immediate resume and start(B) while A is unwinding.

## P1 — Service teardown can leave durable state stuck at `RUNNING`/`PROCESSING`

**Paths:** `app/src/main/kotlin/io/github/surioustype/localscribe/service/TranscriptionService.kt:112-118`, `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:183-195`

`onDestroy()` cancels `executionJob` directly. An external coroutine cancellation with no coordinator control request is intentionally rethrown, and the coordinator only unloads the model; it does not pause the job or requeue the processing chunk. If Android destroys the service while the process/application remains alive, startup recovery does not run, Room still reports `RUNNING`/`PROCESSING`, and a later `execute()` rejects the job as not startable. Ensure service teardown first records a durable pause and signals native cancellation, or make coordinator cancellation cleanup atomically pause an owned running session before propagating cancellation. Cover teardown without an explicit pause/stop action.

## P2 — The shared model-operation mutex invariant is optional at the coordinator boundary

**Path:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:68-82`

The coordinator creates a private `Mutex` when integration omits `executionMutex`. That silently defeats the architecture requirement that transcription, benchmarks, and model-file deletion use the same application mutex: `InstalledModelFileStore` can delete an artifact after the coordinator's metadata/hash check while native loading is in progress. Require the mutex constructor argument in production (tests can pass their own), and add a graph-level test proving coordinator and model deletion receive the same instance.

## Verification gap

**Path:** `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:96-103`

The reported five coordinator tests and Room/device tests passed before the final active-registration synchronization edit. No targeted compile or test run has validated that edit because the SDK/toolchain was removed. This is missing evidence, not a fabricated test failure. Re-run the coordinator suite after restoring the environment, including the start/pause barrier regression above; service race/teardown coverage is currently absent.
