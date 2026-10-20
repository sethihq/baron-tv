#!/usr/bin/env bash
# Build the phone app and share it over the Wi-Fi: scan the QR code (or open the link) on the phone,
# download BaronTV.apk and tap it to install. The phone must be on the same Wi-Fi as this Mac, and
# Android asks once to allow installs from the browser (or Files). Ctrl+C stops sharing.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain 2>&1 | grep -v "SDK XML" || true
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "ERROR: the build made no APK" >&2; exit 1; }
mkdir -p build/share
cp "$APK" build/share/BaronTV.apk
IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1)
PORT=8090
URL="http://$IP:$PORT/BaronTV.apk"
uvx --quiet --from qrcode python -c 'import qrcode, sys; q = qrcode.QRCode(border=2); q.add_data(sys.argv[1]); q.print_ascii(invert=True)' "$URL"
echo "On the phone, open: $URL"
echo "Sharing until Ctrl+C."
python3 -m http.server "$PORT" --bind "$IP" --directory build/share
