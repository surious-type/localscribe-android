# Task B report — deterministic domain logic

## Delivered

- Added deterministic fixed-window chunk planning with configurable duration/overlap, complete
  non-empty source coverage, zero-duration handling, validation, overflow-safe window ends and
  stable pending chunk records.
- Added chronological transcript assembly over absolute source timestamps. Suffix/prefix
  deduplication is confined to actual adjacent chunk overlap and handles exact, partial,
  punctuation/case-normalized and small token-level ASR differences while retaining repeated text
  at distinct times. Raw segment inputs remain unchanged.
- Added bounded previous-transcript context using Unicode code-point limits, plus Unicode-normalized
  WER/CER edit distance with defined empty-reference results.
- Added benchmark timing validation/calculation and benchmark-first model recommendations. Records
  must match the current device, model ID and immutable artifact hash; current-device memory limits
  override speed classifications; VAD artifacts are excluded from transcription choices. The
  engine is pure and has no model-download dependency or side effect.
- Added explicit job/chunk transition matrices. Cancelled, failed and completed states are terminal;
  paused work can only resume through the allowed pending/running path or be cancelled.
- Added deterministic UTF-8 TXT, Markdown, SRT, VTT and JSON export. Timed formats use sorted source
  timestamps directly, support hour-plus timestamps and exact millisecond separators, and reject
  negative, empty, reversed or out-of-source ranges. Markdown and JSON content are escaped.
- Added strict `AppVersion` validation/comparison for `v?MAJOR.MINOR.PATCH` and SemVer prerelease
  precedence. This was the sole lead-authorized frozen-model amendment.
- Added GitHub release tree parsing with `kotlinx-serialization-json`; JSON is never parsed with a
  regular expression. Drafts, prereleases, missing/ambiguous expected assets, unsafe URLs, invalid
  timestamps, non-positive sizes and absent/ambiguous SHA-256 checksums are rejected.

The public algorithm signatures match `docs/contracts.md` exactly. `BenchmarkCalculator` returns
the existing `core.model.BenchmarkTiming`; no duplicate domain model was introduced. The lead was
notified that Android consumers can compile against the completed entry points.

## TDD evidence

Environment for every Gradle command:

```shell
source /tmp/localscribe-tools/env.sh
```

Initial RED command after writing the behavior suite and before adding domain implementations:

```shell
./gradlew :core:test --no-daemon
```

Result: `BUILD FAILED in 9s` at `:core:compileTestKotlin`, with unresolved references for every new
domain entry point (`ChunkPlanner`, `TranscriptAssembler`, `TranscriptContext`, `QualityMetrics`,
`BenchmarkCalculator`, `RecommendationEngine`, `JobTransitions`, `ExportManager`, `ReleaseParser`)
and the missing `AppVersion.compareTo`. This confirmed that the tests exercised absent production
behavior. An earlier sandboxed invocation stopped before compilation on Gradle network-interface
detection and is not counted as RED evidence.

The later `ModelKind` contract amendment received its own focused RED run:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.BenchmarkAndRecommendationTest.VAD artifacts are excluded from transcription recommendations' \
  --no-daemon
```

Result before the filter: `1 test completed, 1 failed`; the assertion showed that a VAD artifact
was returned as a transcription recommendation. After filtering the catalog to
`ModelKind.TRANSCRIPTION`, the behavior is covered by the full suite.

During GREEN, the first full run executed 34 tests and isolated one Markdown export mismatch: a
filename period was unnecessarily escaped. The XML comparison showed expected `Meeting.wav` and
actual `Meeting\.wav`; removing the period from the global Markdown escape set made the focused
test pass:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.ExportManagerTest.markdown escapes formatting characters' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL in 9s`; one focused test passed.

Post-fix full GREEN run:

```shell
./gradlew :core:test --no-daemon
```

Result: `BUILD SUCCESSFUL in 3s`; all 34 tests passed. After formatting, the same complete command
was run from the final source tree and returned `BUILD SUCCESSFUL in 7s`; all four tasks executed
with no failures.

Formatting:

```shell
./gradlew :core:ktlintFormat --no-daemon
```

The first run auto-corrected normal layout and identified two test fixture lines over the
configured 100-character limit. After splitting those literals, the command returned
`BUILD SUCCESSFUL in 4s`; seven tasks completed with four executed and three up-to-date.

Final core style gate:

```shell
./gradlew :core:ktlintCheck --no-daemon
```

Result: `BUILD SUCCESSFUL in 8s`; all core main, test and Kotlin-script checks passed.

The repository aggregate was also invoked:

```shell
./gradlew staticAnalysis --no-daemon
```

It reached and passed `:core:ktlintMainSourceSetCheck`,
`:core:ktlintTestSourceSetCheck`, `:core:ktlintCheck` and core compilation. The aggregate then
failed in a concurrently owned app adapter at `:app:lintGithubDebug`: Android Lint reports
`AndroidAudioRepository.kt:165` calling `MediaMetadataRetriever().use`, whose implicit
`AutoCloseable` cast requires API 29 while the project minimum is API 26. Task B did not edit that
app-owned source.

## Coverage and constraints

The 34 unit tests cover chunk exact boundaries, zero/invalid/overflow durations, gap-free overlap,
deduplication variants and non-overlap repetition, Unicode-safe context, normalized WER/CER and
empty references, positive benchmark inputs, artifact/config identity selection, memory limits,
VAD exclusion, terminal and pause/cancel transitions, every export format, JSON escaping, SRT/VTT
milliseconds and hour-plus timecodes, invalid source timestamps, SemVer ordering, and GitHub
draft/prerelease/missing/ambiguous/unsafe release inputs.

The frozen `ChunkPlanner.plan` contract has no speech-boundary list or callback. It therefore emits
deterministic persisted fixed windows; overlap protects boundary speech and configured VAD operates
inside engine windows. The lead confirmed that speech-aware boundary snapping is an optional future
contract amendment and is not a blocker for stable crash recovery.

## Correctness review fixes — round 1

Before edits, the domain production sources, tests, authorized `UpdateModels.kt` change and this
report were copied to `/tmp/localscribe-b-domain-round1-base` for scoped re-review comparison.

### B1 — timestamp-corresponding overlap deduplication

Matched words now retain their source segment. A suffix/prefix candidate is eligible only when
each paired word's segments overlap within a documented 250 ms tolerance for ASR timestamp jitter.
Text repeated at distinct times inside the shared chunk window is retained.

RED:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.TranscriptAssemblerTest.repeated phrase at distinct times inside chunk overlap is retained' \
  --no-daemon
```

Result before the fix: `1 test completed, 1 failed`; `BUILD FAILED in 9s` at the new assertion.

GREEN:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.TranscriptAssemblerTest' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL in 10s`; the complete assembler test class passed.

### B2 — exact GitHub release asset origin

Both APK and checksum URLs must now use HTTPS on `github.com` and the exact raw path
`/surious-type/localscribe-android/releases/download/{tag}/{exactFileName}`. User info, queries,
fragments and non-HTTPS ports are rejected. Regression inputs cover a wrong HTTPS host, repository,
tag, and a checksum asset under the wrong tag.

RED:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.ReleaseParserTest.release assets must belong to the exact GitHub repository tag and file path' \
  --no-daemon
```

Result before the fix: `1 test completed, 1 failed`; `BUILD FAILED in 9s` because the arbitrary
HTTPS host was accepted.

GREEN:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.ReleaseParserTest' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL in 8s`; the complete release/version test class passed.

### B3 — measured inference memory gating

Recommendations now prefer a positive matching benchmark's `approximatePeakMemoryBytes`. When the
benchmark has no peak measurement, the conservative fallback reserves twice the installed model
size for weights and inference buffers, with saturating arithmetic. Either value exceeding current
available memory yields `MAY_BE_SLOW` before RTF classification.

RED:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.BenchmarkAndRecommendationTest.measured peak memory prevents recommendation when model file itself fits' \
  --no-daemon
```

Result before the fix: `1 test completed, 1 failed`; `BUILD FAILED in 8s` because the fast model
was marked `RECOMMENDED` despite its measured peak exceeding available memory.

GREEN:

```shell
./gradlew :core:test \
  --tests 'io.github.surioustype.localscribe.core.domain.BenchmarkAndRecommendationTest' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL in 10s`; all benchmark and recommendation cases passed, including the
measured-peak and conservative-fallback regressions.

### Round 1 final verification

```shell
./gradlew :core:ktlintFormat --no-daemon
./gradlew :core:test --no-daemon
./gradlew :core:ktlintCheck --no-daemon
```

Results: format `BUILD SUCCESSFUL in 8s`; full core tests `BUILD SUCCESSFUL in 9s`; style gate
`BUILD SUCCESSFUL in 14s`. The final XML reports contain 38 passing domain tests and zero failures.
