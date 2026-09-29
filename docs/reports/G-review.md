# Task G delivery review

## Actionable findings

### P1 — Published checksum name is incompatible with the in-app updater

`.github/workflows/release.yml:58-64` creates
`localscribe-vX.Y.Z.sha256`, and `docs/build-and-release.md:46-52` documents that
name. The GitHub updater instead requires exactly
`localscribe-vX.Y.Z.apk.sha256` in
`app/src/github/kotlin/io/github/surioustype/localscribe/updates/GithubAppUpdateManager.kt:157-176`;
the core parser makes the same requirement in
`core/src/main/kotlin/io/github/surioustype/localscribe/core/domain/ReleaseParser.kt:30-43`.
Consequently a successfully published release cannot be discovered by the app:
the checksum lookup fails before the APK is downloaded. Reconcile the D/G
contract with the product specification's `.sha256` name, update the updater
and its fixtures or the release asset consistently, and add one integration
test that feeds the workflow's exact two asset names and checksum contents into
the release parser/update manager.

### P1 — Build code runs with the release token, and approval occurs before validation

`.github/workflows/release.yml:7-17` gives the entire job `contents: write` and
places the `production` environment on that job. Checkout persists this token by
default, after which repository-controlled Gradle configuration and tests run at
lines 37-41. A compromised tagged revision or build plugin therefore executes
inside the release authority boundary, even though only the final `gh release
create` needs write permission. The job-level environment also asks a reviewer
to approve before version validation and tests have produced evidence. Split
validation/build into a `contents: read` job, then make a dependent protected
sign/publish job receive the verified unsigned artifact and hold signing secrets
plus `contents: write`; disable persisted checkout credentials wherever checkout
is still needed. This makes environment approval a review of a concrete passing
release candidate and limits write authority to publication.

### P2 — The documented version formula permits duplicate version codes

`scripts/version/validate-release.sh:23-31` accepts unbounded semantic
components. Under the documented formula, all of `v0.1000.0`, `v1.0.0`, and
`v0.999.1000` validate with `versionCode=1000000`; these cases were exercised
statically and all exited zero. Thus `docs/build-and-release.md:21-30` is wrong
that the mapping is monotonic for successive semantic releases, and future
updates can collide or move backward at the Android package layer. Restrict
minor and patch to `0..999`, bound the major/result to Android's supported
version-code range, reject arithmetic overflow, and add boundary/collision
tests. The current `versionCode=1` with `versionName=0.1.0` is acceptable only as
the documented pre-release state; the initial release policy should state that
the first production `v0.1.0` changes it to `1000`.

### P2 — CI and contributor docs use abbreviated rather than actual AGP unit-test tasks

`.github/workflows/ci.yml:33-37`, `.github/workflows/release.yml:37-41`,
`README.md:28-33`, and `AGENTS.md:13-22` invoke `:app:testGithubDebug` and
`:app:testPlayDebug`. AGP 9.3.2's generated task evidenced by the existing test
report is `:app:testGithubDebugUnitTest`, and the persistence report likewise
uses that full task. Gradle task-name abbreviation may resolve the shorter form
today, but it is not the actual task and becomes ambiguous if another matching
task appears; the delivery report's claim that commands matched configured task
names is therefore unsupported. Use `:app:testGithubDebugUnitTest` and
`:app:testPlayDebugUnitTest` consistently, and keep the workflow unclaimed as
executed until GitHub CI runs.

### P2 — The optional native-model workflow input cannot populate its fresh emulator

`.github/workflows/device-tests.yml:19-41` creates a new GitHub-hosted emulator,
then lines 42-53 accept only an Android-side file path and hash. No step uploads,
downloads, or `adb push`es a model into that new AVD, so a dispatch caller cannot
provide the advertised "already on the emulator" path and the native inference
test always skips on the hosted runner. Add a bounded opt-in source for a tiny
model and copy it into an app-readable emulator location while verifying the
provided hash, or remove the unusable inputs and document that native inference
requires a separately prepared self-hosted runner. Add wrapper validation to
this workflow as well before it executes `./gradlew` at line 53.

## Accepted scope

The four referenced actions are pinned to full commit SHAs in their official
repositories; PR/main and device workflows have read-only repository permission;
debug jobs receive no production secrets; SDK 37, build-tools 36.0.0, NDK
30.0.16248370, CMake 4.1.2, JDK 21, and Java 17 bytecode match the checked-in
configuration and restoration record. The documentation clearly says GitHub
workflows, signing, and release publication have not run, and it preserves the
owner approval requirement for the first signing key/environment/release.
