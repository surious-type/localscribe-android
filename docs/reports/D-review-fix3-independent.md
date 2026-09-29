# Task D fix round 3 — independent review

## Verdict

Fix round 3 remains open with one medium-severity direct concurrency regression in installed-revision reuse. The updater serialization closes the overlapping-check stale-release finding. The sequential delete/reinstall case is fixed, but its validation uses the wrong synchronization boundary.

## Open finding

### Medium — installed-revision validation blocks unrelated cancellation but still races deletion

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:55-90,93-105,103-116`; `app/src/main/kotlin/io/github/surioustype/localscribe/models/InstalledModelFileStore.kt:25-35`
- `enqueue()` calls `isRevisionInstalled()` while holding the global `jobsMutex`. That method performs a Room lookup, canonical-file checks, and a full SHA-256 read; for a 1.6 GB model this can hold the mutex for a substantial time. During that interval, `cancel()` and `retry()` for every other model block on the same mutex, so an installed-model probe can delay the explicit cancellation contract for an unrelated active transfer.
- The long-held mutex does not protect the operation that actually races it: deletion uses the graph `executionMutex`, not `jobsMutex`. A delete can remove the row/file after the reuse lookup or while hashing. Depending on timing, enqueue can throw on the vanished file or finish hashing an already unlinked file and publish `COMPLETED` after deletion removed all metadata and artifacts. The added regression is sequential and cannot expose this interleaving.
- Action: make exact-row/file validation and descriptor deletion share one artifact/execution synchronization boundary, while keeping the global jobs mutex limited to short ownership-map changes (or use per-descriptor ownership). Treat file disappearance/I/O failure during validation as “not installed” and continue to a transfer. Add a gated delete-versus-enqueue regression and a test proving a large/blocked reuse validation does not prevent cancelling another model download.

## Closed finding

- **Overlapping update checks and download selection — closed.** `checkForUpdate()` serializes the entire check/commit sequence, and `downloadAvailableUpdate()` selects a release under the same mutex while requiring visible `AVAILABLE` state. The gated tests cover ordered overlapping checks and a download request waiting for a final failed check, preventing a stale APK request.

## Evidence and limitations

- Supplied evidence reports the expected three-test RED state, 14/14 focused GREEN, then final GitHub 31/31 and Play 16/16 passes with both variants compiled. The captured `.review/D-fix3.diff` was used as the authoritative pre-format change set.
- Per assignment, I did not run builds, tests, or formatting and made no production/source or git changes. The old integrity-load finding remains withdrawn.
