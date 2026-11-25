#!/usr/bin/env bash
set -euo pipefail
device=(adb -s "${ANDROID_SERIAL:-emulator-5554}")
if [[ "$("${device[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo "Profile fixture requires an isolated Android emulator" >&2
  exit 1
fi
mkdir -p ascension-profile

# The fixture and profileable APK use the same debug key. Replacing the APK preserves onboarding.
"${device[@]}" install -t -r app/build/outputs/apk/debug/app-debug.apk
"${device[@]}" install -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"${device[@]}" shell am instrument -w -r \
  -e class com.sergey.animevault.benchmark.BenchmarkSetup \
  com.sergey.animevault.test/androidx.test.runner.AndroidJUnitRunner | tee ascension-profile/setup.log
grep -Fq 'OK (1 test)' ascension-profile/setup.log
"${device[@]}" install -t -r app/build/outputs/apk/benchmark/app-benchmark.apk
"${device[@]}" install -t -r macrobenchmark/build/outputs/apk/benchmark/macrobenchmark-benchmark.apk
"${device[@]}" shell am start -W -n com.sergey.animevault/.MainActivity | tee ascension-profile/preflight.log

profile_output=/storage/emulated/0/Android/media/com.sergey.animevault.benchmark/profiles
"${device[@]}" shell mkdir -p "$profile_output"
# Use a different directory from AndroidX's temporary output directory for its final move.
profile_status=0
"${device[@]}" shell am instrument -w -r \
  -e class com.sergey.animevault.benchmark.AnimeVaultBaselineProfile \
  -e additionalTestOutputDir "$profile_output" \
  com.sergey.animevault.benchmark/androidx.test.runner.AndroidJUnitRunner | tee ascension-profile/profile.log || profile_status=$?
"${device[@]}" pull "$profile_output/." ascension-profile/
test "$profile_status" = 0
grep -Fq 'OK (2 tests)' ascension-profile/profile.log
compgen -G 'ascension-profile/*-baseline-prof.txt' >/dev/null
compgen -G 'ascension-profile/*-startup-prof.txt' >/dev/null
grep -Fq 'Lcom/sergey/animevault/' ascension-profile/*-baseline-prof.txt
