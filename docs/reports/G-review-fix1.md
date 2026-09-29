# Task G fix round 1 review

## Actionable findings

### P2 — Version validation is still non-injective and permits numeric overflow

`scripts/version/validate-release.sh:23-34` bounds the tag components before
arithmetic, but it does not require their canonical decimal representation and
does not bound `version_code` before Bash evaluates `-eq`. Consequently both
`v01.0.0` and `v1.00.0` validate with code `1000000`, contradicting the
injectivity claim in `docs/build-and-release.md:45-47`; `v0.0.0` also validates
with the non-positive code `0`. More seriously, the oversized input
`18446744073709552616` wraps to `1000` in Bash arithmetic and is accepted for
`v0.1.0`. Require every component to match `0|[1-9][0-9]*`, require a positive
canonical `versionCode` with a safe lexical length/range before any arithmetic
comparison, and add these cases to `scripts/version/test-validate-release.sh`.
That test script is currently unreferenced outside itself, so invoke it from CI
to make the new boundary checks a regression guard.

### P2 — The documented native-smoke example points at a normally unreadable app path

`docs/build-and-release.md:25-34` passes
`/data/local/tmp/ggml-tiny.bin` to instrumentation while the test executes
`File(path).isFile && canRead()` in the target app process. Files pushed to
`/data/local/tmp` belong to the shell SELinux domain and are normally not
readable by an untrusted app/test process, so the documented command generally
skips the smoke test it is meant to enable. Document a preparation command that
streams the model into the debuggable app's private storage (or another
demonstrably app-readable location), then pass that resulting absolute path;
otherwise use an explicit `<app-readable-path>` placeholder rather than the
misleading `/data/local/tmp` example.

## Accepted fixes

The checksum asset now matches the updater/parser contract and its checksum
contents name the APK correctly. Release validation/build has read-only
permission and no persisted checkout credential; only the dependent protected
job can sign and publish. Explicit AGP unit-test tasks are used consistently.
The hosted device workflow removed unusable model inputs and added wrapper
validation. All workflows now install the locally evidenced
`platforms;android-37.0` package. The development `versionCode=1` and documented
first production `v0.1.0` code `1000` form a coherent pre-release gate. No
workflow, APK build, signing operation, or release was claimed as executed.
