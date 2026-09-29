# Task D — distribution implementation report

## Implemented components

- `JsonModelCatalogRepository(bundledCatalog, networkClient, remoteCatalogUrl)` starts from the bundled trusted JSON catalog and replaces its in-memory view only after a bounded, fully validated public HTTPS refresh. The default remote URL is `https://raw.githubusercontent.com/surious-type/localscribe-android/main/models/catalog.json`.
- `SecureModelDownloadManager(catalog, installedModels, modelDirectory, networkClient, scope, nowEpochMs, ioDispatcher)` provides progress, cancellation, retry and HTTP range resume. `ioDispatcher` defaults to `Dispatchers.IO`. It writes hash-qualified `.part` files, bounds received bytes, validates the exact `Content-Range`, checks cancellation during streaming and hashing, checks exact length and SHA-256, fsyncs, performs a same-filesystem atomic replacement, and registers metadata only after verification. Ownership is installed as a lazy job under a mutex and released by matching job identity in non-cancellable terminal cleanup. Late callers are suppressed only while an exact repository row still points to the expected canonical file with the declared length and SHA-256; deletion or corruption therefore permits a normal reinstall.
- `InstalledModelFileStore(modelDirectory, repository, executionMutex, isModelActive)` revalidates a model's private canonical path, size and hash before use. Descriptor deletion holds the graph-owned execution mutex while checking active use, validating every retained revision's canonical path, deleting every artifact, and removing descriptor metadata. `RoomInstalledModelRepository` remains persistence-only and does not acquire that mutex.
- `HttpsNetworkClient(connectTimeoutMs, readTimeoutMs)` permits HTTPS only, applies finite timeouts, follows at most five HTTPS redirects and exposes closeable streaming responses without tokens or request/user-data logging.
- `GithubAppUpdateManager(networkClient, checkStore, platform, updateDirectory, scope, nowEpochMs, automaticChecksEnabled, cacheIntervalMs, releaseParser, ioDispatcher)` uses the public latest-release REST endpoint; `ioDispatcher` defaults to `Dispatchers.IO`. Forced/manual checks bypass the interval. Automatic checks cache the complete truthful outcome, including an available release or failure, and reject future timestamps as stale. Cached availability is rebound to the running version before it becomes downloadable: an equal or older release reports `UP_TO_DATE`. Complete checks and download selection share one mutex, so state, cache, and the downloadable release commit in invocation order; a disabled, failed, or non-available result cannot coexist with a selectable stale release. A never-checked manager reports `UNKNOWN`.
- `AndroidUpdatePlatform(context, expectedPackageName = context.packageName)` extracts package identity at the Android boundary. The pure `ApkIdentityPolicy` rejects unreadable APKs, package/version-name mismatches, non-increasing version codes, absent or unrelated signers, and ambiguous rotation. It accepts an unchanged signer set or a single-signer rotation whose candidate lineage contains both signers. `requestInstall` opens Android's per-app unknown-source settings when required, otherwise launches the system package installer.
- `PlayAppUpdateManager()` always exposes `DISABLED`; the Play manifest does not request package-install permission.
- `SharedPreferencesUpdateCheckStore(context)` persists and reconstructs the full GitHub check outcome and available-release metadata.
- Both flavor source sets expose the same `createAppUpdateManager(context, scope, automaticChecksEnabled): AppUpdateManager` factory. Main graph code can call this symbol without referring to a flavor-only concrete class. The GitHub factory uses `context.cacheDir/updates`, shared preferences, the HTTPS client and Android verifier; the Play factory returns the disabled manager. The preference callback is read for each automatic check, while `force = true` is the manual immediate path.

The root publisher manifest is `models/catalog.json`; the first-launch copy is `app/src/main/assets/models/catalog.json`. Both include the exact documented whisper.cpp hashes and sizes. `small` is the multilingual balanced default candidate. Silero v6.2.0 is marked `VAD`, so it is excluded from transcription model selection and must be downloaded explicitly.

## Security and failure behavior

- Catalog entries reject unsafe identifiers, non-HTTPS/user-info URLs, malformed hashes, duplicate IDs, excessive entry/language counts and files outside the 1-byte to 4-GiB bound. Catalog responses are limited to 512 KiB.
- A partial model is never registered or exposed as installed. A server that ignores `Range` with `200` causes a clean truncate/restart; `206` is accepted only for the requested start and declared total.
- Model filenames include the descriptor ID and SHA-256. Existing loaded model files are not updated in place.
- Room schema v3 retains immutable rows under a unique `(descriptorId, sha256)` index. New jobs select the latest revision deterministically by `installedAtEpochMs DESC`, `verifiedAtEpochMs DESC`, `sha256 DESC`, then `id DESC`; persisted transcription and VAD jobs resolve their exact frozen hashes.
- APK JSON, checksum and APK bodies have independent size bounds. The APK is checked for exact declared length and SHA-256 before platform package and signer inspection.
- Network/check/verification failures produce `FAILED`, never `UP_TO_DATE`. Cancellation produces `CANCELLED` and never a ready URI.
- The catalog and GitHub release endpoints are public and contain no embedded access token. No signing keys were created.

## Integration notes

The graph must read the bundled catalog asset as UTF-8 and inject that lambda into `JsonModelCatalogRepository`. Use an application-owned models directory and cache `updates/` directory. Use a long-lived application `CoroutineScope`, not a screen scope. Inject the same graph execution `Mutex` into transcription, benchmark execution and `InstalledModelFileStore`. The deletion guard supplied by F2 must reject a descriptor referenced by any resumable job or active benchmark/session.

The remote catalog URL will return 404 until `models/catalog.json` is published in the public repository's `main` branch. This does not affect first launch because the bundled catalog is authoritative initially.

## Verification

Focused tests cover bundled and remote catalog validation, secure publication and resume, downloader ownership, descriptor-wide deletion, truthful update caching after restart, pure APK identity policy, exact frozen-hash resolution, and schema migration.

After `source .local-tools/env.sh`, the checked-in wrapper produced this evidence:

- `./gradlew :core:test :app:testGithubDebugUnitTest --tests 'io.github.surioustype.localscribe.models.*' --tests 'io.github.surioustype.localscribe.updates.*' --tests 'io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest'` — passed 72 tests: 39 core, 14 model/catalog, 11 updater/policy, and 8 coordinator; 0 failures, 0 skipped. The cached-release download-after-restart regression was subsequently added and passed with the complete 11-test updater/policy suite.
- `./gradlew :app:testPlayDebugUnitTest --tests 'io.github.surioustype.localscribe.models.*' :app:compileGithubDebugKotlin :app:compilePlayDebugKotlin staticAnalysis :app:lintGithubDebug` — passed 14 Play model/catalog tests, both flavor compiles, ktlint, and GitHub Android lint; 0 test failures or skips.
- `./gradlew :app:connectedGithubDebugAndroidTest -PtestRunner=io.github.surioustype.localscribe.audio.EngineTestRunner -Pandroid.testInstrumentationRunnerArguments.class=io.github.surioustype.localscribe.data.LocalScribeDatabaseMigrationTest,io.github.surioustype.localscribe.data.RoomInstalledModelRepositoryTest` — passed 4/4 on `emulator-5554`: v1→v2 preservation, v2→v3 revision-index migration, retained revision lookup, and descriptor-wide metadata removal.
- `./gradlew :app:testGithubDebugUnitTest --tests 'io.github.surioustype.localscribe.updates.*' staticAnalysis :app:lintGithubDebug` — passed the final 11 updater/policy tests and both analysis gates after cached-release restoration.
- D fix round 2 RED run — the two lifecycle test classes ran 11 tests and reproduced all 3 expected failures against unchanged production code.
- `./gradlew :app:testGithubDebugUnitTest --tests 'io.github.surioustype.localscribe.models.*' --tests 'io.github.surioustype.localscribe.updates.*' :app:testPlayDebugUnitTest --tests 'io.github.surioustype.localscribe.models.*'` — passed 28/28 GitHub model/updater tests and 15/15 Play model tests after terminal ownership and running-version cache fixes; 0 failures, errors, or skips.
- D fix round 3 RED run — the focused concurrency/updater classes ran 14 tests and reproduced all 3 expected failures: same-revision reinstall suppression, overlapping-check stale release, and download selection during a failing check.
- The same final focused command now passes 31/31 GitHub model/updater tests and 16/16 Play model tests after authoritative installed-file validation and serialized updater operations; 0 failures, errors, or skips.
- A trailing-whitespace scan over 27 Task D core, app, schema, test, and report files passed with no findings.

Android's package installer remains the final installation authority.
