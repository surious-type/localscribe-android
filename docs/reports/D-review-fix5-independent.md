# Task D fix round 5 — independent review

## Verdict

Fix round 5 remains open. The reuse-validation publication race is closed, and
the pre-start cancellation path no longer drops the following enqueue. However,
the cancelled former reservation can still overwrite the replacement owner's
terminal status.

## Finding

### Medium — a cancelled former owner can publish stale `CANCELLED` after its replacement

- Path: `SecureModelDownloadManager.enqueue`, specifically the initial
  `jobsMutex.withLock`, the unconditional `CancellationException` status update,
  and the ownership-guarded `finally` cleanup.
- After `job.start()` activates the first job, that job can begin and suspend while
  trying to acquire `jobsMutex` before it marks its reservation `started`. A
  concurrent `cancel(modelId)` can acquire the mutex first, cancel the job, publish
  `CANCELLED`, and remove its not-yet-started reservation. A second enqueue can then
  install and run a replacement. When the first coroutine resumes from its cancelled
  mutex acquisition, its catch block unconditionally publishes `CANCELLED`, even
  though `jobs[modelId]` now belongs to the replacement. The `finally` block protects
  the replacement reservation from removal, but no equivalent ownership check
  protects the status update. If the replacement reaches `COMPLETED` before that old
  catch executes, the observable terminal status becomes stale `CANCELLED` despite a
  successful transfer.
- The new regression exercises cancellation before the test scheduler runs the first
  lazy coroutine at all. In that ordering the old coroutine body never reaches its
  catch, so it cannot cover cancellation while the coroutine has entered its body but
  is still waiting to mark itself started.
- Action: publish cancellation from the coroutine only while confirming under
  `jobsMutex` that `ownedJob` is still the current reservation, and remove that owner
  in the same critical section. Preserve the explicit pre-start cancellation
  publication in `cancel`. Add a deterministic regression that suspends the first
  activated job before its `started` transition, lets cancel remove it and a
  replacement finish, then resumes the old cancellation path and verifies the
  replacement's terminal status remains `COMPLETED`.

## Closed behavior and evidence

- Installed-revision lookup, file validation, checksum, and reuse `COMPLETED`
  publication now share one `modelMutex` critical section with deletion. The gated
  unlock regression directly detects publication after mutex release, so the first
  fix4 residual is closed.
- Pre-start cancellation now publishes `CANCELLED` and removes the unstarted
  reservation while holding `jobsMutex`, allowing the following enqueue to claim the
  descriptor. Ownership-guarded cleanup prevents the old job from removing the new
  reservation. This closes the dropped-enqueue portion of the second fix4 residual.
- The supplied XML records 8 concurrency tests and 10 manager tests with zero
  failures or errors for each of GitHub and Play, matching the reported 18/18 per
  flavor. The supplied formatting log records successful app ktlint format/check
  tasks.
- Per assignment, I did not run builds, tests, formatting, or edit production/test
  source. Updater, native, and other prior Task D scopes were not re-reviewed.
