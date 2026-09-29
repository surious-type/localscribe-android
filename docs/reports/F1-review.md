# Task F1 bounded review — benchmark and quality adapters

Date: 2026-09-20

## Verdict

**Changes required.** The committed fixture is a real, licensed WAV and the benchmark uses engine
output rather than the reference transcript, but three correctness gaps remain in lifecycle
cleanup, observable run state, and quality-difference alignment. No device-performance claim is
made or accepted by this review.

## Findings

### F1-1 — High: cleanup can be skipped or replace cancellation, and a failed run can remain persisted

`AndroidBenchmarkManager.runOne` sets `loaded` only after `loadModel` returns and calls
`unloadModel` only when that flag is true
(`app/src/main/kotlin/io/github/surioustype/localscribe/benchmark/AndroidBenchmarkManager.kt:82-102`).
If loading allocates any engine-side state and then throws, the manager skips cleanup even though
the engine contract explicitly makes unload safe without a loaded model. Conversely, if
`unloadModel` throws while handling cancellation or an inference failure, the exception from the
`finally` block replaces the original `CancellationException` or domain failure, so cancellation
no longer propagates as required. A successful record is also inserted at line 100 before unload;
an unload failure therefore reports the run as failed while leaving a benchmark row behind.

Always attempt non-cancellable unload for every created engine, preserve the primary cancellation
or execution failure when cleanup also fails, and persist only after the session has cleaned up
successfully. Add regressions for load failure after engine creation, cancellation plus unload
failure, and successful inference plus unload failure; assert cleanup attempts, propagated outcome,
and store contents in each case.

### F1-2 — Medium: the advertised run-state flow does not cover two public run paths and exposes raw errors

Only `benchmarkSelected` transitions `runState`; direct `benchmark` and `runQualityDemo` call
`runOne` without publishing `RUNNING`, `COMPLETED`, `CANCELLED`, or `FAILED`
(`AndroidBenchmarkManager.kt:47-73`). F2 is explicitly handed `runQualityDemo`, so its foreground
UI can observe `IDLE` (or a stale prior terminal state) throughout real native work and has no
manager state for cancellation or failure. In the batch path, `BenchmarkRunState.error` contains a
raw `Throwable`, bypassing the repository's stable `DomainFailure` boundary and allowing platform
or persistence messages to reach UI state.

Route every public execution API through one state-owning wrapper, including model/progress data,
and translate expected adapter/native/persistence failures to a stable, sanitized domain failure.
Add state-sequence tests for a single benchmark, quality demo, batch success, cancellation while
waiting for the shared mutex, inference failure, and cleanup failure.

### F1-3 — Medium: displayed token differences do not explain the reported WER

`QualityMetrics` applies NFKC normalization, lowercasing, and punctuation/separator folding before
computing word errors (`core/src/main/kotlin/io/github/surioustype/localscribe/core/domain/QualityMetrics.kt:9-38`).
`AlignedTokenDifferences` instead trims and splits the original strings only on whitespace
(`AndroidBenchmarkManager.kt:110-125`). As a result, reference `"The world is different."` and
hypothesis `"the world is different"` receive zero word errors but the UI difference list reports
case and punctuation substitutions. This makes the quality explanation contradict its score.

Use the same shared normalization/token stream for scoring and alignment, while retaining raw text
separately for display. Add regressions covering case, punctuation, Unicode compatibility forms,
and repeated whitespace, and assert that non-match edit count equals `score.wordErrors`.

## Confirmed behavior and evidence

- The committed WAV hash is
  `2d96f4537a3b87c2f813ecf7ec85fef16e9148b041913e3901700fee73862f97`, its size is 1,192,044
  bytes, and file inspection identifies RIFF PCM, 16-bit, mono, 16 kHz. These match the manifest
  and `docs/audio-assets.md`; the manifest provides the public-domain rights statement and source.
- `AssetDemoAudioRepository` validates the manifest-declared SHA-256 before decoding, then validates
  PCM format and duration. Russian and mixed-language omissions are not exposed as runnable
  samples.
- Recognition is not synthesized from the reference: `runOne` constructs `recognizedText` only
  from `engine.transcribe(...).segments`, and `runQualityDemo` passes that engine output to both
  scoring and its result API (`AndroidBenchmarkManager.kt:91-101`, `:52-54`).
- The monotonic interval starts immediately before `transcribe` and ends immediately after it;
  model load and unload are outside that interval. The injected mutex is required and encloses
  load, inference, and unload. Selected models execute sequentially. Compatibility filtering
  includes device ID, exact model hash, sample ID, and full inference configuration.
- The supplied evidence reports 7/7 focused JVM tests and `:app:ktlintCheck` passing. This reviewer
  did not rerun Gradle, native compilation, or inference. The absence of a real device benchmark is
  correctly documented, so no speed, thermal, or memory-performance result is established.

## Scope

Reviewed `docs/briefs/F1-benchmark.md`, `docs/reports/F1-benchmark.md`, `.review/F1.diff`, the
benchmark/quality core contracts, the C/E lifecycle reports needed to interpret the shared mutex
and engine dispatcher, and the committed demo manifest/reference/WAV metadata. This was a bounded
F1 review; no source, Gradle, native, or git mutation was performed.
