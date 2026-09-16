#!/usr/bin/env bash
# Builds the signed release APK and puts a copy in dist/.
#
#   ./scripts/release.sh
#
# The signing key comes from local.properties (heylana.keystore, heylana.keystorePass,
# heylana.keyAlias, heylana.keyPass); see CLAUDE.md, "Release build". Any extra
# arguments go to Gradle, e.g. -Pheylana.keystore=/path for a one-off build.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :app:assembleRelease "$@"

apk="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$apk" ]]; then
  echo "No signed APK: the release key is not set up." >&2
  echo "Add heylana.keystore, heylana.keystorePass, heylana.keyAlias and heylana.keyPass to local.properties." >&2
  exit 1
fi

version=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
mkdir -p dist
out="dist/heylana-${version}.apk"
cp "$apk" "$out"

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
apksigner=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)apksigner

echo
echo "APK:     $(pwd)/$out"
echo "SHA-256: $(shasum -a 256 "$out" | cut -d' ' -f1)"
if [[ -x "$apksigner" ]]; then
  echo "Signed by (certificate SHA-256, for ASSETLINKS_SHA256 in colon form):"
  "$apksigner" verify --print-certs "$out" | sed -n 's/.*certificate SHA-256 digest: //p' \
    | tr '[:lower:]' '[:upper:]' | sed 's/../&:/g; s/:$//; s/^/  /'
fi
echo
echo "Install: adb install -r $out"
