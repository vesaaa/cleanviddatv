# C71KW-200 (AT&T Osprey): carrier-update caveat and launcher verification

Checked on one physical Android TV set-top box on 2026-10-21. The box already had
a working replacement Home and owner-chosen package disables. This report covers
an in-place update to upstream main
[`3e996739`](https://github.com/Gathin23/cleanyourtv/commit/3e996739ce0b470b51c1c438bf5d0559d1c9e7eb),
including a reboot. It does not supply a package-disable list or establish the
initial carrier-launcher handover on factory firmware.

## Device

| Field | Observed value |
|---|---|
| Hardware | Samsung C71KW-200 / AT&T TV Osprey |
| Android / API | `11` / `30` |
| ABI | `armeabi-v7a` |
| Firmware | `RTT4.250301.001.release-r-sam-25Q3.2-refresh-cert-1` |
| Security patch | `2025-06-05` |

The device and firmware details were previously shared in
[discussion #254](https://github.com/Gathin23/cleanyourtv/discussions/254).
Earlier launcher validation is recorded in
[PR #412](https://github.com/Gathin23/cleanyourtv/pull/412); the checks below
describe the newer, unmodified upstream application source.

## Carrier-update takeover during earlier setup

As recorded in [discussion #254](https://github.com/Gathin23/cleanyourtv/discussions/254),
the carrier update installed DIRECTV interface `34.4.9.255`, took back the Home
preference and restarted the box after earlier Home/reboot checks had passed.
The observed updater and installer were `com.att.ngc.swdl.service` and
`com.directv.tv.vplinstaller`.

The owner approved disabling those two packages while keeping the carrier Home
`com.directv.tv.dtvlauncher` disabled. Replacement Home persisted through the
subsequent reboot. Google Play stayed enabled.

**Tradeoff:** that choice pauses automatic vendor firmware updates. It records
an informed decision on this firmware, not a universal Osprey recommendation
or a several-day-tested debloat profile. The launcher-update checks below
preserved the existing package policy.

The earlier setup retained `com.att.ngc.remote.service`,
`com.att.ngc.remote.settings`, `com.uei.quicksetsdk.directv`, Bluetooth/HDMI
services, keyboard, Play and required Google services. Another carrier interface,
`com.att.tv.openvideo`, was observed; its name alone does not establish that it
is safe to disable.

## Build and configuration

The launcher's app source and resources were byte-for-byte upstream. Only the
local `versionCode` was raised above the previously installed build, and the
existing signing key was retained. The package stayed `com.gathin.tvhome`;
there was no uninstall or app-data reset. No unmerged performance PR or local launcher
fix was carried into this build.

Six dock favorites and the native saved order put 25 apps across the grid. The
layout and effects were set through the external theme `config.json`, using
these supported keys; the shared defaults need no changes:

```json
{
  "columns": 6,
  "rows_below_dock": 2,
  "fill_dock": false,
  "animations": true,
  "reduced_motion": true,
  "advanced_effects": false,
  "dock_title": true,
  "dock_row": true,
  "reorder_button": true,
  "show_stats": true,
  "sky_intensity": 100,
  "wallpaper": false,
  "module_cards": false,
  "app_library": false
}
```

The app-specific `dock_apps` and `hidden_apps` values were retained too. No
accessibility key service or new remote-button mapping was enabled in this run.

## Checks on the box

- All 25 expected app positions, the dock's left and right boundaries, and
  repeated Home presses were checked using injected D-pad events.
- Native Reorder apps moved VLC one place, saved it, and retained that move
  after a cold launcher restart. The move was then undone and saved. All 25
  original positions were checked again after the full reboot.
- Opening VLC from its tile and returning with Home worked.
- With VLC in front, an artificial `RUNNING_LOW` memory-trim notification was
  sent to the launcher. Home returned with its tiles. This
  checks the recovery path, not natural memory-pressure performance.
- After reboot, Home still resolved to `com.gathin.tvhome/.MainActivity`.
  The installed APK matched the signed build, the external theme and disabled
  package set were unchanged, and the crash buffer had no launcher crash.

For automated reorder checks, use D-pad input. During this session an injected
touch tap on the Reorder pill left focus on the grid; a D-pad event followed by
Home restored tile focus. The completed reorder checks above used D-pad events
throughout, including selecting the Reorder pill.

Android Settings left Home classified as a foreground process on this unit, so
ActivityManager refused a `BACKGROUND` trim request. The completed trim check
used `RUNNING_LOW` with VLC in front instead.

For reboot automation, `sys.boot_completed=1` preceded a focused Home window.
Wait for the launcher to be resumed and its window focused as well. There was
no matched comparison of cold-boot latency.

The same app source passed 134 release JVM tests, release lint with zero errors,
and the release build; the upstream Python suite passed all 206 tests.

## Owner follow-up (October 21)

- The owner confirmed that replacing the remote batteries restored expected
  volume and power behavior. The earlier unresolved normal-remote issue is
  resolved; this does not independently validate HDMI-CEC control.
- In normal use, the owner reported reliable AirPlay dashboard display with
  simultaneous audio through the TV. This is an owner report from that session,
  not a measured endurance test or a protected-content playback test.

## Scope

This was a launcher-update check on this firmware and configuration. Physical
remote long presses, streaming-provider playback/DRM, voice, CEC, casting,
package changes during an active reorder, and the optional wallpaper, module
card and app-library paths were not re-tested during the automated launcher
checks. Later owner observations are identified separately above. The existing
carrier and package choices were preserved; no new package-disable guidance
follows from these checks.

AI-assisted with Codex; runtime checks were performed on the physical box.
