#!/usr/bin/env bash
# CI-only: the emulator runner owns this disposable AVD. Never target a real device.
set -euo pipefail

preview_directory=onboarding-preview
remote_directory=/data/local/tmp/animevault-onboarding
test_class=com.sergey.animevault.ui.onboarding.OnboardingFlowTest
test_component=com.sergey.animevault.test/androidx.test.runner.AndroidJUnitRunner
onboarding_adb=(adb -s "${ANDROID_SERIAL:-emulator-5554}")

rm -rf "$preview_directory"
mkdir -p "$preview_directory"

collect_diagnostics() {
  # Diagnostic failures must not replace the original build/install/test exit code.
  timeout 15s "${onboarding_adb[@]}" get-state > "$preview_directory/device-state.txt" 2>&1 || :
  timeout 15s "${onboarding_adb[@]}" shell getprop sys.boot_completed > "$preview_directory/boot-state.txt" 2>&1 || :
  timeout 15s "${onboarding_adb[@]}" shell df -h /data > "$preview_directory/device-storage.txt" 2>&1 || :
  timeout 15s "${onboarding_adb[@]}" logcat -d -t 400 > "$preview_directory/logcat.txt" 2>&1 || :
}

on_exit() {
  local original_result=$?
  trap - EXIT
  if (( original_result != 0 )); then
    collect_diagnostics
  fi
  exit "$original_result"
}
trap on_exit EXIT

timeout 60s "${onboarding_adb[@]}" wait-for-device
emulator_flag="$(timeout 15s "${onboarding_adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "$emulator_flag" != 1 ]]; then
  echo "Refusing onboarding setup on a non-emulator device" >&2
  exit 1
fi

ready=0
for (( attempt=0; attempt<30; attempt++ )); do
  boot_state="$(timeout 10s "${onboarding_adb[@]}" shell getprop sys.boot_completed | tr -d '\r')" || boot_state=
  package_manager="$(timeout 10s "${onboarding_adb[@]}" shell pm path android | tr -d '\r')" || package_manager=
  if [[ "$boot_state" == 1 && "$package_manager" == package:* ]]; then
    ready=1
    break
  fi
  sleep 2
done
if (( ready == 0 )); then
  echo "Emulator boot or PackageManager did not become ready" >&2
  exit 1
fi

# Create and verify the shell-owned directory before any app installation or test.
timeout 15s "${onboarding_adb[@]}" shell "rm -rf '$remote_directory' && mkdir -p '$remote_directory' && test -d '$remote_directory'"
timeout 15s "${onboarding_adb[@]}" shell input keyevent 82

# Build in GitHub Actions only. Bypass ddmlib's install-write transport, not the tests.
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest

# Fresh install timestamps and data are required by the real first-launch test.
# This cleanup is permitted only after the emulator guard above.
installed_packages="$(timeout 15s "${onboarding_adb[@]}" shell pm list packages | tr -d '\r')"
for package in com.sergey.animevault.test com.sergey.animevault; do
  while IFS= read -r installed_package; do
    if [[ "$installed_package" == "package:$package" ]]; then
      timeout 120s "${onboarding_adb[@]}" uninstall "$package"
      break
    fi
  done <<< "$installed_packages"
done

timeout 240s "${onboarding_adb[@]}" install --no-streaming -t -r app/build/outputs/apk/debug/app-debug.apk
timeout 240s "${onboarding_adb[@]}" install --no-streaming -t -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 15s "${onboarding_adb[@]}" shell pm list instrumentation | tee "$preview_directory/instrumentation-list.txt"
# ActivityScenario in the tests launches the app. Do not pre-launch MainActivity:
# that would create first-launch preferences before the assertions run.
set +e
timeout 300s "${onboarding_adb[@]}" shell am instrument -w -r -e class "$test_class" "$test_component" \
  | tee "$preview_directory/instrumentation.log"
instrumentation_results=("${PIPESTATUS[@]}")
set -e

screenshot_result=0
if timeout 15s "${onboarding_adb[@]}" shell test -d "$remote_directory"; then
  timeout 60s "${onboarding_adb[@]}" pull "$remote_directory/." "$preview_directory/" || screenshot_result=$?
else
  echo "Screenshot directory disappeared during instrumentation" >&2
  screenshot_result=1
fi

validation_result=0
python3 tools/check-onboarding-instrumentation.py \
  --log "$preview_directory/instrumentation.log" \
  --source app/src/androidTest/java/com/sergey/animevault/ui/onboarding/OnboardingFlowTest.kt \
  --screenshots "$preview_directory" \
  --report "$preview_directory/TEST-onboarding.xml" \
  || validation_result=$?

# adb can return 0 even when AndroidJUnitRunner reports failed/zero/skipped tests.
# Preserve transport failures, then require both runner assertions and real PNGs.
if (( instrumentation_results[0] != 0 )); then exit "${instrumentation_results[0]}"; fi
if (( instrumentation_results[1] != 0 )); then exit "${instrumentation_results[1]}"; fi
if (( validation_result != 0 )); then exit "$validation_result"; fi
if (( screenshot_result != 0 )); then exit "$screenshot_result"; fi

# Synthetic UI previews only; no real Google account is used by these tests.
for preview_name in 01-welcome 02-account 03-finish-guest; do
  preview_file="$preview_directory/preview-$preview_name.png"
  printf 'ONBOARDING_PREVIEW:%s:' "$preview_name"
  base64 -w 0 "$preview_file"
  printf '\n'
done
