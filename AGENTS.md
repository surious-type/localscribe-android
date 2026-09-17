# Repository Guidelines

## Project Structure & Module Organization

This repository is currently an initial scaffold: no application source, tests, assets, or build configuration has been committed. Update this guide as the project takes shape.

If a standard Android application module is introduced, use `app/src/main/` for production code and the manifest, `app/src/main/res/` for Android resources, `app/src/test/` for local unit tests, and `app/src/androidTest/` for device tests. These are proposed locations, not existing directories. Organize code by feature and keep shared utilities narrowly scoped.

## Build, Test, and Development Commands

There are no runnable build or test commands yet. When adding the build system, commit its wrapper and document required JDK and Android SDK versions in `README.md`.

For a Gradle-based Android setup, verify and document these commands once available:

- `./gradlew assembleDebug`: build a debug APK.
- `./gradlew test`: run local unit tests.
- `./gradlew connectedAndroidTest`: run tests on an emulator or connected device.
- `./gradlew lint`: run Android Lint checks.

## Coding Style & Naming Conventions

No formatter or linter is configured. Follow the conventions of the language selected during setup. For Kotlin, use four-space indentation, `PascalCase` class names, `camelCase` functions and properties, and lowercase package names. Use `snake_case` for Android resource names. Introduce formatting configuration alongside the first source files.

## Testing Guidelines

No test framework or coverage threshold is established. Add regression tests for bug fixes and tests for new behavior. Name test classes after the subject, such as `TranscriptRepositoryTest`, and use descriptive method names. Document test dependencies and device requirements when introduced.

## Commit & Pull Request Guidelines

There is no Git history from which to infer commit conventions. Use concise, imperative subjects, such as `Add initial Android project`. Keep commits focused.

Pull requests should explain the change, link relevant issues, and report validation performed or why it could not run. Include screenshots for UI changes.

## Security & Configuration

Keep credentials, signing keystores, machine-specific SDK paths, and generated build outputs out of version control. Add appropriate ignore rules when configuring the project.
