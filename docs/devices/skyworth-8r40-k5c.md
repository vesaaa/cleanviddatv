# Skyworth / Coocaa 8R40_K5C (China domestic, Android 8.0)

Report for the mainland-China Coocaa/Skyworth firmware (`com.tianci.*` / `com.coocaa.*`) on a Realtek RTD285x set. Source: issue #692. Related older-sibling-in-family report: #417 (8R145_P3DM / K3 Pro, Android 11), whose notes do **not** transfer directly.

> **Warning:** Disabling the wrong package crashes the "About this TV" page, which is the only place ADB can be turned on. Recovery is a factory reset. Read the traps before running any debloat list.

## Device

| Field | Value |
| :--- | :--- |
| Retail model | Skyworth / Coocaa K5C (China domestic) |
| Product / device | `8R40_K5C` |
| SoC / board | Realtek `rtd285x` |
| Kernel | Linux 4.4.3, armv7l (32-bit userspace, armeabi-v7a only) |
| Android / SDK | 8.0.0 / 26 |
| Build | `OPR6.170623.012 dev-keys`, type `user`, `ro.debuggable=0` (adb root refused) |
| Security patch | 2017-08-05 |
| SELinux | Permissive |
| RAM | 1 GB |
| Packages | 56 total |

## Result

16 packages disabled, RAM freed, boot ad removed. Verified across a reboot with ADB reconnecting on its own and the stock home screen intact. List: `examples/skyworth-8r40-k5c/debloat.txt`.

## Traps

### Trap 1: debloating can destroy the only ADB entry

- There is no `com.android.settings` and no Developer options page. `com.tianci.setting` has no develop/debug activities.
- The ADB switch is in `com.coocaa.factory`, reached via Settings → 基本设置 → 设备信息 → 更多信息 → up up down down left right left right.
- That page depends on `com.tianci.de` (process `com.tianci.de:TianciDaemons`) and `com.skyworthdigital.stb.dataprovider`. Disabling either crashes About instantly. Wi-Fi ADB and port 5555 are then unreachable and a factory reset is the only recovery (the reporter needed two).
- On the second attempt `com.coocaa.sky.ccapi` and/or `com.tianci.user` caused the same crash. Treat `ccapi` as must-keep. `com.tianci.user` was in the final verified-safe list, so the evidence is inconclusive; disable it last and re-check About.
- **Escape hatch (no root):** `com.tianci.setting/.factorysetting.FactorySetting` declares the unguarded public action `coocaa.intent.action.TIANCI_FACTORY`, so any app can open the factory menu. Install Activity Launcher (`de.szalkowski.activitylauncher.oss`, has a leanback entry so it appears in the TV launcher) **before** debloating and test it.
- **Recommended workflow:** apply in small batches, reopen About after each batch.

### Trap 2: HOME is hardcoded; a third-party launcher cannot own it

- `cmd package resolve-activity -c android.intent.category.HOME` returns "No activity found". No package, the stock launcher included, declares HOME.
- `com.tianci.system` (SkySystemService) starts the home page from `persist.service.homepage.pkg` / `.cls`, and the remote's Home key goes through `HotKeyHandler` to the same pair.
- The props accept a third-party launcher at runtime but are rewritten at boot from read-only `/system/pcfg/8R40_K5C/config/general_config.xml` (`DEFAULT_HOMEPAGE` = `com.tianci.movieplatform/com.coocaa.homepage.vast.HomePageActivity`). Changing this needs root.
- What breaks boot:
  - `cmd package set-home-activity ...` is refused: `cannot be home on user 0` (it worked on the Android 11 unit in #417).
  - `pm disable-user com.tianci.movieplatform` gives a black screen; `sys.boot_completed` never set.
  - `pm disable-user app.orchard.tv` (Aerial launcher on some units) gives a black screen too. Never disable the current launcher.
  - `setprop persist.service.homepage.switch 0` plus pointing pkg/cls at a third-party launcher: after reboot `adbd` did not start and About crashed. Leave that prop unset.
- Practical answer for now: keep the stock launcher and open other launchers from My Apps.

### Trap 3: boot video ad and autostart queue

- Ad: 15 s video at `/data/local/bootvideo.ts` (system-owned), played by `com.skyworth.tv`, ad SDK in `com.coocaa.adsdk`.
- Fix (survives reboot, no root):
  ```
  tools/tv.py 'setprop persist.sys.bootvideo.time 1'
  ```
- `bootQueue.xml` in the same pcfg folder starts `com.tianci.push` at 1.2 s and `com.tianci.ad` at 5.0 s. Also `persist.sys.ad.startboot = com.tianci.ad/ui.BootViewActivity`.
- Other pcfg switches that exist but need write access to `/system`: `CURRENT_AD_SERVER` (`hoisin.coocaatv.com`), `HIDE_LAUNCHER`.

### Trap 4: input quirks that break automation

- TVBox-based players (e.g. 影视仓 `com.tencent.qqlivehuawei`) swallow injected `KEYCODE_HOME` / `KEYCODE_BACK`. The physical remote Home still works.
- The stock home ignores `input tap` and only responds to `KEYCODE_DPAD_*`. Screenshot-driven clicking fails: uiautomator bounds are 1280x720 but screencap is scaled.

## Must stay enabled

| Package | Why |
| :--- | :--- |
| `com.tianci.de` | About / device info (ADB path) |
| `com.skyworthdigital.stb.dataprovider` | About / device info (ADB path) |
| `com.coocaa.sky.ccapi` | Core API; About crashed after disabling it |
| `com.tianci.movieplatform` | Stock launcher, boot crash if disabled |
| `com.coocaa.factory` | Factory menu, holds the ADB toggle |
| `com.tianci.localmedia` | USB APK install |
| `com.skyworth.tv` | Factory menu, picture settings, accessibility |
| `com.coocaa.tvmanager` | TV management |
| `com.coocaa.guide`, `com.coocaa.miracast` | System guide and casting |

## Verified safe to disable (16)

`com.tianci.ad`, `com.tianci.appstore`, `com.tianci.push`, `com.tianci.user`, `com.tianci.ipc`, `com.tianci.autoinstaller`, `com.baidu.oases`, `com.coocaa.mall`, `com.coocaa.activecenter`, `com.coocaa.app_browser`, `com.coocaa.messageprovider`, `com.coocaa.tvpitv`, `com.coocaa.theme.child`, `com.icoolme.tv.weather`, `com.skyworth.blockchain`, `com.skyworth.smarthome_tv`.

The first six (`ad`, `push`, `appstore`, `user`, `ipc`, `autoinstaller`) are already disabled from the factory on this unit, so they are the lowest risk.

## Related report: Coocaa 8R145 / K3 Pro (#417)

Same vendor family (`com.tianci.*` / `com.coocaa.*`), but a different firmware: Realtek RTD2885N, Android 11, board `8R145_P3DM`. Its notes and package list do **not** transfer to this TV, and this one does not transfer to that one.

| | 8R145 (#417, Android 11) | 8R40_K5C (this guide, Android 8.0) |
| :--- | :--- | :--- |
| `cmd package set-home-activity` | accepted | refused: `cannot be home on user 0` |
| Package declaring `android.intent.category.HOME` | the Coocaa launcher | none at all |
| Disabling `com.tianci.movieplatform` | boot hang, factory reset | black screen at boot, factory reset |
| `com.coocaa.guide` | disabled in the #417 list | **must stay enabled** here |
| About page / ADB lockout (Trap 1) | not reported | the main hazard on this firmware |

Packages that appear in both reports as disabled without problems: `com.tianci.ad`, `com.tianci.push`, `com.tianci.autoinstaller`, `com.coocaa.app_browser`, `com.coocaa.messageprovider`.

Do not copy either list onto the other TV. Packages that appear only in the #417 list (for example `com.skyworth.aisense`, `com.coocaa.sysreport`, `com.coocaa.tvpi`) were not tested on the 8R40_K5C and are not in this one.

## Open questions (from #692)

1. Can a third-party launcher persist across reboot without root (`HIDE_LAUNCHER`, broadcast `com.skyworth.broadcast.homepage.switch`, hotel mode `persist.sky.hotel_switch`)? Untested.
2. Root on RTD285x with kernel 4.4.3: CVE-2019-2215 PoCs are arm64 and need a 4 GB mmap; the arm32 port needs kernel symbol offsets that require the boot image.