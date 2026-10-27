# Haier (HaierATVT32DVB, dupont): a Google TV 14 observed-state note

Observed-state note from a Haier set that shares the Haier/CVTE `dupont` platform documented in
the [Durabrand DURAT32ISDB owner report](durabrand-durat32isdb.md) (Android TV 11), but ships
Google TV 14 and a newer firmware. This is **not** a `debloat.txt`: the unit had already been
modified before the probe, so a "before" state cannot be captured and CONTRIBUTING's "lived with
it for a few days" bar is not met. The purpose of the note is narrower:

- Record what the `dupont` platform's factory stack looks like on Google TV 14 build
  `UKNV.260408.001`, so the Durabrand trap and the Haier `examples/` entries can be read in
  context.
- Name `com.cvte.tv.fac.menu` as a factory-stack package observed on this unit that the current
  `PROMPT.md` list does not spell out literally.

## Identify the firmware

| Field | Value |
|---|---|
| Retail name | Haier (32-inch, HaierATVT32DVB variant) |
| `ro.product.model` | `Haier Android TV DVB` |
| `ro.product.name` | `HaierATVT32DVB` |
| `ro.product.device` | `dupont` |
| `ro.product.board` | `dupont` |
| SoC family | MediaTek (Haier/CVTE board) |
| Android / SDK | Google TV 14 / 34 |
| `ro.build.display.id` | `UKNV.260408.001` |
| `primaryCpuAbi` | `armeabi-v7a` |
| Panel | 3840x2160 physical, `wm size` override 1920x1080, 320 dpi |
| RAM | about 1.4 GB (`MemTotal: 1399312 kB`) |

## Related units in the repo

- [`examples/haier-android-tv-dvb2k/`](../../examples/haier-android-tv-dvb2k/debloat.txt) —
  HaierATV**T56**DVB, device `broadway`, Android TV 11.
- [`examples/haier-h32s80efx/`](../../examples/haier-h32s80efx/debloat.txt) — HRT56EU, device
  `deto`, Google TV 14, CVTE board, Thailand.
- [`docs/devices/durabrand-durat32isdb.md`](durabrand-durat32isdb.md) — same `dupont` platform,
  Android TV 11.

Package names overlap with these but the set is not identical, so apply any of those lists only
after checking each name with `dumpsys package` on the specific unit.

## The `dupont` factory stack on Google TV 14, as observed

All of the following was read with `pm list packages -a` and `dumpsys package <name>`. None of
these packages was launched: opening one is the trap documented in the Durabrand note.

| Package | `codePath` | user 0 state |
|---|---|---|
| `com.cvte.tv.hkcfac` | `/product/app/HKCFacAT` | `installed=false, enabled=3` |
| `com.cvte.tv.fac.menu` | `/product/app/CVTE_fac_settings` | `installed=true, enabled=1` |
| `com.cvte.r_agent_overlay` | `/vendor/overlay/R_Agent_overlay` | `installed=true, enabled=0` |
| `com.at.factory.androidtvaidlservice` | `/system_ext/app/Service_haier` | `installed=true, enabled=1` |
| `com.mediatek.wwtv.setupwizard` | `/system_ext/app/CvteLiveTVSetupWizard` | `installed=true, enabled=1` |
| `com.mediatek.wwtv.tvcenter` | `/system_ext/priv-app/LiveTV` | `installed=true, enabled=0` |
| `mediatek.factorymenu.ui` | `/system_ext/app/HFactory` | `installed=false, enabled=3` |
| `com.haier.tv.hmagicboard` | `/product/app/HMagicBoard` | `installed=true, enabled=1` |

- `com.cvte.tv.hkcfac` is already named by [`PROMPT.md`](../../PROMPT.md) and by the Durabrand
  note; on this unit it is not installed for user 0.
- `mediatek.factorymenu.ui` is already named by `PROMPT.md`; on this unit it is not installed for
  user 0 either.
- `com.at.factory.androidtvaidlservice` is covered by the `com.at.factory.*` glob in `PROMPT.md`.
- `com.mediatek.wwtv.tvcenter` matches the Live TV entry in both existing Haier `examples/`.

## `com.cvte.tv.fac.menu` is a different package from `com.cvte.fac.menu`

The repo already names `com.cvte.fac.menu` (no `.tv.`) in
[`examples/vision-2024vision43stv/`](../../examples/vision-2024vision43stv/debloat.txt),
[`examples/artel-cvte-mt5863/`](../../examples/artel-cvte-mt5863/debloat.txt) and
[`examples/challenger-32t22bt/`](../../examples/challenger-32t22bt/debloat.txt).

The package observed on this unit is `com.cvte.tv.fac.menu` (with `.tv.` in the middle), at
`/product/app/CVTE_fac_settings`. It is enabled by default and declares a `MAIN` + `DEFAULT`
activity. On the name alone it reads as a factory-settings surface in the same family as
`com.cvte.tv.hkcfac`, and the "fac menu" wording of the `PROMPT.md` warning already covers it in
spirit, but no literal entry in that list is spelled with `.tv.` between `cvte` and `fac.menu`.

What this note does **not** claim:

- Whether opening `com.cvte.tv.fac.menu` sets `customer_factory_mode=true` or
  `vendor.mtk.factory.disable.input=1` the way `com.cvte.tv.hkcfac` does on the Durabrand unit.
  Not tested.
- Whether the keycode-218 exit sequence from the Durabrand note applies to it. Not tested.

## Validation scope

- The unit was modified before this note was written: `com.google.android.tvlauncher` and
  `com.google.android.tungsten.setupwraith` were already `pm disable-user --user 0`, and a
  third-party launcher holds the `android.app.role.HOME` role. There is no stock baseline, and
  this note cannot graduate to `examples/`.
- The `installed=false, enabled=3` state for `com.cvte.tv.hkcfac` and `mediatek.factorymenu.ui`
  on user 0 may be the factory default on this build or the result of a prior `pm disable-user`
  by the owner; this note cannot distinguish between the two.
- No reboot campaign was run. The `com.at.factory.factorymanager` boot-time re-enable behaviour
  reported for the Durabrand note was not reproduced here, because the relevant packages were
  already in their current state when the probe started.
- Metadata was read on 2026-10-21 over ADB on the LAN; no activity was launched.
