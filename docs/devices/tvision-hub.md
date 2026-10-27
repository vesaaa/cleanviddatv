# TVision Hub (SEI Robotics, S905X2): a one-session report with a working list

Report from [#577](https://github.com/Gathin23/cleanyourtv/issues/577): one session, same-day
results only. That is why the seven packages are recorded here instead of `examples/` -
CONTRIBUTING asks for a few days of living with a list, and nobody has rebooted, stood by or
restored this one yet.

## Identify the device

| Field | Reported value |
|---|---|
| Retail name | TVision Hub (T-Mobile's Google TV dongle) |
| Maker | SEI Robotics |
| SoC | Amlogic S905X2 |
| Android | Google TV, with a T-Mobile operator layer |
| Build fingerprint | not recorded in the report |

The identity line is thin on purpose: the report does not carry `ro.build.fingerprint`,
`ro.product.device` or the Android patch level, so match the packages below against
`dumpsys package` output before applying anything here to another TVision unit, and do not assume
an OTA left them in the same place.

## What the owner disabled

Seven packages, `pm disable-user --user 0`, in the same session:

| Package | What it is |
|---|---|
| `com.nes.tvbugtracker` | bug tracker |
| `com.nes.tv.bugreportsender` | bug-report sender |
| `com.google.android.tvrecommendations` | recommendation rows |
| `com.sei.hitv.explore` | SEI's content browse app |
| `com.google.android.videos` | Play Movies |
| `com.android.camera2` | camera app (the dongle has none) |
| `com.google.android.play.games` | Play Games |

Nothing is uninstalled, so `tools/restore.sh` can put all seven back. The report does not name the
stock Home package: read it before undoing a handover, because `restore.sh` needs it as its
launcher argument on a set whose stock launcher is not `com.google.android.tvlauncher`:

```sh
TV_HOST=<hub ip> tools/venv/bin/python -I tools/tv.py \
  'cmd package resolve-activity -c android.intent.category.HOME'
```

## Home

The launcher was built, installed and confirmed as Home on this hub, with a clean home screen
afterwards - so the operator layer on this dongle does not block a third-party Home the way some
carrier builds do. The report does not say whether `com.google.android.tungsten.setupwraith` had to
go first (on the onn 4K Pro in the same report it did, and the installer rolled the handover back
until it was disabled), so check the Home owner with `dumpsys activity activities | grep
mFocusedApp` after the takeover instead of trusting the installer's success line.

## Two connection quirks

- **ADB did not connect until the hub was restarted.** Nothing on the host side changed; the same
  `adb connect` worked after the reboot.
- **The first build run hung at the TV-connect step of `launcher/build-and-install.sh`.** An
  `adb disconnect` followed by a re-run finished normally. If the build script stalls on an Amlogic
  dongle, disconnect and reconnect before reading it as a build failure.

## Limits

One session, no reboot or standby cycle, no restore run, no screenshots. Treat the list as a
starting point that has been seen to work once.
