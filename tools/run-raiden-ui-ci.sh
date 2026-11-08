#!/usr/bin/env bash
# Preserve the test failure and expose its diagnostic in GitHub check annotations.
set -euo pipefail
for ui_suite in tools/run-onboarding-ui-tests.sh tools/run-appearance-ui-tests.sh; do
  ui_log="${RUNNER_TEMP:-/tmp}/$(basename "$ui_suite").log"
  if bash "$ui_suite" 2>&1 | tee "$ui_log"; then
    continue
  else
    python3 - "$ui_log" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
# Avoid huge base64 screenshot lines while retaining Kotlin/JUnit failure details.
lines = [line for line in lines if not line.startswith('ONBOARDING_PREVIEW:')]
message = '\n'.join(lines[-65:])[-14000:]
message = message.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print('::error title=Android UI diagnostic::' + message)
PY
    exit 1
  fi
done
