#!/bin/sh
# Rebuilds a jpackage DMG with the app inside signed by sign.sh, jpackage signs its own copy ad-hoc.
# Usage: sign-dmg.sh <dmg> <entitlements>, with the same environment as sign.sh.
set -eu
DMG="$1"
ENTITLEMENTS="$2"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'hdiutil detach "$WORK/mnt" -quiet 2>/dev/null || true; rm -rf "$WORK"' EXIT

mkdir "$WORK/mnt" "$WORK/contents"
# The DMG shows the license first, answer it so this runs unattended
yes | hdiutil attach -nobrowse -readonly -noverify -mountpoint "$WORK/mnt" "$DMG" >/dev/null
VOLUME=$(diskutil info "$WORK/mnt" | sed -n 's/^ *Volume Name: *//p')
# ditto keeps the Applications link, the background and the window layout
ditto "$WORK/mnt" "$WORK/contents"
hdiutil detach "$WORK/mnt" -quiet
rm -rf "$WORK/contents/.fseventsd" "$WORK/contents/.Trashes"

sh "$HERE/sign.sh" "$WORK/contents/CloudStream.app" "$ENTITLEMENTS"
# hdiutil sometimes fails with "Resource busy" on build machines, a moment later it works
for attempt in 1 2 3 4; do
  if hdiutil create -quiet -volname "${VOLUME:-CloudStream}" -srcfolder "$WORK/contents" -format UDZO -imagekey zlib-level=9 -ov "$WORK/signed.dmg"; then break; fi
  if [ "$attempt" = 4 ]; then echo "hdiutil create failed" >&2; exit 1; fi
  echo "hdiutil create failed, trying again" >&2
  sleep 10
done
mv -f "$WORK/signed.dmg" "$DMG"
echo "Signed $DMG"
