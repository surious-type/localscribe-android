# Task D review fix round 4

## Model artifact synchronization

- `SecureModelDownloadManager` now reserves a lazy per-descriptor job while holding only
  its ownership mutex. Installed-revision lookup, Room access, path checks, and checksum
  validation run inside that job, so an unrelated `cancel` or `retry` is not delayed by
  probe I/O.
- Installed-revision validation and descriptor deletion use the same required
  `modelMutex`. A vanished or unreadable candidate file is treated as absent, while
  cancellation continues to be checked while the checksum is read.
- The network stream and partial-file verification stay outside `modelMutex`. Final
  atomic move, metadata registration, and `COMPLETED` publication form one
  non-cancellable `modelMutex` critical section, giving deletion and publication a
  single ordering point.

## Regression coverage

- A gated installed-revision lookup remains blocked while cancellation of another active
  transfer completes, proving probe I/O no longer holds the global ownership lock.
- Deletion is queued before a reuse probe on the shared mutex. The downloader observes
  the deletion, performs a new transfer, and only publishes `COMPLETED` with restored
  file and metadata, covering the delete-versus-reuse/publication race.

## Verification

- No Gradle, formatting, or Git command was run in this round because those are owned by
  the active formatter/root coordination window.
- Static review confirmed both model collaborators accept the same required `Mutex` and
  all local downloader test factories supply it.
