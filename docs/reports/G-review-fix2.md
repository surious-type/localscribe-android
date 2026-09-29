# Task G fix round 2 review

## Actionable findings

None within the scoped fix-round review.

## Accepted fixes and evidence

The version validator now rejects noncanonical tag components, zero or
leading-zero `versionCode` values, codes above `2,100,000,000`, and oversized
values before Bash can evaluate them arithmetically. Static probes confirmed
that `v01.0.0`, `v1.00.0`, `v0.0.0`, `0001000`,
`18446744073709552616`, and `2100000001` are rejected, while the documented
`v0.1.0`/`1000` and upper boundary `v2100.0.0`/`2100000000` pass. The regression
script covers these boundaries and is invoked by both the PR/main CI workflow
and tag-release validation before Gradle work.

The native-smoke documentation no longer relies on `/data/local/tmp`. It
installs the debug app and test APKs first, streams the host-verified model
through `run-as` into the debuggable app's private storage, and invokes the one
instrumentation method directly with that app-private absolute path and the
expected SHA-256. This preserves the fixture across the smoke invocation and
places it in the target app's readable security domain.

`bash -n` and `scripts/version/test-validate-release.sh` passed, all three
workflow files parsed as YAML, references confirm the regression script is
wired into CI and release validation, and `git diff --check` reported no
whitespace errors. Per scope, no Gradle task, GitHub workflow, device test,
signing operation, or release was executed or claimed as validated.
