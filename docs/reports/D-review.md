# Task D independent review

## Actionable findings

### High — a refreshed catalog artifact cannot replace an installed descriptor

- Paths: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:93-111`, `app/src/main/kotlin/io/github/surioustype/localscribe/data/Entities.kt:110-119`, `app/src/main/kotlin/io/github/surioustype/localscribe/data/Daos.kt:126-130`
- Downloads use a hash-qualified primary key (`descriptorId-sha256`), while the Room table enforces a unique `descriptorId`. If a remote catalog changes the hash for an already installed model ID, registering the newly verified artifact uses a new primary key that conflicts with the existing unique descriptor row. The download is reported failed and its newly published file is deleted, so the independently updateable catalog cannot actually update an installed model. This path is absent from the tests.
- Action: define replacement/retention semantics with the persistence owner, then implement them atomically. At minimum test `old hash installed -> refreshed descriptor with new hash -> download/register`. Because jobs freeze `modelHash`, do not delete the old immutable artifact while a job or benchmark can still reference it.

### Medium — a failed automatic check becomes `UP_TO_DATE` after process restart

- Path: `app/src/github/kotlin/io/github/surioustype/localscribe/updates/GithubAppUpdateManager.kt:50-53, 63-74, 85-90`
- The manager records `lastCheckEpochMs` before either network request succeeds. Its constructor initializes any enabled manager as `UP_TO_DATE`, and a cached automatic check returns that in-memory state. After a failed check followed by process restart inside the 24-hour interval, the persisted failed-attempt timestamp suppresses the request and reports `UP_TO_DATE`. This directly violates the requirement that failures never become “up to date.” A future timestamp caused by a wall-clock rollback also suppresses checks beyond the bounded interval because `now - lastCheck` remains negative.
- Action: persist the cache timestamp only after a successful parsed check, or persist enough outcome data to reconstruct `FAILED`/`AVAILABLE` accurately. Treat future timestamps as stale. Add restart-style tests that create a second manager from the same store after a failed request and after a future timestamp.

### Medium — model download coordination is not concurrency-safe

- Path: `app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:42-53, 118-153`
- `jobs` is an unsynchronized mutable map and status publication is a read-modify-write assignment (`downloads.value = downloads.value + ...`). Concurrent callers can both pass the active-job check for the same model and write the same `.part` file, or downloads for different models can overwrite each other's state snapshots. The constructor accepts an arbitrary long-lived scope and the port does not constrain callers to one dispatcher, so thread confinement cannot be assumed.
- Action: protect job creation/removal and per-model file ownership with a mutex, and use atomic `MutableStateFlow.update` for status changes. Add concurrent enqueue tests for the same ID and different IDs, asserting one network transfer per ID and preservation of every status entry.

### Medium — the real APK package/signing verifier has no regression tests

- Path: `app/src/github/kotlin/io/github/surioustype/localscribe/updates/AndroidGithubUpdateSupport.kt:30-78`; test gap in `app/src/testGithub/kotlin/io/github/surioustype/localscribe/updates/GithubAppUpdateManagerTest.kt:49-60`
- The updater test only injects a Boolean fake `UpdatePlatform`; it does not exercise package-name, version-name, increasing-versionCode, current-signer, rotated-lineage, missing-signature, or multiple-signer decisions. These checks are the authenticity boundary because the checksum asset comes from the same release. A regression in `AndroidUpdatePlatform` would still leave all reported tests green.
- Action: extract signer/package comparison into a pure input verifier and cover accept/reject matrices, leaving only `PackageManager` extraction and `FileProvider` URI creation in the Android adapter. Include conservative rejection for unavailable or ambiguous lineage.

## Spec compliance observed

- Catalog parsing retains the bundled catalog on refresh failure and bounds payload size, entry count, IDs, hashes, languages, and artifact sizes.
- Model transfer validates resume ranges, restarts on ignored ranges, bounds received bytes, checks cancellation during streaming/hashing, fsyncs, verifies exact size/SHA-256, and atomically renames before registration.
- The accepted E load boundary rechecks file existence, exact length, and SHA-256 in `WhisperTranscriptionEngine.verifyModel()` before calling the native bridge (`WhisperTranscriptionEngine.kt:40-49,137-158`). Modified or truncated model contents therefore do not reach native loading.
- GitHub release parsing binds the stable tag to exact APK and `.apk.sha256` names and repository paths; APK bytes are length/hash checked before platform inspection.
- GitHub-only install permission, FileProvider handoff, unknown-source settings routing, and the disabled Play implementation match the flavor requirements.
- The supplied report records GitHub 14/14, Play 11/11, both variant compilations, and ktlint passing.

## Review limitations

- Per assignment, I did not run Gradle or modify production/source files.
- Review was static against `.review/D.diff` and the current scoped source/contracts. Android framework behavior was not device-tested.
- Withdrawn finding: the original high-severity claim that model integrity verification was absent from the load path was incorrect because E performs the content checks at the engine boundary. `InstalledModelFileStore` still adds canonical private-directory enforcement and mutex-protected deletion, but its construction and graph wiring are an explicitly documented F2 integration dependency (`docs/progress.md:82,95`), not a Task D defect. F2 should retain an integration test for canonical-path rejection and shared-mutex deletion wiring.
- Release workflow checksum handling was excluded because Task G already fixed the asset name to `.apk.sha256`.
