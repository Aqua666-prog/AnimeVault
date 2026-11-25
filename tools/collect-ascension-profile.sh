#!/usr/bin/env bash
set -euo pipefail
device=(adb -s "${ANDROID_SERIAL:-emulator-5554}")
mkdir -p ascension-profile

# The fixture and profileable APK use the same debug key. Replacing the APK preserves onboarding.
"${device[@]}" install -t -r app/build/outputs/apk/debug/app-debug.apk
"${device[@]}" install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"${device[@]}" shell am instrument -w -r \
  -e class com.sergey.animevault.benchmark.BenchmarkSetup \
  com.sergey.animevault.test/androidx.test.runner.AndroidJUnitRunner | tee ascension-profile/setup.log
grep -Fq 'OK (1 test)' ascension-profile/setup.log
"${device[@]}" install -t -r app/build/outputs/apk/benchmark/app-benchmark.apk

./gradlew --no-daemon -PanimeVaultBenchmarks=true :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.benchmark.AnimeVaultBaselineProfile \
  2>&1 | tee ascension-profile/gradle.log

profile_output=macrobenchmark/build/outputs
find "$profile_output" -type f -name '*-prof.txt' -size +0c -exec cp -t ascension-profile {} +
compgen -G 'ascension-profile/*-baseline-prof.txt' >/dev/null
compgen -G 'ascension-profile/*-startup-prof.txt' >/dev/null
