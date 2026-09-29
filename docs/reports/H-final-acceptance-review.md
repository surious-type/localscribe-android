# H final acceptance review

## Result

The fresh source-only acceptance review initially found two medium-severity audio-source integrity
defects. Both and their follow-up resource-lifecycle findings are closed. The final focused rereview
reported no actionable issue in the audio copy, cancellation, commit, rollback, shared URI owner,
or checkpoint-restart paths.

## Findings

1. `AndroidAudioRepository.relinkSource()` accepts a replacement using display name and duration
   only. A different recording with the same metadata can therefore reuse completed checkpoints
   and produce a mixed transcript. Relink must compare a durable collision-resistant content
   fingerprint, with regressions for the matching and mismatching cases.
2. `AndroidAudioRepository.removeSource()` releases the URI grant and deletes the private copy
   before Room can reject removal of a source referenced by a job. A rejected removal must leave
   the external resources intact, with an adapter-level regression.

## Closure

- Sources and jobs persist SHA-256 fingerprints in Room schema v5. The coordinator verifies source
  bytes before claiming work. A legacy or changed fingerprint atomically deletes stale segments,
  resets every chunk, and binds the job to the current bytes before transcription restarts.
- Relink accepts renamed byte-identical audio and rejects different content. Import, relink, refresh,
  verification and removal share a repository mutex. Room accounts for duplicate persisted URI
  owners before a grant is released.
- Private-copy streaming is cooperatively cancellable. Preparation remains cancellable; Room commit,
  rollback and post-commit cleanup use bounded non-cancellable sections. Partial files are deleted
  on every unsuccessful exit, including cancellation and unexpected stream-close failures.
- Removing a job-referenced source now fails before touching its private file or persisted grant.
- API 35 focused instrumentation passed 18/18: audio persistence/relink/removal, migrations 1→5,
  real migrated-checkpoint restart, and Room repository lifecycle/concurrency behavior.

## Review limits

The independent reviews were source-only. Root separately ran Gradle and API 35 instrumentation.
No interactive SAF provider was available for a deterministic persisted-grant cancellation test;
that cleanup path was reviewed directly. Physical-device performance and live remote catalog/update
network behavior were not exercised.
