#!/bin/sh
# Signs CloudStream.app with a fixed certificate. jpackage signs every build with a new ad-hoc
# signature, which macOS treats as a new app, so it forgets the permissions the user granted.
#
# Usage: sign.sh <app> <entitlements>, with SIGN_IDENTITY (certificate name or SHA-1) and optionally
# SIGN_KEYCHAIN and SIGN_KEYCHAIN_PASSWORD set.
set -eu
APP="$1"
ENTITLEMENTS="$2"

if [ -n "${SIGN_KEYCHAIN:-}" ] && [ -n "${SIGN_KEYCHAIN_PASSWORD:-}" ]; then
    security unlock-keychain -p "$SIGN_KEYCHAIN_PASSWORD" "$SIGN_KEYCHAIN"
fi

sign() {
    if [ -n "${SIGN_KEYCHAIN:-}" ]; then
        codesign --force --timestamp=none --options runtime --keychain "$SIGN_KEYCHAIN" -s "$SIGN_IDENTITY" "$@"
    else
        codesign --force --timestamp=none --options runtime -s "$SIGN_IDENTITY" "$@"
    fi
}

# Inside out: native libraries and helper executables, then the bundled runtime, then the app
find "$APP/Contents" -type f ! -path "$APP/Contents/MacOS/*" \( -name "*.dylib" -o -name "*.jnilib" -o -perm -u+x \) | while read -r file; do
    if file -b "$file" | grep -q "Mach-O"; then
        if [ -x "$file" ] && ! file -b "$file" | grep -q "dynamically linked shared library"; then
            sign --entitlements "$ENTITLEMENTS" "$file"
        else
            sign "$file"
        fi
    fi
done
sign "$APP/Contents/runtime"
sign --entitlements "$ENTITLEMENTS" "$APP"
codesign --verify --deep --strict "$APP"
echo "Signed $APP with $SIGN_IDENTITY"
