#!/usr/bin/env bash
# Switch everything in the debloat list back on and put the TV's own home screen and screensaver back.
# Usage:  TV_HOST=192.168.1.50 tools/restore.sh [list]
set -uo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?Set TV_HOST to your TV's IP address}"
LIST="${1:-debloat-tcl-android9.txt}"
TV="$TV_HOST:5555"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
"$ADB" connect "$TV" >/dev/null
grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/[[:space:]]+#.*$//' | while read -r pkg; do
  printf '%-55s ' "$pkg"; "$ADB" -s "$TV" shell pm enable "$pkg" </dev/null | tr -d '\r'
done
"$ADB" -s "$TV" shell pm enable com.google.android.tvlauncher </dev/null
"$ADB" -s "$TV" shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity </dev/null
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s "$TV" shell settings put global "$s" 1.0 </dev/null
done
"$ADB" -s "$TV" shell settings put global private_dns_mode opportunistic </dev/null
"$ADB" -s "$TV" shell settings put secure screensaver_components com.google.android.backdrop/com.google.android.backdrop.Backdrop </dev/null
echo "Restored. To remove the custom home screen too:  adb -s $TV uninstall com.gathin.tvhome"
