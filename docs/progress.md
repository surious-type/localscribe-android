# Progress ledger — plan: docs/plans/implementation.md

## Current authoritative snapshot (2026-09-29, final verification)

| Task | State | Owner / evidence |
|---|---|---|
| Environment | Ready | Persistent .local-tools/env.sh; API35 software emulator available |
| Licensed demo | Complete, reviewed | English37.25s public-domain fixture; RU/mixed unavailable |
| A/B | Complete, reviewed | 2d3aafa; latest core39tests pass |
| C persistence/service | Complete, reviewed | Recovery correction and dependency pause/resume regressions accepted |
| D models/updater | Complete, reviewed | Publication rollback and exact-revision regressions accepted |
| E audio/JNI | Complete, reviewed | Native smoke retained; selected MediaStore source durability verified on emulator |
| F app UI/benchmark | Complete, reviewed | F1/F2 accepted; responsive controls and permission recovery exercised on emulator |
| G CI/CD/docs | Complete locally; CI execution pending | G-review-fix2; H default runner corrected |
| H final review/verification | Complete locally | 244 JVM tests; 18 focused device tests; static/lint; four APK variants; API35 permission allow/revoke phases pass |

Historical entries below record previous states; this table is the restart entry point.

## Environment

- Initial repository empty; AGENTS.md from previous task is preserved.
- Connected GitHub authenticated as surious-type; target public repo exists, size 0, admin/push access verified.
- Initial Java runtime 17 only; no gh/Gradle/Android SDK found. Toolchain preparation delegated to toolchain agent under /tmp/localscribe-tools.
- No baseline tests exist.

## Decisions

- Ruling: Use the fresh user-designated checkout directly, on a codex implementation branch, without nested worktrees — it contains no existing application work to isolate; avoids invisible duplicate checkouts. Cost if wrong: branch/worktree relocation.
- Ruling: User explicitly authorized architectural decisions and continuous implementation; product-spec.md is the approved spec, so no repeated design approval gates. Cost if wrong: reversible implementation rework.
- Ruling: Keep this ledger and task reports after completion as requested, overriding ephemeral skill-workspace cleanup. Cost: small documentation footprint.

## Preflight

| Tasks | Shared dependency | Resolution |
|---|---|---|
| A / B–F | Core entities and ports | A owns definitions; freeze before consumers |
| A / G | Gradle tasks, flavors | G waits for build configuration |
| B / C,D,F | Algorithms | Agree signatures; B only edits core algorithms/tests |
| C / E | Engine/audio ports | Frozen ports, disjoint app packages |
| C,D,E / F | Concrete wiring | F starts after adapters, owns container |
| All / H | Integrated build | H runs after task reviews; scoped fixes |
| A–H individually | Requirements vs checks | Each has a testable deliverable; device/native checks must be reported as unavailable if not run |

## Status

- Planning: architecture and dependency plan recorded.
- Research: complete; verified stable versions handed to A. No need to repeat research.
- Environment: completed JDK21/Gradle9.5/SDK37.0+36/NDK30/CMake4.1.2 setup and native smoke compile. Activate `/tmp/localscribe-tools/env.sh`; report `/tmp/localscribe-tools/report.md`.
- Demo fixtures: completed English public-domain 37.250s WAV + reference + manifest + provenance. Independent review_assets passed format/hash/provenance. RU/mixed explicitly unavailable. Integration caveat for F: enumerate runnable fixtures only; reference transcript not independently auditioned.
- A: interfaces frozen, partial Gradle scaffold present, build checks and task review pending. Agent interrupted by usage limit, resumed on user's instruction; do not redo contracts.
- B–H: pending; briefs B–G already prepared.
- Emulator environment followup: interrupted by usage limit; resumed to inspect partial download and attempt software boot. No connected device/KVM originally available.
- Recovery audit: git diff has no tracked modifications; implementation files are untracked, not missing. Initial planning commit is 3514913 on codex/localscribe-implementation. Preserve these files.
- Reviewed fixture/plans committed as b9afd97.
- B: domain implementer running against frozen contracts; authorized AppVersion implementation in model/UpdateModels.kt and use of existing BenchmarkTiming model.
- A reported core and both Android flavor Kotlin compilation plus merged manifests passing; independent review still pending.
- E: audio/native implementer running in disjoint packages against frozen ports. AudioSourceStore adapter contract to be supplied to C.
- Emulator installed API35 AOSP ATD x86_64; no KVM. Agent-owned process disappeared at agent completion, restarted from root exec session 93092 with /tmp/localscribe-tools/start-emulator.sh. Full boot and tests not yet confirmed.
- Production signing/release: intentionally not started; requires user action at final gate.
- 2026-09-18 recovery: A report exists and bootstrap implementation is ready for independent review; reviewer_bootstrap assigned `/tmp/localscribe-review-A.diff`. Domain and audio_native resumed existing work after second usage interruption; no duplicate implementations.
- Restored pending toolchain/review_assets agents finalized without repeating completed work to release slots.
- AGENTS.md gained user/workspace cost-aware routing instructions; preserved. Tool API has no agent_type selector, so new dispatches name the specialized role in the brief and use a capable economical model/reasoning level. Explicit user-required per-task/final reviews remain binding.
- Tiny native-test model downloaded only to /tmp and SHA-256 verified: be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21. Not included in Git/APK.
- Task A: complete, review clean in docs/reports/A-review.md; verified build evidence A-bootstrap.md. Commit deferred until B review because B's authorized AppVersion implementation shares a newly created A model file; preserve coherent commit rather than overwrite concurrent work.
- C: worker_persistence running; owns data/execution/service/tests and Room AudioSourceStore adapter.
- Emulator full boot verified on resume: sys.boot_completed=1; package service found. Ready for API35 x86_64 instrumentation.
- Ruling: Keep deterministic fixed 90s/3s persisted windows in first implementation; optional Silero VAD runs within windows rather than changing precomputed boundaries. Frozen planner has no speech-boundary input. Cost: some words cross window edges and rely on overlap/context assembly; future speech-aware planner needs an explicit contract extension.
- B implementation complete: 34 tests and core ktlint passed, report docs/reports/B-domain.md; reviewer_domain active on /tmp/localscribe-review-B.diff. Not yet marked task complete until review gate.
- Cross-task lint: B aggregate run flagged app AndroidAudioRepository API26 compatibility; E owner notified to fix and verify.
- B review: 3 blocking findings recorded docs/reports/B-review.md (B1 segment temporal correspondence, B2 GitHub asset origin/path, B3 measured memory gating). Original implementer resumed for fix round 1 with regression tests; no task completion claim.
- User requested continued execution after another usage interruption. C/E resumed from existing files; no duplicate work. C has coordinator test+implementation in progress; E has audio adapters, JNI/CMake and tests in progress. D/F/G/H remain unstarted.
- B fix round1 implemented with regression tests: temporal correspondence, exact GitHub release URL binding, measured RAM gating. 38 tests and ktlint passed; scoped review package /tmp/localscribe-review-B-fix1.diff; baseline /tmp/localscribe-b-domain-round1-base.
- C concrete adapters: LocalScribeDatabase.open(context), RoomTranscriptionRepository(db), RoomInstalledModelRepository(db), RoomAudioSourceStore(db), RoomBenchmarkStore(db). BenchmarkStore observe/get/upsert/remove; coordinator accepts shared executionMutex.
- Task B: complete, fix round1 3/3 addressed and review clean (docs/reports/B-review-fix1.md). 38 tests passed. Commit batching with A remains pending native shared build ownership.
- E ARM64 and x86_64 builds pass. Instrumentation started zero tests because future F Application class missing; E authorized test-only runner/plain Application and optional Gradle testRunner property for pre-UI adapter verification. Final H will run actual app/default runner.
- C authorized additive BenchmarkRecord.sampleId default unspecified plus schema/mappers, so F1 can compare the same actual sample without silently conflating fixtures.
- Latest recovery: C/D/E were interrupted; resumed same agents and existing files. E fixed AGP9 schema assets directory String. No A/B work repeated beyond commit verification.
- A/B saved in commit 2d3aafa. Root freshly ran :core:test :core:ktlintCheck successfully (15s). Staged app/build.gradle.kts from A reviewed snapshot only, leaving E/C/D native/schema/dependency edits intact as working-tree changes. Shared additive sampleId contract included. Standard generated gradlew.bat CRLF retained (diff checked with cr-at-eol).
- Device coordination correction: C/E concurrent connected tests uninstalled each other's APK, producing zero-test runs. Lead assigned EXCLUSIVE emulator5554 test/install window to E; C must not install/uninstall/instrument until E releases it. Zero-test attempts are not passing evidence. Future device windows serialized by lead.
- E device WAV decode passed; external-model test first skipped explicitly, then separately launched with Tiny staged privately and device SHA verified. Real native inference still running on slow software emulator; not yet passing evidence.
- Integration ruling: remove executionMutex from RoomInstalledModelRepository persistence operations. D deletion holds the graph mutex externally; double acquisition deadlocked (verified RoomStores.kt51–56 and InstalledModelFileStore.kt24–31). C owns fix, D aware. Cost if wrong: protection must be maintained at every model-file mutation owner; final review checks wiring.
- Resume-work recovery: existing E native smoke session recovered without rerun: OK (1 test), 4248.185s on software API35 x86_64 (~70m48s), external Tiny hash verified. E releases device; C now has exclusive install/instrumentation window for Room/migrations. This is integration evidence, not representative device performance.
- C implementation/report present; final covering checks pending. C/D/E resumed same workers; A/B/environment/assets retained as completed. No reset/overwrite.
- C device verification PASS7/7 Room/recovery/migration tests, 61s, API35 EngineTestRunner; see C-persistence.md. No need to repeat this run absent changes.
- Gradle concurrency correction: separate agents' builds interfered with Kotlin output/cache. All Gradle/build/install windows are now serialized by lead: E final checks → C coordinator JVM/style → D distribution tests. Source /tmp/localscribe-tools/env.sh each invocation. Toolchain paths still exist, verified by root; D's missing SDK was unsourced environment, not absent installation.
- E implementation final: 6/6 JVM, app ktlint, generatedWAVdevice, TinyJNI1test, ARM64/x86_64 builds, 0x4000 ELF LOAD alignment verified. MIT notice included. Reviewer audio/native active; all E build/device windows released. Real native smoke uses one second silentPCM, verifies load/inference/JNI/unload, not speech accuracy or representative speed.
- Sep19 resume-work: /tmp was actually wiped (root confirmed exact paths absent). Source files/build reports remain; no implementation restart. Toolchain agent restores same tool versions under persistent ignored .local-tools (240GB available); no unnecessary native71min rerun. E review package regenerated at .review/E.diff, ignored and persistent.
- C final handoff records additional repeated-pause device1/1 pass and coordinator5/5 pass, then synchronization hardening; latter still needs compile/tests after environment restore. C worker released, no active test processes. D holds Gradle; E reviewer continues read-only.

- Resume audit: Git changes preserved; A/B and licensed fixture remain complete. E review confirmed three defects; original implementer resumed for focused fixes. Durable JDK/Gradle restored, Android SDK/NDK installation underway; Gradle windows remain serialized. D static checks tightened range validation and digest cancellation while awaiting its test window.

- Latest resume: build toolchain restoration completed (SDK37.0/NDK30/CMake4.1.2); emulator pending. E owns exclusive Gradle window for focused regression fixes, then C/D checks. C reviewer resumed after quota interruption. git diff --check passed; no completed work repeated.

- Task C review found four actionable concurrency/lifecycle defects; original implementer owns fix round1 with regression tests. Mandatory graph mutex wiring verification handed to F2. Existing successful Room checks remain evidence; new control-flow changes require covering tests.

- Resume audit: E re-review report persisted before quota and accepts all3 fixes; no need to repeat review. C/F1 original workers resumed; G independent delivery/security review started. Git diff --check clean, reviewed changes preserved.

- G ruling: release checksum asset is localscribe-vX.Y.Z.apk.sha256, matching reviewed core/D contract; G docs/workflow adapt. Cost if wrong: release metadata integration rework, no release published. Fix includes exact SDK package android-37.0 verified in installed environment.

- Emulator installation and boot verified by root (sys.boot_completed=1), live session24836. G fixes passed static/YAML/version boundary tests and await scoped re-review. F1 has exclusive Gradle window; C re-review and D security review read-only in parallel.

- C fix1 re-review closes request retention, durable cancellation cleanup and mandatory mutex injection; two related edge cases remain under fix2. F1 implementation reports7/7 focused tests, final handoff/report pending independent review.

- Sep20 recovery: durable tools/source/review packages retained; Git diff --check passes. C fix2, D fix1 and G fix2 assigned original workers. F1 review saved3 findings; fix next when slot frees.
- Ruling: retain installed model revisions by descriptor/hash with Roomv3 migration and exact-hash lookup for persisted jobs; new jobs use latest revision. No automatic old-artifact deletion. This resolves remote catalog refresh while preserving resumability; cost is retained disk usage until safe explicit descriptor deletion. Detailed ownership/tests: docs/briefs/D-review-fixes.md.

- Task C accepted after fixround2: all findings closed (C-review-fix2.md),12/12targetedtests passed. D-owned NewApi failures remain separate and prevent global lint completion. No completedCwork repeated.

- Resume afterquota: D/F1 originalworkers continue partialfixes; Gfix2 re-review accepted with nofindings. G localdelivery scope complete; actualGitHubworkflow execution remains finalintegration evidence.

- Dfix productionstable; covering JVM command passed72 tests (reported33Dapp+8coordinator+31core),4deviceRoomv3tests running. F1 requestedregressions fully staged awaiting window. Explicitappktlint sourcegate worker stages boundedconfigfix after audit identified coveragegap; no concurrentbuildedits. F2 independentUI preparation starts in ignored staging, no compiledsource integration until prerequisites accepted.

- Sep21 recovery: D checks/reports finished beforeinterruption; no rerun needed. D independent fixreview, F1 stagedregression execution, and existingF2 staging resumed. Formatgate patch is preserved .review/format-working/app.build.gradle.kts.patch; its apply/verification window followsF1.

- Latest recovery: Dfix2 resumesexclusiveGradle. F1fix1 accepted state/alignment but residual cancel-during-unload guard/test staged .review/F1-fix2. F2 fullownedUI staged, independent source review starts before integration; no UIcompile/deviceclaims. Formatgate JavaExec1.5.0all implemented with configurationcache disabled fordynamicfileargs, badprobe verificationpending; probe removed.

- F1 allreviewfindings closed after17tests (F1-review-fix2.md); emptyfixdiff packagingissue resolved bytargetedactualsourceinspection. Formatgate rootverification nowproves actualKotlincoverage+newbadtestPlayprobe rejection/cleanup; manyexistingstyleviolationsawaitautoformat afterDfix3snapshot. UIreview10findings confirmed; originalUIworker fixesall in staging withauthorized minimalcoldservice recoverygate.

- Latest resume: Dfix3 originalworker retains exclusiveGradle; player implementation resumes independently in staged ui/playback +JVMtests; UIworker resumes allotherF2fixes and consumes playerAPI. These scopes are disjoint. No stagedUI copied into actual app yet; no prioracceptedtests/reviews repeated.

- Sep26 recovery: prior root autoformat command never executed because automaticapprovalreview hitaccountquota (notunsafe-action verdict). Userresumed; sameapproved-scope request retried normally and started(session87171);48Kotlinfiles snapshot .review/pre-format. Dfix3review and stagedUI/player originalagents resumed; acceptedtasksnotrepeated.

## Resume checkpoint — 2026-09-26

- Read resume-work, AGENTS, plan, latest review and agent results; inspected status/diff and preserved all changes. No completed subsystem was restarted.
- Format gate now checks real app Kotlin sources. Autoformat applied; formatter is closing remaining long-line violations. Gradle remains serialized.
- D fix4 uses required shared graph model mutex for installed reuse/publication and deletion; gated regressions pending. Coordinate real D files with formatter to avoid edit overlap.
- F2 state restoration and overlap export tests are staged; a fresh scoped worker is completing remaining required UI integration regressions. No UI build/device pass claimed.
- H follow-up: device CI currently selects EngineTestRunner (plain Application); after F2 integration, verify runner choice for application/graph Compose tests and remove obsolete pre-UI override if needed.

## Resume checkpoint — 2026-09-27

- Previous agents are no longer live. Preserved Dfix4 implementation/report and staged UI regression files; no accepted task repeated.
- worker_verify_d_format owns the exclusive build window for remaining style fixes, both variant compilation and focused model tests. reviewer_d_fix4 independently checks only the remaining concurrency finding.
- worker_finish_ui_stage completes interrupted controller/test wiring and adaptive tests in staging; real UI integration waits for build window release.

- Dfix5 model tests root XML verification: GitHub18/18 and Play18/18, zero failures/errors/skips; source/format checks passed. Bounded independent fix5 review pending.
- F2fix1 review left7findings (benchmark ownership/resume permission plus5medium original gaps); worker_finish_ui_stage implements fix2 then integrates allowlist and owns exclusive Gradle. Emulator boot_completed=1 verified.

- D fix5 review closes artifact synchronization but retains one direct ownership residual: a formerly reserved job can publish stale CANCELLED after replacement COMPLETED. Ruling: this is real and affects UI truthfulness, so carry the smallest owner-checked terminal publication correction and deterministic regression into integration; no broad sixth D review cycle. Final project review must inspect this explicit fix. Cost if wrong: a model transfer status may be overwritten or cancellation feedback lost.

- Latest resume: integrated UI preserved (do not recopy staging). Prior worker hit quota during verification. worker_ui_verification owns Gradle and real-source compile/style/test fixes; reviewer_ui_fix2 read-only independently checks7findings. D ownership residual correction written but tests pending; CI runner correction done/YAML parsed.

- F2fix3 independent source review closes all4findings (F-ui-review-fix3.md); two low coverage gaps tracked for H: stop during suspended player preparation and real UI recreation/SAF/feedback instrumentation. Source accepted pending integrated runtime gates.
- Root fresh benchmark/player11tests passed after diagnosing corrupted Gradle serialized results; state/presentation6tests passed. H full JVM/static/lint/four APK builds running; fresh final reviewer dispatched.

- Latest quota resume: H JVM complete39core/101GitHub/86Play. Dynamiccolor NewApi errors corrected; both debug lints and GitHub/Play debug APK plus GitHub unsignedrelease built in H-build-lint. Build halted only at new notification Androidtest duplicatehelper/missingimport; Playrelease not yetcreated. worker_android_test_fix owns testfix/build/device window; emulator5554 available. Final reviewer resumed interrupted investigation; finalreport pending.

- H final review saved6findings in H-final-review.md (2high,4medium); single consolidated worker_final_fixes assigned all6 plus regressions. D formerowner cancellation fix accepted, new publicationcancel bug distinct. Device firstattempt failed before tests (XML0tests); worker_android_test_fix diagnoses launch/harness and forwards productionfailures. Playrelease/testAPK compile nowcompleted perH-device-build log. No acceptance claim.

- Device attempt evidence: 11Room/migration passes, one stale reflection failure fixed; UItest run interrupted by reinstall so aggregate XML0tests cannot be accepted. All4APK variants nowexist but predate finalfixwave. Root will rerun device and coveringverification after worker_final_fixes completes.

- Sep28 resume: valid prior device run21tests17pass4fixturefailures; setUp Unit and notification seed transition corrected in2testfiles only. Root clean JVMall nowPASS39core/105Github/90Play, zero failures/errors/skips (H-final-jvm.log). Final scoped rereview resumed (no report saved beforequota); static/lint/fourAPK rebuild inprogress. Targeted4device failures rerun afteremulatorboots.

- Final scoped rereview H-final-rereview.md verifies4/6closures, retains2load-bearing exception/publicationbranches. Ruling: both are confirmed unfinished original fixes that would strand checkpoints or model metadata; complete only these2branches with targeted regressions rather than defer broken recovery. No further broadreview cycle; lead will verify exact closures/evidence. Cost if wrong: unintended dependency classification or model rollback can affect resumability/revision retention. worker_residual_closure owns bounded completion; root4devicefixture rerun single session12300.

- Root4case device retest: MediaStore durable-source regressionPASS, notificationdeniedPASS; revocation testcase killsinstrumentedprocess by Android policy (logcat permissions revoked, notproductcrash). grantedcase notrun. worker_device_fixture_fix nowmakes externalrevoke two-process harness; no syntheticpermission/no skippedassertions. residualworker owns focusedJVM/formatwindow.
