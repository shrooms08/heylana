#!/usr/bin/env bash
# Re-enables Heylana's screen reading on the attached device.
#
# Installing the app switches its accessibility service off, which greys out
# "Start buddy" on the onboarding screen. Run this after every Run from Android
# Studio instead of walking through Settings by hand.
set -euo pipefail

PACKAGE="xyz.heylana.app"
SERVICE="${PACKAGE}/${PACKAGE}.screen.HeylanaAccessibilityService"

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
    ADB="$HOME/Library/Android/sdk/platform-tools/adb"
fi
if [ ! -x "$ADB" ]; then
    echo "adb not found. Install platform-tools or put adb on your PATH." >&2
    exit 1
fi

"$ADB" shell settings put secure enabled_accessibility_services "$SERVICE"
"$ADB" shell settings put secure accessibility_enabled 1

echo "Screen reading enabled for $PACKAGE."
echo "Open Heylana: the Screen reading row should now be ticked."
