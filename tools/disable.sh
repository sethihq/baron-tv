#!/usr/bin/env bash
# Disable every package listed in a debloat file on the TV (one package per line, # comments allowed).
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore.sh <same file>.
# Some firmware re-enables every disabled package at boot (CloudWalker on CVTE boards). There, pass
# REMOVE=1 to remove each package for user 0 instead (pm uninstall -k --user 0): the APK stays in the
# system image and its data is kept, and restore.sh brings it back with install-existing.
#
# Usage: TV_HOST=192.168.1.50 tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt
#        REMOVE=1 TV_HOST=192.168.1.50 tools/disable.sh examples/baron-cloudtv-32/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: disable.sh <debloat list>}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
if [ "${REMOVE:-0}" = 1 ]; then CMD="pm uninstall -k --user 0"; else CMD="pm disable-user --user 0"; fi
{
  grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk -v cmd="$CMD" '{print cmd " " $1}'
  [ "${REMOVE:-0}" = 1 ] || echo "pm list packages -d"
} | "$PY" -I tools/tv.py --batch
