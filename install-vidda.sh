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
[ -x "$PY" ] || { echo "请先运行：python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
LIST="${LIST:-examples/vidda-mt9653/debloat.txt}"
PKG=io.github.vesaaa.cleanviddatv
# The stock launcher (聚好看); overridable only to rehearse the flow on another Android 11 set or an emulator.
STOCK="${STOCK_PKG:-com.jamdeo.tv.vod}"
STOCK_HOME="${STOCK_HOME_COMPONENT:-com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity}"
if [ -n "${APK_FILE:-}" ]; then APK_FILE="$(cd "$(dirname "$APK_FILE")" && pwd)/$(basename "$APK_FILE")"; export APK_FILE; fi

tv() { "$PY" -I tools/tv.py "$@" | tr -d '\r'; }

echo "== 1/6 检查电视型号"
MODEL=$(tv 'getprop ro.product.model')
SDK=$(tv 'getprop ro.build.version.sdk')
echo "型号=$MODEL  SDK=$SDK"
if [ "$MODEL" != VIDAA_TV ] || [ "$SDK" != 30 ]; then
  if [ "${FORCE_MODEL:-0}" != 1 ]; then
    echo "错误：CleanViddaTV 只在 VIDAA_TV（Android 11，SDK 30）上验证过。电视没有任何改动。" >&2
    echo "如果仍要尝试，加上 FORCE_MODEL=1 重新运行。" >&2
    exit 1
  fi
  echo "FORCE_MODEL=1：在未验证的机型上继续。"
fi
tv "pm path $STOCK" | grep -q '^package:' || { echo "错误：没有找到 $STOCK（聚好看），这不是国行海信 / Vidda 的系统。" >&2; exit 1; }

echo "== 2/6 记录当前状态（用于恢复）"
# Packages disabled before this run are never re-enabled by the restore; Home and the accessibility
# services go back to what they are now. A second run keeps the first run's record.
BEFORE=$(tv 'pm list packages -d' | sed -n 's/^package://p' | sort)
EXISTING=$(tv "settings get global cleanviddatv_disabled")
A11Y_BEFORE=$(tv 'settings get secure enabled_accessibility_services')
case "$A11Y_BEFORE" in null) A11Y_BEFORE="" ;; esac

echo "== 3/6 按清单停用预装内容（$LIST）"
tools/disable.sh "$LIST"

echo "== 4/6 安装 CleanViddaTV 并设为默认桌面"
STOCK_LAUNCHER=$STOCK HOME_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1 launcher/build-and-install.sh

echo "== 5/6 配置遥控器按键服务，写入恢复记录"
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
echo "恢复记录：$(printf '%s' "$NEW" | tr ',' '\n' | grep -c .) 个应用"
tv "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" && echo "已开通桌面设置里的 ADB 开关和一键恢复"

echo "== 6/6 完成"
echo "请重启一次电视，确认开机直接进入 CleanViddaTV，遥控器主页键正常。"
echo "想换回原厂桌面：在 CleanViddaTV 的「设置」按钮上长按确认键 → CleanViddaTV 设置 → 维护 → 恢复原厂桌面。"
if [ "${KEEP_ADB:-0}" = 1 ]; then
  echo "KEEP_ADB=1：ADB 保持打开。关闭之前，同一网络里的任何设备都能控制这台电视。"
else
  echo "正在关闭 ADB（以后需要时，在桌面设置的「维护」里打开）。"
  tv 'settings put global adb_enabled 0' >/dev/null 2>&1 || true
fi
