#!/usr/bin/env bash
# How the home screen does on the TV: cold start time, memory, and dropped frames while moving focus.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address}"
PY=../tools/venv/bin/python
TV="$PY -I ../tools/tv.py"
PKG=com.barontv.home
echo "== Cold start (force-stop, then start)"
$TV "am force-stop $PKG; sleep 2; am start -W -n $PKG/.HomeActivity | grep -E 'TotalTime|WaitTime'"
sleep 6
echo "== Memory (kB)"
$TV "dumpsys meminfo $PKG | grep -E 'Java Heap|Native Heap|Code:|Graphics:|Private Other|TOTAL'"
echo "== Frames while moving focus round the dock, shelf and side dock"
$TV "dumpsys gfxinfo $PKG reset >/dev/null; for k in 22 22 22 22 21 21 21 21 19 19 22 22 20 20 21; do input keyevent \$k; sleep 0.35; done; dumpsys gfxinfo $PKG | grep -E 'Total frames|Janky frames|50th|90th|95th|99th'"
