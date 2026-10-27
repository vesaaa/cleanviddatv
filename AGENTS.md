# AGENTS.md

Instructions for coding agents working on CleanViddaTV: a fork of CleanYourTV
(github.com/Gathin23/cleanyourtv, snapshot 5773336) narrowed to China-market Hisense / Vidda TVs
(VIDAA_TV, MediaTek MT9653, Android 11, the 聚好看 JuUI launcher `com.jamdeo.tv.vod`). People start with README.md
(Chinese).

## Layout

| Path | What it is |
|---|---|
| `install-vidda.sh` | One-step install: model check, debloat, launcher handover, restore record, ADB off. |
| `launcher/` | The launcher, package `io.github.vesaaa.cleanviddatv`, plain Java, `minSdk` 26. Hisense defaults live in `res/values/config.xml`. |
| `launcher/.../ViddaTools.java`, `LocalAdb.java` | The ADB switch and the on-TV restore (a minimal ADB client for 127.0.0.1:5555). |
| `launcher/.../BootHomeActivity.java` | Direct-boot-aware Home for the locked part of boot; without it an MT9653 set with 聚好看 disabled never finishes booting. |
| `examples/` | The tested MT9653 lists: `vidda-mt9653` (the one install-vidda.sh applies), `hisense-65d62qh`, `hisense-e5q-pro`. |
| `docs/devices/hisense-65d62qh.md` | Field notes for this platform: boot, Home key, inputs, a USB HID freeze. |
| `tools/` | ADB client (`tv.py`), `disable.sh` / `restore.sh` with their safety checks, `check.py`, `adb-closed.py`, themes. |
| `tests/` | Python suite, including `install-vidda.sh` against a simulated TV. |

## Rules

- Disable, never uninstall. Every change must be undone by the in-launcher restore and by `tools/restore.sh`.
- Never disable `com.hisense.tv.tiletransition` (HDMI switching depends on it) or the firmware updaters.
- Disable 聚好看 only after the new launcher is confirmed as Home (build-and-install.sh enforces this).
- ADB on this firmware needs no authorisation: anything that turns it on must also turn it off.
- No secrets or identifiers in commits: no IPs, Wi-Fi names, serials, ADB keys.
- Verify launcher changes on a real TV: cold boot, the remote's Home button, HDMI, the restore.

## Checks

- `tools/venv/bin/python -m unittest discover -s tests`
- `cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease` (lint: zero errors)
