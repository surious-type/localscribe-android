# Frozen core contracts

These contracts are the shared boundary between LocalScribe's pure Kotlin domain and Android
adapters. Task A owns them; later tasks must request changes through the lead rather than editing
them concurrently. Times are milliseconds. `*EpochMs` values use the Unix epoch; audio times are
relative to the start of the source unless a field explicitly says otherwise.

## Persistence models

The source metadata record is:

```kotlin
data class AudioSource(
    val id: String,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val mimeType: String,
)
```

`TranscriptionJob` freezes `sourceId`, `TranscriptionConfig`, and the verified `modelHash`. Its
status is one of `PENDING`, `RUNNING`, `PAUSED`, `CANCELLED`, `FAILED`, or `COMPLETED`.
`TranscriptionChunk` stores `startMs`, `endMs`, its attempt counter, and creation/start/completion
timestamps. Chunk status is `PENDING`, `PROCESSING`, `PAUSED`, `CANCELLED`, `FAILED`, or
`COMPLETED`. `TranscriptSegment` stores `chunkId`, absolute source start/end times, and raw text.
Raw engine segments are never overwritten by assembly.

`InferenceConfig` freezes thread count, optional language, translation mode, temperature, and an
optional verified VAD model identity/configuration.
`TranscriptionConfig` adds the selected model ID, window/overlap sizes, and bounded context size.
Default windows are 90 seconds with a 3 second overlap. Model identity is split deliberately:
`modelId` is the catalog identity and `modelHash` is the exact immutable artifact used by a job or
benchmark.

`ModelDescriptor` contains the public download URL, SHA-256, download and installed byte counts,
language tags, version, and quality tier. `InstalledModel` records its private app path, actual
SHA-256 and verification timestamp. `ModelKind` separates `TRANSCRIPTION` from `VAD`; catalog and
recommendation UIs must never offer a VAD artifact as a transcription model. No model is an APK
asset.

`BenchmarkRecord` is keyed independently and records the device, model ID and hash, exact
inference config, durations, RTF, realtime multiplier, optional thermal state and approximate peak
memory. `DemoSample` includes its reference transcript and license provenance. `AppRelease` and
its download state are entirely independent from model catalog and download types.

## Ports

The compiled signatures in `core/ports` are authoritative. Their behavioral requirements are:

- `AudioRepository` observes MediaStore/imported sources and owns persisted URI permission state.
- `AudioPipeline.readWindow(source, startMs, endMs): FloatArray` returns bounded mono 16 kHz PCM
  for the half-open interval `[startMs, endMs)`.
- `TranscriptionEngine.loadModel`, `transcribe`, and `unloadModel` own one native context per
  engine instance. `transcribe` must respond both to coroutine cancellation and the supplied
  `CancellationSignal`; cancellation returns no partial result. `loadModel` accepts the verified
  optional VAD model, while `transcribe` accepts `InferenceConfig` on every window so the
  coordinator can reduce thread count in response to thermal state without reloading the model.
- `TranscriptionRepository.createJob` persists the job and complete plan atomically.
  `claimNextChunk` atomically chooses a pending chunk, changes it to `PROCESSING`, increments the
  attempt, and returns the updated row. `completeChunk` atomically inserts raw segments and marks
  the chunk `COMPLETED`; retrying an already committed completion is idempotent.
  `recoverInterrupted` changes `PROCESSING` chunks to `PENDING` and `RUNNING` jobs to `PAUSED` in
  one transaction. Job control methods enforce `JobTransitions`.
- `ModelCatalogRepository`, `ModelDownloadManager`, and `InstalledModelRepository` are separate
  ports. A download is registered as installed only after SHA-256 verification and atomic rename.
- `AppUpdateManager` does not refer to any model type or model port. The Play implementation emits
  `DISABLED`; the GitHub implementation may check, download, verify, and hand off to the system
  installer.
- `BenchmarkManager` measures real inference with a fixed sample. `DemoAudioRepository` exposes
  only samples with recorded compatible provenance and returns mono 16 kHz PCM.

## Deterministic domain APIs for Task B

Task B implements these signatures under
`io.github.surioustype.localscribe.core.domain`. It may add private helpers but must keep these
public entry points:

```kotlin
class ChunkPlanner {
    fun plan(
        jobId: String,
        modelId: String,
        sourceDurationMs: Long,
        config: TranscriptionConfig,
        createdAtEpochMs: Long,
    ): List<TranscriptionChunk>
}

class TranscriptAssembler {
    fun assemble(
        chunks: List<TranscriptionChunk>,
        rawSegments: List<TranscriptSegment>,
    ): List<TranscriptSegment>
}

class TranscriptContext {
    fun build(previousSegments: List<TranscriptSegment>, maxCharacters: Int): String?
}

object QualityMetrics {
    fun calculate(reference: String, hypothesis: String): QualityScore
}

data class BenchmarkTiming(
    val realTimeFactor: Double,
    val realTimeMultiplier: Double,
)

object BenchmarkCalculator {
    fun calculate(audioDurationMs: Long, processingDurationMs: Long): BenchmarkTiming
}

class RecommendationEngine {
    fun recommend(
        catalog: List<ModelDescriptor>,
        benchmarks: List<BenchmarkRecord>,
        hardwareProfile: HardwareProfile,
    ): List<ModelRecommendation>
}

object JobTransitions {
    fun canTransition(from: JobStatus, to: JobStatus): Boolean
    fun canTransition(from: ChunkStatus, to: ChunkStatus): Boolean
}

class ExportManager {
    fun export(
        source: AudioSource,
        segments: List<TranscriptSegment>,
        format: ExportFormat,
    ): ExportDocument
}

// AppVersion keeps its public `value: String`, implements Comparable<AppVersion>, and rejects
// anything outside v?MAJOR.MINOR.PATCH with an optional SemVer prerelease suffix.
class ReleaseParser {
    fun parse(releaseJson: String, checksumText: String): AppRelease
}
```

Algorithm rules: chunk plans cover the entire non-empty source and never produce zero-length
windows; assembly orders by absolute time and deduplicates only inside actual overlap; context is
bounded without splitting Unicode code points; WER/CER normalize consistently and define empty
reference behavior; benchmark duration inputs must be positive; recommendations never download;
state transitions are explicit and terminal states remain terminal; exports are deterministic and
SRT/VTT use original timestamps; versions compare by SemVer precedence and release parsing rejects
missing, ambiguous, or checksum-less APK assets.

Persisted and adapter-facing failures use `DomainFailure` with a stable `FailureCode`; exceptions
and platform/native messages must be translated at the boundary, and diagnostics must not contain
transcript text or sensitive source paths.
