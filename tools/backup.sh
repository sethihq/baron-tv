#!/usr/bin/env bash
# Save what's needed to put the TV back as it was: package lists, props, settings, the current
# Home app and keyboard, and a memory snapshot. Writes to backup/<timestamp>/ (gitignored).
#
# Usage: TV_HOST=192.168.1.20 tools/backup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "run: uv venv tools/venv && uv pip install --python tools/venv/bin/python -r tools/requirements.txt"; exit 1; }
OUT="backup/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
run() { "$PY" -I tools/tv.py "$2" > "$OUT/$1"; echo "saved $OUT/$1 ($(wc -l < "$OUT/$1" | tr -d ' ') lines)"; }
run packages-all.txt 'pm list packages -f'
run packages-disabled.txt 'pm list packages -d'
run packages-system.txt 'pm list packages -s'
run packages-third-party.txt 'pm list packages -3'
run getprop.txt 'getprop'
run settings-global.txt 'settings list global'
run settings-secure.txt 'settings list secure'
run settings-system.txt 'settings list system'
run home.txt 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
run home-candidates.txt 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
run keyboard.txt 'settings get secure default_input_method; ime list -s'
run meminfo.txt 'dumpsys meminfo'
echo "Backup in $OUT"
