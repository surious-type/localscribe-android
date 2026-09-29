# Task D review fix round 1

## Resolved findings

- **Immutable model revisions:** Room schema v3 replaces the descriptor-only unique index with a unique `(descriptorId, sha256)` index and preserves existing rows. The repository exposes a backwards-compatible exact-hash overload; Room implements it with an exact query. The one-argument query selects the latest row by `installedAtEpochMs DESC`, `verifiedAtEpochMs DESC`, `sha256 DESC`, and `id DESC`. The two coordinator resolution sites now use the persisted primary and VAD hashes.
- **Descriptor deletion:** `InstalledModelFileStore` holds the graph execution mutex while checking the injected active-use guard, enumerating every retained revision, validating all canonical paths, deleting every artifact, and removing all descriptor metadata. F2 must make the injected guard cover resumable jobs and active benchmark/session use.
- **Truthful update cache:** update availability now includes `UNKNOWN`. The preferences store persists complete success, available-release, and failure state. Cached failures remain failures, available releases remain downloadable after restart, future timestamps are stale, and manual checks still bypass the interval.
- **Downloader ownership:** enqueue/retry/cancel ownership is serialized with a mutex and download `StateFlow` mutations are atomic. Simultaneous enqueue calls for one model create one transfer, while separate model transfers preserve independent statuses.
- **APK policy:** package, version, version-code, signer, and rotation-lineage decisions are pure and unit tested. The Android adapter has explicit pre-28 and 28+ extraction paths and passes API 26 lint.

## Verification

- Focused core/GitHub JVM run: 72 tests passed, 0 failed, 0 skipped. Relevant app counts were 14 model/catalog, 11 updater/policy, and 8 coordinator tests.
- Focused Play JVM run: 14 model/catalog tests passed, 0 failed, 0 skipped; both GitHub and Play factories compiled.
- Focused Room device run through `EngineTestRunner`: 4/4 passed on `emulator-5554`.
- Final updater/policy run: 11/11 passed after the cached-release restart regression.
- `staticAnalysis` and `:app:lintGithubDebug` passed; the lint report contains no API compatibility errors.
- A trailing-whitespace scan over 27 Task D files passed with no findings.
