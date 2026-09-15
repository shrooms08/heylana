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

# Clear the list first. Writing the same value back changes nothing, so a
# service Android has marked as crashed would stay unbound; emptying the list
# and putting it back makes Android unbind and bind it fresh, which also takes
# it off the crashed list.
"$ADB" shell settings put secure enabled_accessibility_services '""'
"$ADB" shell settings put secure accessibility_enabled 0
sleep 1
"$ADB" shell settings put secure enabled_accessibility_services "$SERVICE"
"$ADB" shell settings put secure accessibility_enabled 1

# Enabled is not the same as running: wait for Android to actually bind it.
# The bound list names services by their label, not their class, so look for
# "label=Heylana" — the class name never appears on that line.
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if "$ADB" shell dumpsys accessibility | grep -E "Bound services" | grep -q "label=Heylana"; then
        echo "Screen reading is on and running for $PACKAGE."
        echo "Open Heylana: the Screen reading row should now be ticked."
        exit 0
    fi
    sleep 1
done

echo "Screen reading is switched on, but Android has not started it." >&2
echo "Open Settings > Accessibility > Heylana and turn it off and on again." >&2
exit 1
