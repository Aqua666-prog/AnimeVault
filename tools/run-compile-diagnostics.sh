#!/usr/bin/env bash
# Executed by GitHub Actions only. Keep compilation errors accessible without blob logs.
set -euo pipefail
compile_log="${RUNNER_TEMP:-/tmp}/animevault-compile.log"
if ./gradlew --no-daemon compileDebugKotlin 2>&1 | tee "$compile_log"; then
  exit 0
fi
python3 - "$compile_log" <<'PY'
from pathlib import Path
import sys
message = '\n'.join(Path(sys.argv[1]).read_text(errors='replace').splitlines()[-80:])[-14000:]
print('::error title=Kotlin compile diagnostic::' + message.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
PY
exit 1
