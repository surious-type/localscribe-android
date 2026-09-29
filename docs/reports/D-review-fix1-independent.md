# Task D fix round 1 — independent review

## Verdict

Fix round 1 remains open because two medium-severity lifecycle regressions remain. The Room revision design, exact frozen-hash resolution, APK identity policy, and the core concurrency primitives are otherwise implemented as authorized. The earlier integrity-load finding remains withdrawn: E verifies file existence, length, and SHA-256 before native loading, while F2 owns canonical-path/deletion wiring.

## Open findings

### Medium — completed ownership permanently blocks a later catalog revision

- Path: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:52-71,74-140`
- `enqueue()` now rejects any model ID present in `jobs`, but completed, failed, and cancelled jobs are never removed from that map. This closes the simultaneous-enqueue race, yet it also means a normal later `enqueue(modelId)` silently does nothing for the rest of the manager lifetime. In particular, after revision A completes and the catalog refreshes the same descriptor to revision B, the authorized immutable-retention flow cannot download B unless the caller happens to use the failure-oriented `retry()` API. The new concurrency tests only enqueue each ID once and therefore do not catch the regression.
- Action: release ownership when the matching job terminates while preserving compare-by-identity semantics so an old completion cannot remove a replacement job. Account for scopes whose dispatcher may start a coroutine immediately; assigning a lazy job under the mutex and then starting it is one safe pattern. Add a regression covering `complete revision A -> catalog changes hash -> enqueue same descriptor -> revision B transfers and both rows remain`.

### Medium — restored update availability is not rebound to the running version

- Path: `app/src/github/kotlin/io/github/surioustype/localscribe/updates/GithubAppUpdateManager.kt:50-60,65-76,84-99,103-106`
- A cached `AVAILABLE` state is returned unchanged inside the interval and copied into `currentRelease` without comparing its release version to the `currentVersion` argument. Preferences survive an app update, so after installing that cached release and restarting, the updater can still report the already-installed (or older) version as available and download it again; package/versionCode policy rejects it only after wasting the download. Separately, a failed forced check does not clear a previously cached/in-memory `currentRelease`, so `downloadAvailableUpdate()` can still download the stale release even while the public state is `FAILED`.
- Action: normalize cached availability against `currentVersion` before returning it and clear `currentRelease` whenever a check starts or ends without an available newer release. Add tests for restart with cached release `<= currentVersion` and for `AVAILABLE -> forced check fails -> downloadAvailableUpdate rejects`.

## Disposition of reviewed findings

- **Immutable model revisions — closed at the D boundary.** Schema v3 replaces descriptor-only uniqueness with `(descriptorId, sha256)`, migration 2→3 preserves rows, Room supplies deterministic latest and exact-hash queries, and both transcription and VAD coordinator lookups use the frozen hash. Device evidence covers migration and retained-revision lookup. Descriptor-wide file deletion is implemented; the documented resumable-job/benchmark guard and graph construction remain F2 integration work.
- **Truthful update cache — partially closed.** `UNKNOWN`, persisted success/failure/release state, restart restoration, future-timestamp invalidation, and manual bypass address the original false-`UP_TO_DATE` defect. The running-version and stale-`currentRelease` cases above remain open.
- **Downloader concurrency — partially closed.** The mutex prevents duplicate simultaneous ownership and `MutableStateFlow.update` preserves independent status entries. Terminal ownership cleanup remains open and directly blocks the new revision workflow.
- **APK package/signing policy — closed.** Package, version name, increasing version code, signer equality, conservative rotation lineage, missing signer, and ambiguous multi-signer cases are handled by a pure tested policy. The Android adapter has explicit pre-28 and 28+ extraction paths, and the supplied API 26 lint evidence is consistent with the implementation.

## Evidence and limitations

- Supplied evidence reports 72 focused JVM tests (39 core, 14 model/catalog, 11 updater/policy, 8 coordinator), 14 Play tests, both flavor compilations, static analysis, GitHub lint, 4/4 device migration/revision tests, and the final 11/11 updater run.
- Per assignment, I did not rerun Gradle, device tests, or lint, and I made no production/source or git changes. This was a static, scoped review of `.review/D-fix1.diff`, the authorized fix brief, current affected files, tests, schemas, and reports.
