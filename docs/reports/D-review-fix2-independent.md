# Task D fix round 2 — independent review

## Verdict

Fix round 2 remains open because two medium-severity direct regressions remain around the new lifecycle state. The exact terminal-job identity cleanup, immediate-dispatcher revision A→B path, sequential cache/version normalization, and sequential failed-check cleanup are otherwise correct. The old integrity-load finding remains withdrawn.

## Open findings

### Medium — completed-generation deduplication survives deletion and suppresses reinstall

- Path: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:49,55-70,76-83,97-105`
- A successful job permanently records `completedRevisions[modelId] = sha256`. The only removal is `retry()`; descriptor deletion occurs through `InstalledModelFileStore` and has no path to clear this map. After `download A -> delete descriptor A -> enqueue A again` in the same manager lifetime, `enqueue()` publishes `COMPLETED` and returns without checking the repository or filesystem, even though no installed row or model file remains. This makes the UI claim a deleted model is installed and prevents a normal reinstall. The new test covers a different hash B, which bypasses the stale marker.
- Action: suppress the same revision only when the exact `(descriptorId, sha256)` is still installed and valid, or connect deletion to ownership-state invalidation. Add a regression for successful download, descriptor-wide deletion, then normal enqueue of the same catalog revision; it must perform a second transfer and restore metadata/file state.

### Medium — overlapping checks can still leave a stale release downloadable after `FAILED`

- Path: `app/src/github/kotlin/io/github/surioustype/localscribe/updates/GithubAppUpdateManager.kt:65-100,114-117`
- Clearing `currentRelease` at check entry fixes the sequential case, but `checkForUpdate()` is not serialized. If checks A and B overlap, B can clear the release and later fail while A succeeds between those events. B then publishes the final visible `FAILED` state without clearing the release that A installed, so `downloadAvailableUpdate()` accepts it despite `FAILED`. The inverse completion order also lets older requests overwrite newer state/cache. The added regression runs the checks sequentially and does not exercise this interleaving.
- Action: serialize the complete check operation, or use a monotonically increasing request generation and commit `state`, cache, and `currentRelease` only for the current generation. Ensure every committed non-`AVAILABLE` outcome clears `currentRelease`. Add a gated two-check test whose final result is `FAILED` and assert that downloading is rejected and no APK request occurs.

## Closed portions

- **Terminal ownership cleanup:** the lazy job is stored before start, terminal cleanup is non-cancellable, and `jobs.remove(modelId, ownedJob)` prevents an older completion from removing a replacement. A completed revision releases ownership, and a later different catalog hash transfers and retains both immutable rows.
- **Sequential cached-version handling:** cached `AVAILABLE` releases equal to or older than the running version normalize to `UP_TO_DATE`, are removed from the returned state, and are not downloadable. A sequential forced failure also clears the prior in-memory release.

## Evidence and limitations

- Supplied evidence reports the expected three-test RED state, then 11/11 focused GREEN; final GitHub model/updater tests passed 28/28 and Play model tests passed 15/15. Both variants were recompiled according to the task handoff.
- Per assignment, I did not run builds or tests and made no production/source or git changes. This was a static scoped review of `.review/D-fix2.diff`, the current affected code/tests, the fix2 implementer report, and the fix1 independent findings.
