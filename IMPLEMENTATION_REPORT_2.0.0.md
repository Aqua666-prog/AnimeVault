# AnimeVault 2.0.0 — implementation report

Date: 2026-09-23

## Base

The implementation was made directly on top of the supplied `AnimeVault-1.7.2-build-ready-source.zip` source tree. The project was not recreated from scratch. Version is now `2.0.0` (`versionCode 50`), Room schema is `8` with migration `7 -> 8`.

## Implemented

### Provider layer

- provider config schema v2 with monotonic `configVersion`, issue/expiry timestamps, priorities and APK-pinned trusted host families;
- safe sibling/mirror switching inside trusted domain families;
- separate provider health channels for catalogue, playback and direct downloads;
- download preflight checks a real HLS/MP4 transport instead of treating a successful catalogue request as proof of download health;
- YummyAnime requires a user-supplied public `X-Application` key and current `api.yani.tv`/`yummyani.me` headers;
- AnimeLib uses `Site-Id: 5`, the `cdnlibs.org`/`lib.social` API family and v5 origin/referer assumptions;
- AnimeON preserves legitimate episode `0` specials;
- AnimeVost can recover older releases outside the latest-100 cache path;
- Jut.su and Dream Cast are present again as experimental providers.

### Offline download engine

- explicit resolving/retry/verifying states;
- per-provider and per-host/CDN circuit breakers;
- AniLiberty `cache-rfn.libria.fun` <-> `cache.libria.fun` fallback;
- fresh stream re-resolution for expired/forbidden/not-found URLs;
- Unified-provider fallback to another provider for the same logical episode;
- user-controlled quality fallback; strict quality mode still permits unknown-quality HLS master playlists so the requested rendition can be selected after opening the master;
- HLS segment concurrency with a conservative cap, retry/backoff, AES-128 and byte-range support retained;
- persistent HLS resume journal and progressive-download source fingerprinting;
- smoothed speed and ETA persisted in Room and shown in the download UI/notification;
- final `MediaExtractor` verification before `COMPLETED`;
- download provenance stores the actual provider/CDN host used.

### UI / diagnostics

- source diagnostics show separate catalogue/playback/download states;
- settings include automatic quality fallback toggle;
- download centre understands `RESOLVING`, `RETRY_WAIT`, and `VERIFYING`, plus speed/ETA and actual source host/provider.

## Verification completed in this environment

Successful:

```text
git diff --check
bash tools/verify-final.sh
bash tools/verify-2.0.0.sh
```

Observed output includes:

```text
Android XML: OK
Source sanity: OK
Provider config v2: OK (11 providers, configVersion=2000001)
Final version/database sanity: OK
Final source verification: OK
AnimeVault 2.0.0 source/provider/download sanity: OK
```

## Verification not completed here

A full Gradle build/test/lint could not start because the Gradle 9.5.0 wrapper distribution is not cached in this container and DNS/network access to `services.gradle.org` is unavailable. The actual command attempted was:

```text
./gradlew --offline testDebugUnitTest
```

and it stopped in the wrapper with `java.net.UnknownHostException: services.gradle.org` before project compilation. Therefore this report does **not** claim that unit tests, lint, APK assembly, Room generated schema validation, or live provider smoke tests passed in this environment.

Before shipping an APK, run on a normal networked JDK 17 Android build environment:

```bash
bash tools/verify-2.0.0.sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
bash tools/verify-apk-runtime.sh
```

Then, on an Android device, use **Settings -> Sources -> Check sources** and test at least one real download through AniLiberty plus one cross-provider fallback path.

## Operational note

The APK has safe built-in provider endpoints and priorities. The configured remote GitHub URL currently needs its repository copy of `provider-config.json` updated to the included schema-v2 file before remote mirror/priority changes can take effect. Until then, an old/invalid remote config is rejected and built-in settings remain active.

AnimeLib currently keeps the existing optional manual Bearer-token model. A full OAuth/PKCE sign-in flow was deliberately not added without an end-to-end authenticated test environment; doing so would have increased release risk rather than download reliability.
