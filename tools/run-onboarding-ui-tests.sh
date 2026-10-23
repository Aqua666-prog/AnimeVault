#!/usr/bin/env bash
set -uo pipefail

test_result=0
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sergey.animevault.ui.onboarding.OnboardingFlowTest \
  || test_result=$?

mkdir -p onboarding-preview
adb pull /sdcard/Android/data/com.sergey.animevault/files/onboarding-preview/. onboarding-preview/ || true
exit "$test_result"
