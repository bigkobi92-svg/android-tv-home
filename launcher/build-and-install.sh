#!/usr/bin/env bash
# Build the home screen and install it on your Android TV as the default home, screensaver and remote.
# Usage:  TV_HOST=192.168.1.50 launcher/build-and-install.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?Set TV_HOST to your TV's IP address, e.g. TV_HOST=192.168.1.50 $0}"
TV="$TV_HOST:5555"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17+ 2>/dev/null || /usr/libexec/java_home)}"
export PATH="$JAVA_HOME/bin:$PATH"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
grep -q '^tmdb.api.key=' local.properties || echo "Note: no tmdb.api.key in launcher/local.properties; using the free iTunes charts."

./gradlew assembleDebug -q --console=plain
"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" install -r app/build/outputs/apk/debug/app-debug.apk
PKG=com.gathin.tvhome
"$ADB" -s "$TV" shell appops set $PKG GET_USAGE_STATS allow                      # Recently used row
"$ADB" -s "$TV" shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
"$ADB" -s "$TV" shell settings put secure screensaver_components $PKG/.GlobeDream  # night globe screensaver
"$ADB" -s "$TV" shell settings put secure screensaver_enabled 1
"$ADB" -s "$TV" shell cmd package set-home-activity --user 0 $PKG/.MainActivity
# On Android 9 the stock launcher keeps winning Home, so switch it off (tools/restore.sh switches it back on).
STOCK="${STOCK_LAUNCHER:-com.google.android.tvlauncher}"
"$ADB" -s "$TV" shell pm disable-user --user 0 "$STOCK" >/dev/null 2>&1 || true
"$ADB" -s "$TV" shell input keyevent KEYCODE_HOME
echo "Installed. Phone remote: http://$TV_HOST:8765"
