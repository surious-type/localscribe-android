# Task H integration runner correction

The optional device-test workflow now invokes
`:app:connectedGithubDebugAndroidTest` with its default AndroidJUnitRunner.
This preserves the integrated application graph required by Room, Compose, and
UI instrumentation tests. Hosted-device native inference still skips because no
model is provisioned.

The self-hosted, explicitly targeted native smoke command continues to use
`EngineTestRunner`, which is appropriate for that isolated adapter test.

Verification was limited to YAML and shell-command inspection; Gradle was not
run because the UI integration worker owns the build window.

## UI verification recovery

Root reproduced Gradle EOFException and traced it to `Test.getPreviousFailedTestClasses` reading an incomplete serialized result store, before test execution. Prior results retained in `.review/F2-verification/corrupt-github-test-results`. Running `:app:cleanTestGithubDebugUnitTest :app:testGithubDebugUnitTest --tests '*BenchmarkRunControllerTest' --tests '*TranscriptPlaybackControllerTest' --no-daemon --no-configuration-cache --max-workers=2` passed in 7 seconds. Fresh XML counts recorded by root; this is focused coverage, not whole-product acceptance.

## Interrupted device attempt

The device worker confirmed it launched two identical connected-test commands: its retry reinstalled the APK while the first instrumentation still ran. Logcat shows MainActivityTest at23:38:09, reinstall at23:38:24, and process kill for installPackageLI at23:38:29. No product crash was established. Aggregate XML0tests is invalid; do not claim device acceptance. The lead will run the next device suite through one persistent exec session and poll that session rather than retrying an active command. Test-only field reflection corrected to InstalledModelFileStore.modelMutex.

## External notification-revocation harness

Android kills the instrumented target while `pm revoke` runs, so the notification-permission
regression is split into two fresh instrumentation processes. The phase class has one selected
test; normal device filtering supplies no phase and skips it without creating a durable job.

Run the following commands in order, waiting for each Gradle command to finish before starting the
next one:

```bash
source .local-tools/env.sh
SERIAL=<adb-serial>
./gradlew :app:assembleGithubDebug :app:assembleGithubDebugAndroidTest --no-daemon --no-configuration-cache --max-workers=2
adb -s "$SERIAL" install -r app/build/outputs/apk/github/debug/app-github-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk
adb -s "$SERIAL" shell pm grant io.github.surioustype.localscribe.debug android.permission.POST_NOTIFICATIONS
adb -s "$SERIAL" shell am instrument -w -r -e class io.github.surioustype.localscribe.ui.NotificationPermissionRevocationPhaseTest#runsHostSelectedPhase -e notificationRevokePhase PREP io.github.surioustype.localscribe.debug.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell pm revoke io.github.surioustype.localscribe.debug android.permission.POST_NOTIFICATIONS
adb -s "$SERIAL" shell am instrument -w -r -e class io.github.surioustype.localscribe.ui.NotificationPermissionRevocationPhaseTest#runsHostSelectedPhase -e notificationRevokePhase VERIFY io.github.surioustype.localscribe.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`PREP` requires the host grant and persists a paused job ID. `VERIFY` asserts the revocation in a
fresh process, uses the rendered Resume control, denies Android's dialog, and confirms the same
job remains paused. Do not rerun a Gradle connected-test task or reinstall either APK between the
two `am instrument` invocations, because that may clear the persisted fixture.

## Final API 35 results

The notification-granted case passed on the API 35 software emulator (`OK (1 test)`, 41.428s).
It exposed and verified the production foreground-service fix: API 35 now calls the platform
three-argument `startForeground` with `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`, because the
AndroidX compatibility mask omits that API 35 service type.

The external revocation protocol then passed without reinstalling between phases:

- PREP: `OK (1 test)` in 28.729s.
- Host `pm revoke`: completed and Android terminated the target process as expected.
- VERIFY: `OK (1 test)` in 36.773s; the persisted job remained recoverable and paused.

The earlier post-FGS build matrix passed in 57s. The final post-audio-integrity matrix supersedes it:
244 clean JVM tests, static analysis, both debug lint variants, four application APK variants, and
the GitHub Android-test APK passed in 1m20s. A focused API 35 run passed 18/18 audio, Room, migration,
shared-URI ownership, and real v4→v5 checkpoint-restart tests.
