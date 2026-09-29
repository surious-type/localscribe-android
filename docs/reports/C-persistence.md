# Task C report — durable persistence and background execution

## Delivered

- Room entities and DAOs for audio sources, transcription jobs, chunks, raw segments, installed
  models, and benchmark records.
- Foreign keys retain source metadata while jobs exist and cascade job/chunk raw records only at
  the owned boundary. Unique chunk windows and indexed job/status/timestamp lookup paths prevent
  ambiguous checkpoint identity.
- `RoomTranscriptionRepository` implements atomic job plus plan creation, atomic claim with attempt
  increment, raw-segment replacement plus chunk completion, guarded pause/resume/cancel/fail/
  complete transitions, and one-transaction interrupted-work recovery.
- Chunk completion reads the current job and chunk states inside the commit transaction. A late
  native result cannot turn a paused or cancelled job into completed work. Repeating an already
  committed completion leaves the first checkpoint unchanged.
- `RoomAudioSourceStore` implements E's exact persistence seam. Source deletion is checked in a
  transaction and rejected while any transcription job still references the source.
- `RoomInstalledModelRepository` and `RoomBenchmarkStore` persist installed artifacts and benchmark
  evidence. Benchmark records include `sampleId`; migration defaults legacy rows to `unspecified`.
- Exported Room schemas cover versions 1 and 2. `MIGRATION_1_2` adds benchmark sample identity and
  lookup indexes without destructive migration.
- `TranscriptionCoordinator` serializes sessions through a required injected `Mutex`, freezes the
  job's model/hash/config, loads the verified model once, decodes one bounded window at a time, uses
  bounded prior transcript context, converts relative engine timestamps to absolute timestamps
  exactly once, checkpoints each completed chunk, and unloads in `NonCancellable` cleanup.
- Manual pause/cancel signals reach native inference and persist control state before any late
  completion can commit. The coordinator publishes per-execution ownership before waiting for the
  shared model-operation mutex or performing its first repository read, so a pause issued
  immediately after start is retained under either form of contention. Multiple waiting
  executions cannot overwrite each other's controls. External coroutine cancellation durably
  pauses an owned running job before it propagates, including service teardown, and
  non-cancellable cleanup always releases execution ownership.
- Moderate thermal state halves thread count for future chunks. Severe/critical state persists an
  actionable thermal pause. `AndroidThermalMonitor` unregisters its API 29+ listener.
- `TranscriptionService` starts foreground immediately for start/resume, selects
  `mediaProcessing` on API 35+, `specialUse` on API 34, and no unsupported explicit type on API
  26–33. Its progress notification reports processed/total audio time and carries pause, resume,
  and stop actions. Service timeout persists an actionable pause and stops foreground execution.
  Start and resume requests enter a main-thread serial queue synchronously; a request received
  while the previous execution unwinds is retained and starts next instead of being dropped.
  Foreground notification actions remain bound to the active job while another job is queued, and
  switch to the queued job only from the queue's `onStarted` callback.
  No boot receiver or automatic background launch was added.
- `RecoveryInitializer` is the explicit startup hook for cold recovery.

## Integration guide

F's application graph should create the database and adapters once:

```kotlin
val database = LocalScribeDatabase.open(applicationContext)
val transcriptionRepository = RoomTranscriptionRepository(database)
val sourceStore = RoomAudioSourceStore(database)
val installedModels = RoomInstalledModelRepository(database)
val benchmarkStore = RoomBenchmarkStore(database)
```

`BenchmarkStore` exposes `observe()`, `get(id)`, `upsert(record)`, and `remove(id)`.

Create exactly one application-level `Mutex` for operations that use or delete model files. The
`TranscriptionCoordinator` constructor requires this argument. F's graph must pass the same
instance to the coordinator and benchmark orchestration, while D's model file deletion must
acquire it as well. `RoomInstalledModelRepository` deliberately does not lock: D's file store owns
the file-plus-row deletion critical section, which avoids nested acquisition of the non-reentrant
mutex. F also owns the graph-level identity test for this shared instance because the application
container does not exist in C's scope.

Construct `TranscriptionCoordinator` with the Room repository, E's `AudioRepository`,
`AudioPipeline`, and `TranscriptionEngine`, plus the installed-model repository, thermal monitor,
and shared mutex. Call `RecoveryInitializer(transcriptionRepository).recover()` once during
application startup; it does not start the service.

The application implements `ServiceDependenciesProvider` and returns a small
`ServiceDependencies` implementation containing the coordinator, durable repository, audio
repository, and thermal monitor. A visible UI action starts `TranscriptionService` with one of its
public action constants and `EXTRA_JOB_ID`. There is no service locator fallback and no automatic
boot launch.

## Verification evidence

Test-first coordinator work began with:

```shell
source /tmp/localscribe-tools/env.sh
./gradlew :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest
```

The first run failed at test compilation because `TranscriptionCoordinator`, `ExecutionOutcome`,
`DurableTranscriptionRepository`, and thermal types did not exist. After the minimal coordinator
implementation, the focused two-test suite passed (`BUILD SUCCESSFUL`); later tests add moderate
and severe thermal behavior and await the final covering run below.

Room tests were written before production adapters. The initial Android-test compilation failed on
the absent `LocalScribeDatabase`, `RoomTranscriptionRepository`, and persistence exception. After
implementation and schema generation:

```shell
source /tmp/localscribe-tools/emulator-env.sh
./gradlew :app:compileGithubDebugKotlin :app:compileGithubDebugAndroidTestKotlin
```

Result at that checkpoint: `BUILD SUCCESSFUL`; 33 tasks, six executed and 27 up-to-date. The v1 and
v2 schema JSON files were generated in `app/schemas/`.

The covering Room device command was:

```shell
source /tmp/localscribe-tools/emulator-env.sh
./gradlew --no-daemon -Pkotlin.incremental=false \
  :app:connectedGithubDebugAndroidTest \
  -PtestRunner=io.github.surioustype.localscribe.audio.EngineTestRunner \
  -Pandroid.testInstrumentationRunnerArguments.class=\
io.github.surioustype.localscribe.data.RoomTranscriptionRepositoryTest,\
io.github.surioustype.localscribe.data.LocalScribeDatabaseMigrationTest
```

Result: `BUILD SUCCESSFUL in 1m 1s`; all seven tests passed on the API 35 x86_64 emulator. The
tests cover atomic plan rollback, claim/attempt increments, idempotent per-chunk segment
replacement, cancellation winning against late completion, cold recovery, protected source
deletion, benchmark sample identity, and validated v1→v2 migration with legacy-row preservation.
Incremental compilation was disabled for this run after a prior concurrent Gradle daemon retained
the same Kotlin cache registration; the clean single-use daemon reached and passed all tests.

The focused coordinator suite was then run after the pre-start/repeated-pause regression fix:

```shell
source /tmp/localscribe-tools/env.sh
./gradlew --no-daemon -Pkotlin.incremental=false \
  :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest
```

Result: `BUILD SUCCESSFUL in 10s`; all five coordinator tests passed. A subsequent single-device
regression for repeated pause idempotence also passed (`BUILD SUCCESSFUL in 42s`, one test).

`./gradlew --no-daemon :app:ktlintCheck` returned `BUILD SUCCESSFUL in 3s`. The current AGP 9
integration exposes only the aggregate app task (the older source-set-specific task names are not
registered).

The first review round identified four lifecycle gaps: an immediate start/pause race, dropped
start/resume requests while the previous execution unwound, external service cancellation leaving
durable work running, and an optional shared execution mutex. The fix round added barrier and
cancellation coordinator regressions plus a pure JVM service-queue suite. The covering command was:

```shell
source .local-tools/env.sh
gradle :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest \
  --tests io.github.surioustype.localscribe.service.SerialExecutionQueueTest
```

Result: `BUILD SUCCESSFUL in 6s`; all ten tests passed (seven coordinator and three service queue).
The queue tests cover pause/immediate-resume retention, start(B) while A unwinds, and teardown
cancelling active work without launching queued work. The coordinator regressions cover a pause
while the initial Room read is suspended and durable pause on external cancellation.

After a final cleanup guard made execution-ownership release survive an unload exception, the
coordinator suite was rerun from current source. Result: `BUILD SUCCESSFUL in 19s`; all seven tests
passed. `gradle :app:ktlintCheck` also returned `BUILD SUCCESSFUL in 554ms` from the exact final
source (the task was up-to-date). The earlier seven Room/migration device tests and repeated-pause
device regression were not rerun because this round did not change persistence or schema code.

The second review round found two direct races left outside the first regressions. Both fixes used
red/green tests. With the shared execution mutex locked, the new coordinator test initially failed
because execution completed instead of returning `Paused`; after moving per-execution control
registration ahead of mutex acquisition, the same test passed (`BUILD SUCCESSFUL in 16s`). The
queued-notification routing test initially failed at compilation because the requested service
routing state did not exist; after wiring that state through `SerialExecutionQueue.onStarted`, it
passed (`BUILD SUCCESSFUL in 3s`).

The covering fix-round command was:

```shell
source .local-tools/env.sh
./gradlew :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest \
  --tests io.github.surioustype.localscribe.service.SerialExecutionQueueTest \
  --tests io.github.surioustype.localscribe.service.TranscriptionServiceControlRoutingTest
```

Result: `BUILD SUCCESSFUL in 3s`; all 12 tests passed (eight coordinator, three serial queue, and
one service control-routing test). No Room, schema, device, or native code changed in this round,
so their completed checks were not repeated.

The repository-required `./gradlew staticAnalysis :app:lintGithubDebug` reached Android lint but
failed on five existing API-28 `NewApi` errors in
`app/src/github/kotlin/io/github/surioustype/localscribe/updates/AndroidGithubUpdateSupport.kt`.
The full report contains no C fix-round error; it reports one pre-existing service warning for an
API-26 check and unrelated warnings elsewhere. The current app Gradle integration registers only
Kotlin-script ktlint tasks, while compilation of all changed C production and test sources passed
in the focused run above.

## Current limitations and handoff notes

- Notification permission affects notification visibility but is not treated as microphone
  access. The app intentionally declares no microphone permission.
- Native process crashes cannot be translated in-process. Room checkpoints and startup recovery
  bound lost work to the active chunk.
- The service relies on Android's foreground-service time budget on API 35+ and does not promise
  unbounded background execution.
- F owns the application/container implementation and must install the startup recovery hook and
  `ServiceDependenciesProvider` described above, pass one required execution mutex through every
  model-use/delete component, and add the graph-level shared-instance wiring test.
