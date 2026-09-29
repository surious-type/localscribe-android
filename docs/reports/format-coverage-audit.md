# KtLint format coverage audit

## Scope and confidence

This is a read-only audit of the root/app Gradle configuration, the pinned
ktlint Gradle plugin, existing reports, and the checked-in Kotlin source tree.
No Gradle task was run. Confidence is **high** that the current app format gate
does not demonstrate coverage of every Android Kotlin source set; confidence is
**medium** on the exact plugin input set until the pending task-input command
below is run.

## Relevant files and symbols

- `build.gradle.kts`: root `plugins { alias(libs.plugins.ktlint) }`,
  `subprojects { apply(plugin = "org.jlleitschuh.gradle.ktlint") }`, and
  `staticAnalysis`'s dependency on each subproject's `ktlintCheck`.
- `app/build.gradle.kts`: Android application plus Compose/KSP plugins; no
  ktlint source-set configuration or explicit ktlint CLI task.
- `gradle/libs.versions.toml`: AGP `9.3.2`, Kotlin `2.4.20`, and
  `org.jlleitschuh.gradle.ktlint` `14.0.1`.
- App Kotlin source roots: `src/main/kotlin`, `src/github/kotlin`,
  `src/play/kotlin`, `src/test/kotlin`, `src/testGithub/kotlin`, and
  `src/androidTest/kotlin`.
- `docs/reports/C-persistence.md:137-141,186-191`: records that AGP 9 exposes
  only the aggregate app task and that the current integration registers only
  Kotlin-script ktlint tasks.
- `docs/reports/D-distribution.md:41`: records a passing, up-to-date
  `:app:ktlintCheck`, but does not identify its file inputs.

## Findings and evidence

1. The root plugin is applied to all subprojects, so `:app:ktlintCheck` exists,
   but neither root nor app config declares Android source directories. The
   aggregate `staticAnalysis` task only depends on that aggregate task.
2. The repository's own AGP 9 observation says the app integration registers
   only Kotlin-script ktlint tasks. The app contains 42 Kotlin files across
   Android `main`, flavor, JVM test, flavor-test, and instrumentation roots;
   a passing aggregate task therefore cannot be treated as evidence that all
   six roots were checked.
3. The plugin 14.0.1 artifact includes an Android applier and separate
   Kotlin-script task classes, so the likely failure mode is variant/source-set
   discovery with AGP 9's built-in Kotlin integration, rather than the plugin
   being absent. This artifact inspection does not prove the final file inputs.
4. Existing reports use `:app:ktlintCheck` as a pass signal, but no report records
   task paths, source directories, or input file counts. Those reports are
   insufficient to establish coverage for `github`, `play`, `testGithub`, or
   `androidTest` sources.

## Minimal robust correction

Add an explicit app formatting gate whose inputs name every intended Kotlin
root (main, github, play, test, testGithub, androidTest), using the repository's
pinned ktlint CLI/plugin engine. Make `app:ktlintCheck` or `staticAnalysis`
depend on that gate, and keep the existing plugin task for compatibility. A
source-set-aware Gradle configuration is preferable if AGP 9 exposes stable
Kotlin source directories; otherwise a small explicit CLI task with these six
directories is the bounded fallback. Do not rely on variant compilation to
cover formatting.

## Pending verification

After any correction, inspect task inputs without changing files:

```shell
source .local-tools/env.sh
./gradlew :app:tasks --all --no-daemon | rg 'ktlint|format'
./gradlew :app:ktlintCheck --dry-run --no-daemon
./gradlew :app:ktlintCheck --info --no-daemon 2>&1 | rg 'src/(main|github|play|test|testGithub|androidTest)/kotlin|ktlint.*(Input|files)'
```

The result should show a check task or explicit CLI invocation receiving all
six roots. This verification remains pending because the D exclusive window
prohibits running Gradle during this audit.
