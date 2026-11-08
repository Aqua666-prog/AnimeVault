#!/usr/bin/env bash
# Run after the existing CI emulator setup/install; no real-device cleanup.
set -euo pipefail
appearance_adb=(adb -s "${ANDROID_SERIAL:-emulator-5554}")
mkdir -p onboarding-preview
"${appearance_adb[@]}" shell am instrument -w -r -e class \
  com.sergey.animevault.ui.theme.AppearancePersistenceTest,com.sergey.animevault.ui.theme.RaidenUiTest \
  com.sergey.animevault.test/androidx.test.runner.AndroidJUnitRunner \
  | tee onboarding-preview/appearance-instrumentation.log
python3 - <<'PY'
import re
from pathlib import Path
log = Path('onboarding-preview/appearance-instrumentation.log').read_text()
assert re.search(r'OK \(6 tests\)', log), log
assert not re.search(r'FAILURES|INSTRUMENTATION_FAILED|Process crashed', log), log
PY

"${appearance_adb[@]}" pull /data/local/tmp/animevault-raiden onboarding-preview/raiden
test -s onboarding-preview/raiden/euthymia.png
test -s onboarding-preview/raiden/musou.png
