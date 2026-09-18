# Task B correctness review

## Blocking findings

### B1 — Transcript assembly can delete speech repeated at a distinct time inside the overlap

`TranscriptAssembler` first selects every segment that intersects the shared chunk window, then
matches only the flattened text suffix/prefix and removes that many words from the beginning of the
new chunk (`TranscriptAssembler.kt:51-64`). It never verifies that the previous and current
segments carrying the matched words overlap each other in time. For example, with chunk windows
`[0, 10_000)` and `[8_000, 18_000)`, a previous `"yes"` at `[8_000, 8_500)` and a new `"yes"` at
`[9_500, 10_000)` are two utterances at distinct source times, but both are admitted to the window
overlap and the second is removed. This violates the binding requirement to avoid discarding
repeated phrases at distinct times (`docs/briefs/B-domain.md:5`) and the timestamp-constrained merge
rule. The existing regression test only places repeated phrases in non-overlapping chunks
(`TranscriptAssemblerTest.kt:72-84`), so it does not exercise this failure. Deduplication needs
segment-level temporal correspondence (with a documented tolerance), plus a test for distinct
utterances within the same shared window.

### B2 — Release assets are trusted from any HTTPS origin

`ReleaseParser.requireSafeHttpsUrl` accepts any URL with an HTTPS scheme, non-empty host, no user
info, and no fragment (`ReleaseParser.kt:106-118`). Thus a release response can name
`https://attacker.example/localscribe-v1.2.3.apk` and a matching checksum URL and the parser will
return the attacker-controlled download as a valid `AppRelease`. The product fixes the update
source to `surious-type/localscribe-android` (`docs/product-spec.md:768-782`) and defines a secure
GitHub-release update chain (`docs/product-spec.md:838-855`), so origin and release-path binding are
part of the trust boundary. The unsafe-URL test covers only plain HTTP
(`ReleaseParserTest.kt:48-55`). Require the expected GitHub host/repository/tag/asset path (including
the checksum asset), and add rejection cases for an arbitrary HTTPS host and wrong repository or
tag.

### B3 — Recommendation memory gating ignores measured peak memory

The benchmark model records `approximatePeakMemoryBytes` (`BenchmarkModels.kt:12-24`), but
`RecommendationEngine` compares only the model file's `installedBytes` with currently available
memory (`RecommendationEngine.kt:32-38`). A 500 MB model with a measured 2 GB peak can therefore be
marked `RECOMMENDED` on a device with 1 GB available whenever its RTF is fast. Installed file size
is not an inference-memory measurement, and this contradicts the required memory constraint and
the product rule that recommendations use benchmark data plus available memory
(`docs/product-spec.md:438-468`). Prefer the matching benchmark's measured peak when present, with a
conservative documented fallback, and cover the case where peak memory exceeds available memory
although installed size does not.

## Reviewed without a blocking finding

The bounded Unicode context uses code-point offsets and does not split surrogate pairs.
WER/CER normalization is consistent and defines both empty-reference outcomes. Benchmark duration
inputs reject zero and negative values. Export timestamps, ordering, millisecond separators,
hour-plus values, Markdown escaping, and JSON escaping satisfy the frozen rules. `AppVersion`
implements the specified SemVer subset without integer overflow, and release parsing rejects
drafts, prereleases, missing or ambiguous named assets, invalid sizes/timestamps, and missing or
ambiguous checksums. Benchmark selection binds device, model ID, and immutable artifact hash; the
record retains its exact inference configuration. Recommendations exclude VAD artifacts and have no
download side effect.

The reported final `:core:test` run passed 34 tests. I did not rerun it because these findings follow
directly from uncovered input cases and the review request said a rerun was unnecessary absent a
concrete doubt about the reported run.
