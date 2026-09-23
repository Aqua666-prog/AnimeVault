# AnimeVault 2.0.0 — build-ready source notes

- `versionName`: `2.0.0`
- `versionCode`: `50`
- Room schema: `8`, migration `7 -> 8` included
- target/compile SDK: unchanged from 1.7.2
- expected build environment: JDK 17, Gradle Wrapper 9.5.0, AGP from the repository configuration

Recommended verification before distributing an APK:

```bash
bash tools/verify-2.0.0.sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
bash tools/verify-apk-runtime.sh
```

Provider smoke tests require live Internet access and are intentionally not treated as deterministic unit tests. In the app use **Settings → Sources → Check sources** to verify catalogue, playback and direct-download transport separately.

`provider-config.json` is schema v2. The installed APK has safe built-in endpoints/priorities; the remote file only overrides them after signature-shape/trust-family/expiry/version checks. Deploy the v2 config to the configured remote URL when publishing 2.0.0.
