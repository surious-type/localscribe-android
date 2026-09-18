# Task A report — bootstrap and frozen contracts

## Delivered

- Added a Gradle 9.5 two-module project: pure Kotlin `:core` and Android `:app`.
- Pinned the wrapper distribution SHA-256 and committed the authentic generated wrapper JAR.
- Configured AGP 9 built-in Kotlin for `:app`, Kotlin/JVM for `:core`, Kotlin 2.4.20,
  KSP 2.3.12, compile/target SDK 37, min SDK 26, NDK 30.0.16248370, Compose,
  Material 3, Activity, Lifecycle, Navigation, Room, Coroutines, JUnit 4 and instrumentation
  dependencies in the version catalog.
- Added GitHub and Play distribution flavors and debug/release build types.
- Added ktlint through its Gradle plugin and a root `staticAnalysis` task combining all ktlint
  checks with Android Lint.
- Added application manifests and base resources. Audio storage and notification permissions are
  API-scoped; there is no microphone permission. Backup/device transfer are disabled. The
  FileProvider exposes only `cache/exports/` and `cache/updates/`.
- Declared modern foreground-service permissions/types, a documented `specialUse` subtype for API
  34, `mediaProcessing` for API 35+, and wake-lock capability. Runtime code will select the
  applicable type by API level.
- Kept `REQUEST_INSTALL_PACKAGES` solely in the GitHub flavor manifest.
- Added persistence-friendly models, typed failures, and coroutine/Flow ports for audio,
  transcription, models, benchmarks/demo samples, and app updates. Model and APK update contracts
  have no dependency on one another. Model descriptors distinguish transcription and VAD
  artifacts so VAD entries cannot enter transcription recommendations.
- Froze exact contract semantics and Task B deterministic API signatures in
  `docs/contracts.md`. The lead was notified when this boundary was ready.
- Added `.gitignore` coverage for SDK-local files, Gradle/build/cache output, IDE state and signing
  material.

## Version basis

The lead's platform research verified the pinned baseline against official release information:
AGP 9.3.2, Gradle 9.5.0, Kotlin 2.4.20, KSP 2.3.12, API 37, Room 2.8.5 and the AndroidX/Compose
versions in `gradle/libs.versions.toml`. Gradle resolved every pinned plugin and library used by the
verification commands.

Android Lint's online dependency detector reports newer compatible-looking versions (AGP 9.4.0,
Gradle 9.7.1, ktlint plugin 14.2.0 and Coroutines 1.11.0). They are warnings, not resolution or
correctness failures. This bootstrap keeps the researched, toolchain-tested set requested by the
brief rather than changing the build matrix during implementation.

## Verification evidence

Environment for every command:

```shell
source /tmp/localscribe-tools/env.sh
```

Wrapper generation:

```shell
gradle wrapper --gradle-version 9.5.0 --distribution-type bin \
  --gradle-distribution-sha256-sum 553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
```

Result: `BUILD SUCCESSFUL in 51s`; one wrapper task executed. Generated wrapper JAR SHA-256:
`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.

Core compilation:

```shell
./gradlew :core:compileKotlin
```

Result: `BUILD SUCCESSFUL in 10s`; `:core:compileKotlin` executed. The first diagnostic run caught
Java target 21 versus Kotlin target 17; `core/build.gradle.kts` now explicitly emits Java and
Kotlin 17 bytecode while Gradle runs on the available JDK 21.

After the lead-authorized final `ModelKind` contract amendment, the focused command was run again:
`BUILD SUCCESSFUL in 712ms`; `:core:compileKotlin` was up-to-date against the newly compiled output.

Flavor configuration, resource processing and manifest processing:

```shell
./gradlew :app:compileGithubDebugKotlin :app:compilePlayDebugKotlin \
  :app:processGithubDebugMainManifest :app:processPlayDebugMainManifest
```

Result: `BUILD SUCCESSFUL in 38s`; 19 tasks executed and one up-to-date. Both Kotlin compile tasks
correctly reported `NO-SOURCE` because UI/service/application classes belong to later tasks.
Resource, KSP, core JAR and both manifest pipelines completed.

Merged-manifest inspection found `REQUEST_INSTALL_PACKAGES` in
`merged_manifest/githubDebug/.../AndroidManifest.xml` and no occurrence in the Play manifest. Both
manifests contain backup disabled, the versioned audio/notification/foreground-service
permissions, the foreground service declaration and the narrow FileProvider.

Bootstrap-owned production formatting and static analysis:

```shell
./gradlew :core:ktlintMainSourceSetCheck :app:ktlintCheck :app:lintGithubDebug
```

Result: `BUILD SUCCESSFUL in 2s`; 37 tasks, with eight executed, four from cache and 25 up-to-date.

The aggregate task is wired and was also invoked:

```shell
./gradlew staticAnalysis
```

The first aggregate run reached and passed app ktlint, core main-source ktlint and Android Lint,
then failed at `:core:ktlintTestSourceSetCheck` on Task B's concurrently created tests. Task B then
began adding production algorithm sources, so subsequent aggregate/main-source formatting also
reports its in-progress files. The Task B implementer has been notified to format its main and test
sources before handoff. Task A did not edit or reformat another implementer's files.

## Deferred handoffs and limitations

- `LocalScribeApplication`, `MainActivity`, and `service.TranscriptionService` are intentional
  future manifest references owned by Tasks F and C. `MissingClass` is suppressed on those exact
  declarations only so Android Lint remains useful during bootstrap.
- Native Gradle wiring is deferred until Task E creates its owned
  `app/src/main/cpp/CMakeLists.txt`; adding it earlier would break Task A configuration checks. The
  handoff is to set CMake version `4.1.2` and ABI filters `arm64-v8a` and `x86_64` after that file
  exists. Task E owns target definitions and 16 KiB page-size flags.
- No emulator/device checks ran in this task. There is no UI, Room schema, service, or native
  target yet, so such checks cannot exercise bootstrap behavior.
- No production signing material was created.
