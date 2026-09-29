# Task D review fix round 3

## Lifecycle fixes

- Downloader deduplication no longer relies on a manager-lifetime completed marker. With no in-flight owner, enqueue resolves the exact descriptor/hash row and accepts it as installed only when it points to the expected canonical hash-qualified file, the metadata and file lengths match, and the actual file SHA-256 matches. Descriptor deletion removes the row and file, so a normal enqueue transfers and registers the same revision again. A corrupt same-path artifact is replaced atomically after verification.
- Job ownership remains one in-flight lazy job per descriptor. Non-cancellable terminal cleanup removes only the same job identity, preserving immediate-dispatcher and replacement-job safety.
- `GithubAppUpdateManager` serializes the complete check operation and `downloadAvailableUpdate` selection with one mutex. Each check commits its state, cache, and `currentRelease` before the next check or download selection proceeds. A final failure therefore clears release selection, and a download request issued during that check waits and then rejects.

## Regression coverage

- Download revision A, delete it through `InstalledModelFileStore`, enqueue A normally in the same manager, observe a second transfer, and verify restored metadata and file state.
- Start two gated forced checks while the first is in flight; allow the first to commit `AVAILABLE`, then the second to commit `FAILED`; downloading is rejected and no APK request occurs.
- Start a failing forced check from an available state and request download while it is gated; selection waits for the check, then rejects its final `FAILED` result without an APK request.

## Verification

- RED: 14 focused tests ran against unchanged production code; the three new regressions failed as expected.
- GREEN: the same 14 focused tests passed after the production changes.
- Final GitHub model/updater run: 31/31 passed (2 catalog, 4 ownership/concurrency, 10 model download/file-store, 5 APK policy, 10 updater), with 0 failures, 0 errors, and 0 skipped.
- Final Play model run: 16/16 passed (2 catalog, 4 ownership/concurrency, 10 model download/file-store), with 0 failures, 0 errors, and 0 skipped. The Play variant compiled the shared downloader changes.
- `.review/D-fix3.diff` is non-empty and contains the production, regression, and report changes relative to the pre-fix snapshot; a trailing-whitespace scan over the six changed D files passed.
- Migration, native, device, and formatting suites were not rerun because this round changed only the model downloader, GitHub updater, and their JVM regressions; root owns formatting verification separately.
