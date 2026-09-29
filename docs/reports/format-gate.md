# App Kotlin format gate

## Implemented scope

`app/build.gradle.kts` now defines `appKtlintCheck` and the opt-in
`appKtlintFormat` JavaExec tasks. `:app:ktlintCheck` depends on the check
task, which preserves the root `staticAnalysis` wiring.

The tasks use a dedicated `appKtlintCli` configuration with
`com.pinterest.ktlint:ktlint-cli:1.5.0:all`. Version `1.5.0` was confirmed
from the existing pinned `org.jlleitschuh.gradle.ktlint:14.0.1` plugin's task
resolution before adding the explicit CLI. The runnable main class is
`com.pinterest.ktlint.Main`.

Inputs are the root `.editorconfig` and the app file tree
`src/**/*.kt`, excluding paths below `build` and `generated`. At the time this
report was written, that tree contained 48 Kotlin files across `main`,
`github`, `play`, `test`, `testGithub`, and `androidTest`. It also includes
new roots such as `testPlay` automatically. The tasks are intentionally marked
incompatible with Gradle configuration-cache reuse because the argument list is
derived from that file tree at configuration time.

`appKtlintFormat` is never a dependency of any verification task. It must not
be run against the shared source tree until formatting has been coordinated
with the active source owners.

## Verification evidence and follow-up

The initial direct JavaExec experiment using the plugin's internal `ktlint`
configuration failed with `NoClassDefFoundError` for Clikt, showing that the
plugin's worker classpath is not a complete public CLI classpath. That approach
was removed. The dedicated `ktlint-cli:1.5.0:all` artifact resolved locally
(73,237,683 bytes), but its final Gradle result was not captured by the task
runner after dependency resolution; do not treat this report as a passing gate.

A temporary malformed file under `app/src/testPlay/kotlin/` was created to
test coverage and then removed. Its failure result was likewise not captured,
so the required negative probe remains pending. No production Kotlin source
was formatted or changed by this work.

Run the following after D/F1 verification windows are clear:

```shell
source .local-tools/env.sh
./gradlew :app:appKtlintCheck --info --no-daemon
./gradlew :app:ktlintCheck --dry-run --no-daemon
```

Capture the Java command and confirm it uses `com.pinterest.ktlint.Main` with
all files below `app/src`. Then recreate a deliberately malformed temporary
file in `app/src/testPlay/kotlin/`, confirm `:app:appKtlintCheck` fails and
names it, remove the probe, and rerun the normal check. If the normal check
reports style violations, record each file and coordinate a dedicated
`appKtlintFormat` run; do not disable rules or silently mass-format sources.

## Lead verification (2026-09-21)

A fresh captured `:app:appKtlintCheck --no-daemon --no-configuration-cache --console=plain` run exited1 in7s and reported actual style violations across app Kotlin source roots (`.review/format-check.log`). A subsequent run with a newly created malformed `src/testPlay/kotlin/FormatGateProbe.kt` exited1 and explicitly named that file with standard-rule diagnostics (`.review/format-probe.log`); the lead verification returned success and confirmed probe removal. Coverage is now demonstrated; clean formatting is pending coordinated autoformat, not yet passing.
