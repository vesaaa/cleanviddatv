#!/usr/bin/env bash
# CleanViddaTV: clean up a China-market Hisense / Vidda TV (VIDAA_TV, MediaTek MT9653, Android 11) and make
# CleanViddaTV its home screen, in one go. Run from the repo root, with the TV's ADB on (USB debugging):
#
#   TV_HOST=192.168.1.50 ./install-vidda.sh
#
# Options (environment):
#   APK_FILE=<path>   install a prebuilt CleanViddaTV APK instead of building one (no JDK / Android SDK needed)
#   LIST=<file>       the debloat list (default examples/vidda-mt9653/debloat.txt)
#   KEEP_ADB=1        leave ADB on at the end (default: off; it can be turned on again in the launcher's settings)
#   FORCE_MODEL=1     run on a set that is not VIDAA_TV / Android 11 (untested there)
#
# Steps: check the model, record what to restore, disable the list (tools/disable.sh: backup, Home guard),
# install the launcher and hand Home over (launcher/build-and-install.sh, which disables 聚好看 only after the
# new Home is confirmed), leave HomeKeyService as the only accessibility service, write the restore record onto
# the TV for the launcher's "Restore the original home screen", then turn ADB off.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
export TV_HOST
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
LIST="${LIST:-examples/vidda-mt9653/debloat.txt}"
PKG=io.github.vesaaa.cleanviddatv
# The stock launcher (聚好看); overridable only to rehearse the flow on another Android 11 set or an emulator.
STOCK="${STOCK_PKG:-com.jamdeo.tv.vod}"
STOCK_HOME="${STOCK_HOME_COMPONENT:-com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity}"
if [ -n "${APK_FILE:-}" ]; then APK_FILE="$(cd "$(dirname "$APK_FILE")" && pwd)/$(basename "$APK_FILE")"; export APK_FILE; fi

tv() { "$PY" -I tools/tv.py "$@" | tr -d '\r'; }

echo "== 1/6 Checking the TV"
MODEL=$(tv 'getprop ro.product.model')
SDK=$(tv 'getprop ro.build.version.sdk')
echo "model=$MODEL sdk=$SDK"
if [ "$MODEL" != VIDAA_TV ] || [ "$SDK" != 30 ]; then
  if [ "${FORCE_MODEL:-0}" != 1 ]; then
    echo "ERROR: CleanViddaTV is tested on VIDAA_TV with Android 11 (SDK 30) only. Nothing was changed." >&2
    echo "Run again with FORCE_MODEL=1 to try anyway." >&2
    exit 1
  fi
  echo "FORCE_MODEL=1: continuing on an untested set."
fi
tv "pm path $STOCK" | grep -q '^package:' || { echo "ERROR: $STOCK (聚好看) is not installed: not a JuUI set." >&2; exit 1; }

echo "== 2/6 Recording the current state"
# Packages disabled before this run are never re-enabled by the restore; Home and the accessibility
# services go back to what they are now. A second run keeps the first run's record.
BEFORE=$(tv 'pm list packages -d' | sed -n 's/^package://p' | sort)
EXISTING=$(tv "settings get global cleanviddatv_disabled")
A11Y_BEFORE=$(tv 'settings get secure enabled_accessibility_services')
case "$A11Y_BEFORE" in null) A11Y_BEFORE="" ;; esac

echo "== 3/6 Disabling the list ($LIST)"
tools/disable.sh "$LIST"

echo "== 4/6 Installing CleanViddaTV and handing Home over"
STOCK_LAUNCHER=$STOCK HOME_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1 launcher/build-and-install.sh

echo "== 5/6 Accessibility helper and restore record"
# Only this launcher's helper: with the vendor's assistant service filtering keys as well, a USB or Bluetooth
# mouse froze all input on a 65D62QH (docs/devices/hisense-65d62qh.md).
tv "settings put secure enabled_accessibility_services $PKG/.HomeKeyService; settings put secure accessibility_enabled 1"
AFTER=$(tv 'pm list packages -d' | sed -n 's/^package://p' | sort)
NEW=$(comm -13 <(printf '%s\n' "$BEFORE") <(printf '%s\n' "$AFTER") | paste -sd, -)
if [ -n "$EXISTING" ] && [ "$EXISTING" != null ]; then
  # Installed before: add to that record rather than lose what the first run disabled.
  NEW=$(printf '%s,%s\n' "$EXISTING" "$NEW" | tr ',' '\n' | sed '/^$/d' | sort -u | paste -sd, -)
else
  tv "settings put global cleanviddatv_stock_home $STOCK_HOME"
  tv "settings put global cleanviddatv_a11y_before '${A11Y_BEFORE}'"
fi
tv "settings put global cleanviddatv_disabled '$NEW'"
echo "restore record: $(printf '%s' "$NEW" | tr ',' '\n' | grep -c .) packages"
tv "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" && echo "ADB switch and restore enabled in the launcher's settings"

echo "== 6/6 Done"
echo "Restart the TV once and check that it comes up on CleanViddaTV, and that the remote's Home button works."
echo "To go back: in CleanViddaTV, hold OK on the Settings button > Launcher settings > Restore the original home screen."
if [ "${KEEP_ADB:-0}" = 1 ]; then
  echo "KEEP_ADB=1: ADB stays on. Anyone on this network can control the TV until you turn it off."
else
  echo "Turning ADB off (turn it on again from the launcher's settings when you need it)."
  tv 'settings put global adb_enabled 0' >/dev/null 2>&1 || true
fi
