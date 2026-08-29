# AnimeVault 1.7.2 — build-ready cleanup

Build-system cleanup applied before CI build:

- Android Gradle Plugin updated from 9.3.1 to 9.3.2 (JDK 17 lint crash fix).
- Removed temporary `android.newDsl=true` and `android.builtInKotlin=true` migration flags; both are AGP 9 defaults.
- Removed `ANIMEVAULT_LOCAL_MAVEN` emergency/offline mirror hooks and dependency exclusions.
- Restored standard Gradle repository resolution via Google Maven, Maven Central and Gradle Plugin Portal.
- Top-level source directory corrected from `AnimeVault-1.7.1` to `AnimeVault-1.7.2`.

Intended CI environment: Ubuntu x86_64, JDK 17, Gradle Wrapper 9.5.0, AGP 9.3.2.
