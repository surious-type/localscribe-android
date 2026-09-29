# Verification and acceptance record

This file records requirements to verify, not claims that they already pass. Commands and results belong in `docs/reports/` and the final section below.

## Automated gates

* Domain: chunk coverage/bounds, timestamp-constrained overlap, Unicode context, WER/CER, benchmark calculations, recommendations, state transitions, strict release parsing/version comparison, all five exports.
* Persistence: job/plan atomicity, claim attempts, segment/completion atomicity, duplicate completion, cancelled-job guard, restart recovery, schema upgrade retaining transcript data.
* Android: both flavors compile; GitHub-only install permission; Room/Compose tests on a real device or emulator; native model fixture test separately opted in.
* Delivery: format/static analysis, unit tests, both-flavor lint, debug APK and unsigned release APK. No production key needed for these checks.

## Device acceptance protocol

1. Fresh install: onboarding explains offline processing, SAF import works without broad media access; model catalog offers multilingual Small and no model is downloaded automatically.
2. Download: visible progress; cancel and resume; SHA mismatch never creates an installed model. Check disk exhaustion/airplane mode; retry provides actionable state.
3. Benchmark: run the bundled 37.250-second sample on an installed model. Record actual duration, config, model hash, memory/thermal state and RTF; repeat with another model for comparison. Demo shows reference and recognition, not fixture text pretending to be output.
4. Transcribe a >3-minute file. Verify bounded chunks, foreground progress, screen-off continuation and seekable timestamped transcript. Repeat with M4A/MP3/WAV supported by the device codecs.
5. After the first chunk commits, terminate the process. Relaunch and resume: committed chunk count and raw segment IDs remain unchanged; only unfinished work runs. Repeat while paused and after device reboot. Android force-stop may require explicit app launch, never promise automatic restart.
6. Cancel while native inference runs. Job stays CANCELLED, no result from that interrupted chunk is committed, engine closes without native use-after-free. Pause/resume follows the documented state machine.
7. Simulate service timeout/thermal critical event using tests or device shell where supported; ensure prompt foreground shutdown, paused state and explanation. No endless retry loop.
8. Transcript: segment tap seeks to source timestamp; play/pause/scrubber, search, copy and share work. Save TXT/MD/SRT/VTT/JSON through SAF and inspect escaping and timestamps.
9. Updates: automatic checks obey toggle/cache interval; manual check performs an immediate request. Offline errors differ from no-update. Hash, package or signer mismatch blocks installer handoff. Play has no APK self-update or install permission.
10. Accessibility: large font, TalkBack labels, dark/light themes, narrow phone and tablet, empty/loading/error states and adequate touch targets.

## Environment limitations

An API 35 software emulator was used for Room, Compose, MediaStore, foreground-service and runtime-permission checks. Physical-device performance, screen-off reliability, real thermal behavior and production-signed update acceptance must not be claimed from JVM tests or a software emulator.

## Final results

Local acceptance completed on 2026-09-29.

- Clean JVM results: core 39, GitHub 110, Play 95; 244 total with no failures, errors or skips.
- `staticAnalysis`, `lintGithubDebug`, `lintPlayDebug`, both debug builds, both unsigned release
  builds and `assembleGithubDebugAndroidTest` passed after the API 35 foreground-service and Room
  v5 audio-integrity fixes.
- Focused API 35 audio/Room instrumentation passed 18/18, including migrations through v5, durable
  fingerprint relink, blocked deletion, shared URI ownership, and real legacy checkpoint restart.
- API 35 notification-granted instrumentation passed. The external PREP/revoke/VERIFY protocol
  also passed both phases and proved recovery of the same durable paused job after Android killed
  the target process on permission revocation.
- Retained native Tiny-model smoke evidence passed earlier; it was not repeated because the final
  repository/schema changes do not touch JNI, decoding, or inference.

Artifact SHA-256 values:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-github-debug.apk` | 22,760,431 | `ccd7f3c49dc072491ca85a8d964a578a8ae8acd9a5ec540ab2e940717e1bb52b` |
| `app-play-debug.apk` | 22,631,625 | `5ef511c52e06fbd82c08e701e46b4a5e41f5875c7c3925c9e04547c8e912a89b` |
| `app-github-release-unsigned.apk` | 15,517,178 | `6d233f860fd1d966d84e0eb09c561ccb58bd9c8114f04c46440ac723437d1f1e` |
| `app-play-release-unsigned.apk` | 15,500,754 | `d6a0c6937395f31da950249b863376bb7dedd981097c87cb2355c71f04a7ee0b` |
| `app-github-debug-androidTest.apk` | 1,329,834 | `0e1af730de0dc6b639f9e11d905033c0da4aa7eb01d8f0fc8a51f4cc1f5e6b84` |

Not run: live GitHub Actions, production signing/release, physical-device performance, thermal and
screen-off endurance, production-signed update acceptance, interactive SAF grant-cancellation,
and real multilingual RU/mixed audio.
