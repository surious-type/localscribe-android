# Task B fix review — round 1

Scope: review only the changes recorded in `/tmp/localscribe-review-B-fix1.diff` against the three blocking findings in `B-review.md`. No production or test code was edited during this review.

## Findings

### B1 — addressed

`TranscriptAssembler` now carries each normalized token together with its originating `TranscriptSegment` and accepts a suffix/prefix match only when every paired segment overlaps, allowing a documented 250 ms timestamp tolerance for ASR jitter. This preserves phrases repeated at distinct segment times even when both fall in the chunk-window overlap. The added regression test exercises the prior failure: `"yes"` at 8,000–8,500 ms is retained alongside `"yes"` at 9,500–10,000 ms.

The tolerance is bounded, overflow-safe, and applied before both exact and fuzzy text matching. Segment timestamps are the temporal resolution exposed by the frozen `TranscriptSegment` contract, so this is the available segment-level correspondence without changing the public API.

### B2 — addressed

`ReleaseParser` now binds both the APK and checksum URL to HTTPS `github.com`, repository `surious-type/localscribe-android`, the parsed release tag, and the exact expected asset filename. It also rejects user info, query strings, fragments, and non-default HTTPS ports. The raw-path comparison rejects a wrong repository, tag, filename/path encoding variant, or arbitrary HTTPS host. Added tests cover arbitrary HTTPS origin, wrong repository, wrong APK tag, and wrong checksum tag.

### B3 — addressed

`RecommendationEngine` now uses the positive measured peak-memory value from the selected matching benchmark for the available-memory gate. If the selected benchmark has no usable peak value, it uses the documented conservative fallback of twice the installed bytes, with saturation at `Long.MAX_VALUE`. Memory insufficiency is evaluated before RTF level selection. Added tests cover a fast model whose measured peak exceeds available memory and a missing-peak fallback case.

## Fix-only regression review

No new blocking regression was identified in the scoped diff. Existing public method signatures remain exactly those in `docs/contracts.md`: `assemble`, `parse`, and `recommend` were not changed. The release URL validation intentionally permits only the default HTTPS port (or no explicit port), and transcript timestamp tolerance is documented locally as 250 ms.

## Verification evidence reviewed

I did not rerun the suite, as requested. The recorded round-1 commands in `B-domain.md` report successful `:core:test` and `:core:ktlintCheck` runs after formatting. The current JUnit XML artifacts list 38 tests with zero failures/errors across the eight domain suites (5 + 5 + 4 + 3 + 7 + 3 + 5 + 6), timestamped 2026-09-18T05:08Z. The current ktlint main and test check report files are empty, consistent with successful checks. The fix report's stated 38-test and ktlint-pass result is therefore supported by the available artifacts.
