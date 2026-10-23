#!/usr/bin/env bash
set -uo pipefail

test_result=0
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.ui.onboarding.OnboardingFlowTest \
  || test_result=$?

mkdir -p onboarding-preview
adb pull /sdcard/Android/data/com.sergey.animevault/files/onboarding-preview/. onboarding-preview/ || true
# These files contain only the synthetic test UI, never a real Google account.
for preview_name in 01-welcome 02-account 03-finish-guest; do
  preview_file="onboarding-preview/preview-$preview_name.png"
  if test -s "$preview_file"; then
    printf 'ONBOARDING_PREVIEW:%s:' "$preview_name"
    base64 -w 0 "$preview_file"
    printf '\n'
  fi
done
exit "$test_result"
