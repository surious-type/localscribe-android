# LocalScribe Implementation Plan

> Use subagent-driven-development. Each implementation task receives a fresh implementer and an independent reviewer. Keep reports and review evidence in docs/reports.

**Goal:** Build and validate a local-only Android transcription app from the approved mission.
**Architecture:** Pure Kotlin core plus Android adapters; durable Room checkpoints; minimal whisper.cpp JNI boundary.
**Tech stack:** Kotlin, Compose Material 3, Flow, Room, Android MediaCodec/SAF, NDK whisper.cpp.
**Spec:** ../product-spec.md and ../architecture.md.

## Global Constraints

- applicationId `io.github.surioustype.localscribe`; minSdk 26.
- No uploads, analytics, telemetry, cloud transcription or embedded credentials.
- ModelManager and AppUpdateManager must remain independent.
- Models are outside APK; SHA-256 verified before use; Small multilingual default.
- Room is authoritative; chunk completion and raw segment writes are atomic and idempotent.
- GitHub and Play flavors; REQUEST_INSTALL_PACKAGES only in GitHub.
- Never generate production signing secrets or publish first production release.
- Preserve legal provenance of bundled audio; omit unavailable fixtures rather than substitute unlicensed audio.

## Tasks and dependencies

- [ ] A Bootstrap + shared contracts: root Gradle/wrapper/version catalog, core model/ports, app Gradle/manifest/resources/base application, Android flavors. Verify configuration/compile. Freeze contracts before B–F.
- [ ] B Domain algorithms: core chunk planner, assembler, bounded context, WER/CER, benchmarks/recommendation, states, export and version/release parsing. TDD with boundary/failure tests. Own core algorithms and tests only.
- [ ] C Persistence + execution: app data/Room, repository adapters, coordinator, foreground service, thermal policy and lifecycle tests. Depends on A; integrates B/E through frozen contracts.
- [ ] D Model and update systems: app models/download and updates adapters, verified catalog, resumable downloads, checksum and signer validation, independent flavor update factories. Depends on A/B API contracts. Own network/models/updates only.
- [ ] E Audio + native: app audio MediaStore/SAF/window decode, app engine JNI and CMake pinned whisper.cpp; cancellation/resource ownership and device tests. Depends on A. No interface changes without lead.
- [ ] F Compose integration: application container/ViewModels, onboarding/home/details/progress/transcript/player, models/benchmark/compare/demo/settings/update screens. Depends on A–E adapters. Own app ui and container/Activity.
- [ ] G CI/CD + documentation: reproducible PR/main checks and debug APK artifacts, protected tag release workflow, signing/version docs, architecture subsystem docs, contributor guide update. Depends on A build tasks; does not publish a release.
- [ ] H Integrated validation: tests/lint/static analysis/debug + unsigned release builds, Room/Compose/native device checks where available; fix integration defects via implementer. Fresh broad reviewer then covering revalidation.

## Per-task steps

1. Read brief, spec and frozen contracts; record baseline.
2. Write meaningful tests first for domain logic; run to confirm expected failure.
3. Implement only owned files; run focused checks, then task checks once.
4. Save exact commands/results and limitations in task report; provide diff for review.
5. Independent reviewer checks spec and quality; implementer fixes findings and reruns covering tests.
6. Lead updates ledger and commits only reviewed task scope. Parallel agents never mutate Git index.

## Safe parallelism

Environment/toolchain and version research are read-only to project source. After A freezes interfaces, B, C, E can work in disjoint paths. D can overlap E once B public signatures are known. G can overlap F with stable build config. F integration waits for concrete adapter constructors. Shared file amendments are serialized by lead assignment.
