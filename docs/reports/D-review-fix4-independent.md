# Task D fix round 4 — independent review

## Verdict

Fix round 4 remains open. Moving installed-revision I/O out of `jobsMutex` closes
the unrelated-cancellation blockage, but the reuse publication is still outside
the deletion mutex. The lazy reservation used to shorten `jobsMutex` also adds a
same-model cancellation/enqueue race.

## Findings

### Medium — deletion can still linearize between reuse validation and `COMPLETED`

- Paths: `SecureModelDownloadManager.isRevisionInstalled` and the installed-reuse
  branch in `enqueue`; `InstalledModelFileStore.delete`; the delete/reuse regression
  in `ModelDownloadConcurrencyTest`.
- `isRevisionInstalled()` holds `modelMutex` through the exact-row lookup, path and
  length checks, and checksum, but returns and unlocks before `enqueue()` calls
  `update(COMPLETED)`. On a multithreaded dispatcher, a waiting deletion can acquire
  the mutex after validation, delete the file and metadata, and then the enqueue job
  can publish `COMPLETED` for the removed revision. This is the stale publication
  interleaving identified in fix round 3.
- The new test forces deletion to win before validation. It proves that ordering
  triggers a transfer, but it does not gate the boundary between successful
  validation and reuse publication, so it cannot expose this remaining ordering.
- Action: keep successful validation and the reuse `COMPLETED` update in one
  `modelMutex` critical section (return a result only after publication), while
  preserving cancellation checks during hashing. Add a regression that lets the
  reuse validation win, queues deletion before completion publication, and asserts
  that the operations have a defined mutex order rather than publishing stale state.

### Medium — a cancelled lazy reservation can drop status and a concurrent enqueue

- Path: `SecureModelDownloadManager.enqueue` around the lazy `scope.launch`, jobs-map
  insertion, out-of-lock `job.start()`, and failed-start cleanup.
- After the lazy job is inserted and `jobsMutex` is released, `cancel(modelId)` can
  cancel it before `job.start()`. A cancelled lazy coroutine never enters its body,
  so its `CancellationException` handler does not publish `CANCELLED`. Before the
  failed-start cleanup reacquires `jobsMutex`, another `enqueue(modelId)` sees the
  cancelled reservation and returns; cleanup then removes it, leaving no active job
  and no transfer. An older terminal status can remain visible throughout.
- Action: make reservation activation and ownership visible as one state transition,
  or explicitly handle cancellation-before-start without a gap in which another
  enqueue is discarded. Add a deterministic same-model enqueue/cancel/enqueue
  regression for the pre-start boundary and assert both terminal status and transfer
  ownership.

## Closed behavior and evidence

- Installed-revision repository/file/checksum work no longer runs under `jobsMutex`,
  so a blocked reuse probe does not prevent `cancel()` from acquiring ownership state
  for an unrelated transfer. The gated regression directly covers this property.
- File disappearance and ordinary I/O failure during validation are converted to an
  absent revision while coroutine cancellation is rethrown.
- Network final move, registration, and `COMPLETED` publication share `modelMutex`
  with deletion; graph wiring of the shared mutex is explicitly outside this review.
- Per assignment, I did not run tests, formatting, builds, or Git commands. Test
  execution was still owned by the formatter/root coordination window, so this
  verdict is based on source and regression review rather than duplicated execution.
