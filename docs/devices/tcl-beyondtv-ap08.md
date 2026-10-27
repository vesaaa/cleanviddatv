# TCL BeyondTV AP08: initial setup report

Checked on a real TV in one session on 2026-10-21, including a restart and owner checks with the physical remote and phone casting. This is **not a several-day tested debloat list**. The package changes below record this owner's choices, not a list to apply unchanged. The retail model was not established.

## Device identifiers

| Field | Observed value |
|---|---|
| Model / device / product | `BeyondTV` |
| Board | `RTD2851` |
| Android / API | Android TV 9 / 28 |
| Build display | `PPR1.180610.011 release-keys` |
| Build incremental | `AP08` |
| Security patch | `2021-01-05` |
| Reported memory | `2097152 kB` |

This supplements the [BeyondTV example](../../examples/tcl-beyondtv/debloat.txt). Do not confuse AP08 with the [RT2851 AP06 example](../../examples/tcl-beyondtv-rt2851/debloat.txt), which has a different security patch. The trial used a locally built launcher reporting version 2.5 / versionCode 6; it does not validate every later upstream revision.

## Launcher and input configuration

The local launcher configuration used these values in `launcher/app/src/main/res/values/config.xml`; shared defaults do not need changing:

```xml
<string-array name="dock_apps">
    <item>com.google.android.youtube.tv</item>
    <item>com.netflix.ninja</item>
    <item>com.amazon.amazonvideo.livingroom</item>
    <item>com.android.vending</item>
</string-array>
<string-array name="hidden_apps">
    <item>com.tcl.tv</item>
</string-array>
<string name="live_tv_package" translatable="false">com.tcl.tv</string>
```

Replace the corresponding resource entries rather than adding duplicate names. Keep `com.tcl.tv` and `com.tcl.tvinput` enabled: hiding the TV tile is only a launcher presentation choice. Input services on this unit were `com.tcl.tvinput/.TDTVInputService` and `com.tcl.tvinput/.passthroughinput.TvPassThroughService`.

The original HOME activity was `com.google.android.tvlauncher/.MainActivity`. The replacement was installed and launched before disabling stock HOME, then set as HOME and checked with the Home key. `com.gathin.tvhome/.MainActivity` was the resumed activity, including after restart. `com.google.android.tungsten.setupwraith` was installed but did not advertise HOME on this unit and stayed enabled. The TCL key-customization and Android Settings fallback HOME activities stayed enabled too. No accessibility helper or remote scancode remapping was needed.

## Changes observed in this session

All changes used reversible user-0 package disabling, not uninstalling. There were 19 disabled packages before the session and 30 afterward. These eleven additions remained disabled after restart, with no unexpected changes to the disabled-package set:

| Package | Session choice / constraint |
|---|---|
| `com.tcl.bi` | TCL analytics |
| `com.google.android.feedback` | Feedback reporting |
| `com.google.android.tv.bugreportsender` | TV bug reporting |
| `com.tcl.tv.tclhome_passive` | Passive TCL Home promotion |
| `com.tcl.esticker` | Retail stickers |
| `com.google.android.tvrecommendations` | Stock-home recommendations |
| `com.google.android.leanbacklauncher.recommendations` | Stock-home recommendations |
| `com.google.android.tvlauncher` | Only after replacement HOME was installed and working |
| `com.tcl.tvweishi` | Cleaner; disabled after installation because it also exposes an installer service |
| `com.tcl.appmarket2` | Duplicate store; disabled after installation because it also exposes an installer service |
| `com.tcl.versionUpdateApp` | TV firmware updater, disabled at the owner's explicit request; not a default recommendation |

The remote firmware updater stayed enabled. No newly disabled package needed re-enabling during this session; longer-term behavior is unknown. Record original package states before making changes and restore those states when undoing them. Re-enable stock HOME before removing a replacement launcher.

YouTube, Netflix, Prime Video, Play Store, ZEE5, Google Play Movies & TV, YouTube Music and TCL Channel stayed enabled. Chromecast, voice assistant, Miracast and T-Cast stayed enabled. In particular, this owner did **not** apply the existing example's Play Movies or YouTube Music disables.

Ambiguous or input-related packages were retained, including `com.tcl.partnercustomizer` (T-key activities), `com.tcl.messagebox`, `com.tcl.usercenter`, `com.tcl.showmode`, `com.tcl.framework.custom` and `com.tcl.keycustomfunctionservice`. No conclusion about their safe removal follows from this report.

## Verification and limits

- The Inputs menu showed HDMI 1, HDMI 2, AV and Live TV. Selecting HDMI 1 opened `com.tcl.tv/.TVActivity` and its no-signal screen; Home returned to the launcher. **HDMI picture/audio with a connected source and tuner reception were not tested.**
- The owner confirmed all present physical remote buttons worked after restart, covering the requested Home, Input/Source, Settings, volume, voice/microphone and available app shortcuts. This is an owner check, not an exhaustive remote-event trace.
- At the owner's request, Android Private DNS was set to hostname mode with `dns.adguard-dns.com`. Connectivity validation passed and the owner confirmed video and sound in a streaming playback test. This does not establish playback in every retained app or block YouTube ads. Restore the previous Private DNS settings to undo it.
- The owner confirmed the TV appeared for phone casting and played video with sound after restart. Miracast and T-Cast were preserved but not separately exercised.
- The post-restart crash buffer was empty at inspection. Standby cycles, prolonged use, firmware updates and every app's functionality were not tested.
- Finally, the owner revoked USB-debugging authorizations, turned debugging off and restarted. A TCP connection attempt to port 5555 returned **connection refused**. Network debugging was not left enabled.

Private device dumps, network addresses, Wi-Fi names, screenshots containing network identifiers, signing keys and downloaded build tools are intentionally absent from this report.
