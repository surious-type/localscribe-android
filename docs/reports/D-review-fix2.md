# Task D review fix round 2

## Lifecycle fixes

- `SecureModelDownloadManager` creates a lazy job while holding its ownership mutex, stores that exact job, and starts it only after releasing the mutex. Non-cancellable terminal cleanup removes the entry only when the map still contains the same job identity and records the completed generation SHA. A cancelled-before-start job is also removed by identity. Late callers for that completed SHA are suppressed, a new catalog SHA can enqueue normally, and explicit retry clears the completed marker.
- `GithubAppUpdateManager` does not make a restored release downloadable until `checkForUpdate` binds it to the running version. A fresh cached `AVAILABLE` result is retained only when its release is newer; an equal or older release becomes `UP_TO_DATE`. Every check clears the prior in-memory release first, so disabled, cached non-available, up-to-date, malformed-cache, and failed outcomes cannot start a stale download.

## Regression coverage

- A revision-A download completes on an immediate dispatcher, the catalog changes the same descriptor to revision B, a normal enqueue transfers B, and both immutable installed rows remain.
- A cached `AVAILABLE` v1.1.0 result becomes `UP_TO_DATE` for running v1.1.0 and v1.2.0 and cannot be downloaded.
- An available release followed by a failed forced check leaves `FAILED`, rejects `downloadAvailableUpdate`, and makes no APK request.

## Verification

- RED: the two focused classes ran 11 tests against unchanged production code and produced the three expected failures: blocked revision B, stale cached availability, and stale release after failed force-check.
- GREEN: the same 11 tests passed after the production changes. One intermediate run exposed two transfers from late concurrent callers; completed-generation deduplication corrected it before final verification.
- Final GitHub model/updater run: 28/28 passed (2 catalog, 3 ownership/concurrency, 10 model download/file-store, 5 APK policy, 8 updater), with 0 failures, 0 errors, and 0 skipped.
- Final Play model run: 15/15 passed (2 catalog, 3 ownership/concurrency, 10 model download/file-store), with 0 failures, 0 errors, and 0 skipped. The Play variant compiled the shared downloader change.
- The new app formatting gate was intentionally not invoked in this window; its owner and lead are verifying that gate separately.
