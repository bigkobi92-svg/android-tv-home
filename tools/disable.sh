#!/usr/bin/env bash
# Switch off every package listed in a debloat list (one per line, # comments allowed), for user 0.
# Nothing is uninstalled; tools/restore.sh undoes it.
# Usage:  TV_HOST=192.168.1.50 tools/disable.sh [list]     (default list: tools/debloat-tcl-android9.txt)
set -uo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?Set TV_HOST to your TV's IP address}"
LIST="${1:-debloat-tcl-android9.txt}"
TV="$TV_HOST:5555"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
"$ADB" connect "$TV" >/dev/null
grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | while read -r pkg; do
  printf '%-55s ' "$pkg"; "$ADB" -s "$TV" shell pm disable-user --user 0 "$pkg" </dev/null | tr -d '\r'
done
