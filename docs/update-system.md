# Application updates

GitHub builds check public releases for `surious-type/localscribe-android`. Play builds use a disabled update implementation and do not declare REQUEST_INSTALL_PACKAGES. Model catalog/download code must not reference the updater.

Automatic checks run only when enabled and outside a bounded cache interval. A manual check bypasses this interval. Expose current version, last check time, release notes and actionable error/no-update/update states. Never turn a failed request into “up to date.”

## Verification pipeline

Parse a stable `vX.Y.Z` release and exact `localscribe-vX.Y.Z.apk`/`.sha256` assets. Ignore drafts/prereleases. Download on user request into private cache. Enforce HTTPS and size bounds; verify SHA-256 before inspection. Parse APK package metadata, require the expected package name and increasing versionCode, and match signing certificates to the installed app before launching the system installer. The Android package installer remains the final authority. No silent install.

Checksums from the same release detect corruption; they are not an independent authentication channel. Certificate verification and Android installation policy supply the stronger authenticity boundary. Debug installs cannot update to a differently signed production APK in place; document uninstall/data consequences instead of bypassing checks.

## Release operations

CI must build debug artifacts without signing secrets. The tag workflow validates `vX.Y.Z` against project version, runs checks, builds/signs with protected environment secrets, calculates SHA-256 and attaches consistently named assets and release notes. Production secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD. Never commit them, print them or place them in the APK.

The first production key and first production release require explicit user handling/approval. The implementation agent prepares workflows and documentation but does not generate that key or publish that release.
