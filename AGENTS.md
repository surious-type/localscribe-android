# Repository Guidelines

## Project Structure & Module Organization

LocalScribe has a pure Kotlin `core/` module and Android code in `app/`.
Production Kotlin and resources live in `app/src/main/`; GitHub and Play
implementations are in `app/src/github/` and `app/src/play/`. JVM tests belong
in `core/src/test/` or `app/src/test/`; device tests are in
`app/src/androidTest/`. Keep Room schemas in `app/schemas/`, JNI/CMake code in
`app/src/main/cpp/`, bundled metadata/assets in `app/src/main/assets/`, and
task evidence in `docs/reports/`.

## Build, Test, and Development Commands

Activate the restored environment with `source .local-tools/env.sh`, then use
the checked-in wrapper. The project needs JDK 21 (Java 17 bytecode), SDK 37,
NDK 30.0.16248370, and CMake 4.1.2. Useful targeted commands are:

- `./gradlew :core:test :app:testGithubDebugUnitTest`: local unit tests.
- `./gradlew staticAnalysis :app:lintGithubDebug`: ktlint and GitHub lint.
- `./gradlew :app:assembleGithubDebug`: GitHub debug APK.
- `./gradlew :app:connectedGithubDebugAndroidTest`: device/emulator tests.

## Coding Style & Naming Conventions

Use four-space Kotlin indentation, `PascalCase` types, `camelCase` members,
lowercase package names, and `snake_case` Android resources. Keep core domain
interfaces free of Android dependencies. ktlint is part of static analysis.

## Testing Guidelines

Add regression tests for bug fixes and meaningful tests for new behavior. Name
test classes after their subject, such as `TranscriptRepositoryTest`, and use
descriptive method names. Document device requirements for instrumentation
tests.

## Commit & Pull Request Guidelines

Use concise imperative subjects. The reviewed A/B foundation commit is
`2d3aafa`. Keep commits and pull requests focused.

Pull requests should explain the user-visible change, link relevant issues, and
report exact validation or why it could not run. Include screenshots for UI
changes.

## Security & Configuration

Keep credentials, signing keystores, machine-specific SDK paths, and generated build outputs out of version control. Add appropriate ignore rules when configuring the project.

## Cost-aware agent routing

Use the cheapest capable specialized role.

### Routing

1. `explorer`
   - DEFAULT role for repository investigation
   - locating files and symbols
   - tracing call paths
   - dependency inspection
   - test/log investigation
   - read-only research

2. `worker`
   - DEFAULT role for implementation
   - ordinary bug fixes
   - tests
   - refactoring
   - isolated feature implementation

3. `reviewer`
   - NOT a default final step
   - architecture-sensitive changes
   - subtle correctness problems
   - security-sensitive code
   - use only when deeper review is justified

### Delegation rules

- Use `explorer` for investigation rather than doing broad repository
  exploration in the lead agent.
- Use `worker` for normal implementation.
- Never spawn a generic/default agent when one of the specialized roles fits.
- Never use reviewer merely because implementation has completed.
- Never use high reasoning by default.
- Never spawn multiple agents for the same scope.
- Prefer one well-scoped agent over several overlapping agents.
- Do not repeat repository investigation already completed by a subagent.
- Escalate only after identifying a concrete limitation of the cheaper role.
- Keep handoffs concise.
- Prefer targeted tests during implementation.
- Run broad verification only when the scope justifies it.
