# H residual closure verification

## Result

The two residual findings from `H-final-rereview.md` are closed in the current
source and covered by focused JVM regressions. Formatting, static analysis,
the focused tests, and both product-variant plus Android-test Kotlin compiles
were run after the final source change.

## Source closure mapping

| Residual finding | Production closure | Regression evidence |
| --- | --- | --- |
| Dependency exceptions could strand completed checkpoints by failing the job. | `TranscriptionCoordinator` maps execution errors to `DomainFailure` and routes source permission/missing and model corruption/checksum failures through `pauseForDependency` (`app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:256`). The durable pause returns a claimed chunk to the pause state and leaves prior completed chunks intact. | `TranscriptionCoordinatorTest` covers permission loss during `readWindow()` and a corrupt pinned model during `loadModel()`, then restores the dependency and verifies that only the unfinished chunk runs (`app/src/test/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinatorTest.kt:198`, `:222`). |
| A file move followed by metadata or terminal-state publication failure could leave an inconsistent model installation. | `SecureModelDownloadManager` tracks file-moved, metadata-registered, and terminal-publication stages. Both cancellation before completion and any publication exception invoke `rollbackPublication`; rollback removes only the registered revision and moved file while holding `modelMutex` under `NonCancellable` (`app/src/main/kotlin/io/github/surioustype/localscribe/models/SecureModelDownloadManager.kt:264`, `:289`, `:323`). The repository contract has an exact-revision removal overload (`core/src/main/kotlin/io/github/surioustype/localscribe/core/ports/ModelPorts.kt:37`). | `ModelDownloadManagerTest` simulates a throwing register and a throwing terminal update. It verifies `FAILED`, no new final file, and preservation of an older revision (`app/src/test/kotlin/io/github/surioustype/localscribe/models/ModelDownloadManagerTest.kt:70`, `:97`). |

## Verification

All commands were run from the repository root after `source .local-tools/env.sh`.

```text
./gradlew appKtlintFormat appKtlintCheck staticAnalysis
```

Passed. This includes core and app ktlint plus `:app:lintGithubDebug`.

```text
./gradlew :app:cleanTestGithubDebugUnitTest :app:testGithubDebugUnitTest \
  --tests io.github.surioustype.localscribe.execution.TranscriptionCoordinatorTest \
  --tests io.github.surioustype.localscribe.models.ModelDownloadManagerTest
```

Passed. Cleaning the task first prevents stale result XML. Fresh XML reports:

| Suite | Tests | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `TranscriptionCoordinatorTest` | 12 | 0 | 0 | 0 |
| `ModelDownloadManagerTest` | 13 | 0 | 0 | 0 |
| Total | 25 | 0 | 0 | 0 |

```text
./gradlew :app:compileGithubDebugKotlin :app:compilePlayDebugKotlin \
  :app:compileGithubDebugAndroidTestKotlin :app:compilePlayDebugAndroidTestKotlin
```

Passed.

`git diff --check` also passed after formatting.

## Limitations

No device or adb test was run. The Android-test sources were compiled for both
GitHub and Play debug variants, but their runtime permission flows still need
an emulator or device with the required notification-permission states.
