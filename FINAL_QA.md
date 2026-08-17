# AnimeVault 1.0.0 — final QA gate

Local static gate completed before packaging:

- Kotlin PSI parse: 130 source/test files, 0 syntax errors.
- Android XML parse: 13 files, 0 errors.
- GitHub Actions YAML parse: OK.
- `tools/verify-source.sh`: OK.
- `git diff --check`: OK.
- SQLite smoke checks for storage aggregation and watched-time aggregation: OK.
- Working tree clean before packaging.

The container cannot complete Gradle's Android tasks because DNS access to `services.gradle.org` is unavailable here. The repository workflow therefore remains the authoritative final gate and runs, in order:

1. source sanity checks;
2. `testDebugUnitTest`;
3. `lintDebug`;
4. `assembleDebug`;
5. upload APK artifact.

Do not treat a failed CI run as a releasable build. Fix the reported compile/lint/test failure and rerun the workflow before installing the final APK.
