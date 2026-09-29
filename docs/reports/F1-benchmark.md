# Task F1 — real benchmark and licensed quality demo adapters

## Delivered surface

`AssetDemoAudioRepository` reads only `assets/demo/manifest.json`. It exposes the bundled
English JFK fixture and its manifest-provided provenance and rights text. It does not expose the
manifest's Russian or mixed-language omissions as runnable samples. Before decoding it validates
the WAV SHA-256, RIFF PCM format, mono 16 kHz layout, and exact manifest duration, then returns
mono 16 kHz float PCM.

`AndroidBenchmarkManager` is constructed with:

```kotlin
AndroidBenchmarkManager(
    engineFactory = BenchmarkEngineFactory { WhisperTranscriptionEngine() },
    benchmarkStore = RoomBenchmarkStore(database),
    demoAudioRepository = AssetDemoAudioRepository(AndroidAssetReader(context.assets)),
    executionMutex = applicationModelOperationMutex,
    sampler = AndroidBenchmarkSampler(context),
)
```

The `executionMutex` must be the same application-level mutex supplied to
`TranscriptionCoordinator` and model-file deletion. Each selected installed model runs in sequence
and no catalog or download path is used. Model loading and unloading are inside that critical
section but excluded from the measured inference interval. Sampling records the maximum observed
Java plus native allocation estimate and current thermal status before, during, and after inference.
The timing source is `SystemClock.elapsedRealtime()`.

The manager unloads in non-cancellable cleanup. Caller cancellation propagates and does not persist
a partial record. `observeRunState()` exposes the foreground-UI lifecycle state. F2 should cancel
its calling coroutine when the screen stops; it must not start benchmark inference in background
outside a foreground service.

## F2 handoff

Create the hardware snapshot with `AndroidHardwareProfile.read(context)`. Use:

```kotlin
manager.benchmarkSelected(installedModels, config, "english_jfk_37s", hardware)
manager.runQualityDemo(installedModel, config, "english_jfk_37s", hardware)
```

`QualityDemoResult` contains actual `recognizedText`, the fixture's `referenceText`, the existing
`QualityMetrics` score, and `AlignedTokenDifferences` output. Persisted `BenchmarkRecord.sampleId`
is always the fixture ID. `BenchmarkComparisons.compatibleWith` only retains records sharing device,
model hash, sample ID, and exact inference configuration, so the UI must not compare unmatched runs.

No performance numbers are claimed here; an actual device run is required to produce them.

## Review fix 1

The manager now attempts non-cancellable unload for every created engine, including a load that
fails after allocation. Cleanup failure is suppressed onto the original execution or cancellation
failure, and a record is persisted only after successful unload. All public benchmark and quality
entry points publish typed `DomainFailure` terminal state. Token differences now use
`QualityMetrics.normalizedWords`, the same normalized stream used for WER.

## Review fix 2

After non-cancellable unload, the manager checks coroutine cancellation before persisting the
record. The deterministic unload-blocking regression cancels at that boundary and verifies a
`CancellationException`, `CANCELLED` run state, and an empty benchmark store.

Focused JVM verification passed: `AndroidBenchmarkManagerTest` and
`AssetDemoAudioRepositoryTest` (17 tests total).

## Verification

Focused JVM tests passed: `AndroidBenchmarkManagerTest` and
`AssetDemoAudioRepositoryTest` (7 tests). They cover monotonic timing, sample identity, actual
recognized-text quality output and token differences, cancellation cleanup, comparison-key
isolation, real bundled fixture decoding/reference/provenance, unavailable fixture absence, and
checksum rejection. `:app:ktlintCheck` also passed. No native inference benchmark was run on the
software emulator, so this report makes no speed or memory-performance claim.

### Fix1 covering verification (2026-09-21)

After applying the staged regressions, the implementer reported the focused `AndroidBenchmarkManagerTest` and `AssetDemoAudioRepositoryTest` run passing **16/16**, including load/cleanup failure, preserved cancellation, no-persist guarantees, public state transitions, waiting-mutex cancellation, batch progress, and normalized alignment. No device performance measurement was made. The earlier 7-test result above is pre-fix evidence. The additive core normalized-token test also participated in the previously reported 39-test core run during D verification.
