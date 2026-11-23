#!/usr/bin/env bash
# Installs build/m3home.apk on the Fire TV and (re)enables the Home-button service.
# Android can drop the service from the enabled list when the app is updated, so this
# always puts it back.
# Usage: ./install.sh <fire-tv-ip>:5555
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
export MSYS2_ARG_CONV_EXCL='*'   # Git Bash: pass device paths to adb untouched

DEVICE=${1:-${M3_DEVICE:-}}
[[ -n $DEVICE ]] || { echo "usage: ./install.sh <fire-tv-ip>:5555   (or set M3_DEVICE)" >&2; exit 1; }
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null || ADB="${LOCALAPPDATA:-}/Microsoft/WinGet/Packages/Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe/platform-tools/adb.exe"
SERVICE=app.m3home/app.m3home.HomeGuard

[[ -f build/m3home.apk ]] || ./build.sh
"$ADB" connect "$DEVICE" >/dev/null 2>&1 || true
"$ADB" -s "$DEVICE" install -r build/m3home.apk
"$ADB" -s "$DEVICE" shell "settings put secure enabled_accessibility_services $SERVICE; settings put secure accessibility_enabled 1; dumpsys deviceidle whitelist +app.m3home >/dev/null"
# Lets M3 Home switch the screensaver itself and restore its Home-button service after updates.
"$ADB" -s "$DEVICE" shell pm grant app.m3home android.permission.WRITE_SECURE_SETTINGS || true
# Lets the "Now playing" widget read media titles (M3 Home ignores notifications themselves).
# Lets M3 Home offer updates from GitHub (Android still asks before installing each one).
"$ADB" -s "$DEVICE" shell appops set app.m3home REQUEST_INSTALL_PACKAGES allow || true
"$ADB" -s "$DEVICE" shell cmd notification disallow_listener app.m3home/app.m3home.NowPlaying >/dev/null 2>&1 || true
"$ADB" -s "$DEVICE" shell cmd notification allow_listener app.m3home/app.m3home.MediaListener || true
"$ADB" -s "$DEVICE" shell am start -n app.m3home/.Home >/dev/null
echo "installed on $DEVICE; Home button service: $("$ADB" -s "$DEVICE" shell settings get secure enabled_accessibility_services | tr -d '\r')"
