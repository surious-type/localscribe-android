# D review fix round 1 — integration decisions

Read D-review.md; its integrity-load finding was withdrawn because E already verifies actual bytes. Fix the four remaining findings, without reworking accepted native code.

## Model revisions

Retain immutable artifact rows/files by descriptor plus SHA-256. Latest installed revision is the default for new jobs; persisted jobs resolve their frozen hash, including VAD. Add a backwards-compatible repository lookup overload accepting descriptor ID and SHA, with a default implementation checking the current model for existing test adapters. Room overrides it with an exact query. Keep the existing single-argument lookup as latest revision with deterministic ordering.

Migrate Room v2 to v3 by replacing the descriptor-only unique index with a unique descriptor/hash index; preserve every existing row and migration1→2. Add a migration and old/new revision persistence regression. No destructive fallback. Register a newly downloaded revision atomically without removing old immutable files. Benchmark records remain keyed by actual hash.

Model deletion remains graph-mutex protected and descriptor-scoped: enumerate all revisions for that descriptor, remove their private files, then metadata. The injected guard must reject deletion while any resumable job references that descriptor or a benchmark/session is active. F2 owns that guard/wiring. Do not silently orphan retained files.

Lead will assign data/core changes to the D fix implementer after C control changes stabilize. The two coordinator resolution calls must use frozen transcription/VAD hashes; coordinate those exact call-site edits with C owner. Other C control code is outside D ownership.

## Other findings

Persist/reconstruct a truthful update-check outcome; never use a failed-attempt timestamp to report up-to-date after restart, and treat future timestamps as stale. Preserve the distinction between available/unknown/up-to-date states across cache use.

Serialize downloader ownership and use atomic state updates; cover simultaneous enqueue for one model and separate models. Extract pure APK identity/signer policy and test authentic package/version/single-signer/lineage and conservative rejection matrices. Keep PackageManager and FileProvider at the adapter boundary.

Run focused tests first; request exclusive Gradle/device window. Save pre-fix snapshot under .review/D-fix1-base and update D report. No keys, publication, Git mutation, or native inference rerun.
