# Transcription pipeline

1. Import a persisted SAF URI or MediaStore entry. Keep source URI rather than copying the full recording. Shared temporary grants require an explicit durable import if access cannot be retained.
2. Persist source metadata, model hash, inference configuration, job and chunk plan in Room.
3. A visible user action starts the foreground service. Only one job/session executes at a time.
4. Decode one bounded window with Android MediaExtractor/MediaCodec; seek before its start and discard leading PCM. Convert channels to mono, resample to 16 kHz, apply conservative gain without clipping. Do not retain a multi-hour PCM buffer.
5. Load the verified Whisper model once for the session. Optional verified Silero VAD is supplied to whisper.cpp; no automatic aggressive denoise.
6. Transcribe each 90-second window with 3-second overlap. Keep at most a bounded tail of prior completed text as initial prompt.
7. In one Room transaction, replace that chunk's raw segments and mark it complete. Convert native relative timestamps to absolute source time exactly once.
8. Derive a merged view using timestamp-constrained overlap and normalized suffix/prefix matching. Preserve raw segments for audit, reassembly and timed export.

## Interruption semantics

COMPLETED chunks are immutable checkpoints. Aborted inference yields no checkpoint. On restart, reset PROCESSING chunks to PENDING and interrupted RUNNING jobs to PAUSED; user resume processes only unfinished chunks. Transactional claims and guarded state transitions prevent double processing or cancellation being overwritten by completion. A new model/config requires a new job, not mixing results in an existing job.

Pause checkpoints finished work and releases execution resources. Stop marks CANCELLED. Thermal critical state pauses with an explanation; moderate heat lowers thread count for the next chunk. Service timeout cancels inference, persists paused state, removes foreground notification and stops promptly. Android 15+ mediaProcessing has a system time budget, so no promise of unbounded background execution is made.

## Errors

Map revoked source access, missing files, unsupported codecs, storage exhaustion, model corruption, out-of-memory, native failure and thermal/service interruption to actionable domain failures. Never include transcript text or private file paths in release logs. Native process crashes cannot be caught by Kotlin; durable checkpoints bound the work lost.
