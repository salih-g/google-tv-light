# tv — make a slow Google TV fast again, without losing anything

[Türkçe](README.tr.md)

A 2 GB-RAM Google TV (TCL 50C645, Android 11) that took seconds to open an app and reloaded
everything from scratch after every switch. Two things fixed it, both reversible, nothing deleted:

1. **`tvclean.py`** – a small ADB-over-Wi-Fi tool that puts the manufacturer's ads, telemetry
   and "cleaner" apps to sleep in supervised batches, with a protected list it refuses to touch,
   a snapshot before every change and a one-command restore.
2. **`launcher/`** – a 3.7 MB Apple TV-style home screen: a dock of your apps, time-of-day
   wallpapers, inputs/Wi-Fi/settings pills, no content rows, no network access.

![Home screen on the TV](docs/screenshot.jpg)

## What it changed on this TV

| | Before | After |
|---|---|---|
| Free RAM, idle on the home screen | 440 MB | 616 MB |
| Swap in use | ~500 MB | ~110 MB |
| Home screen process | 333 MB (Google TV) | 36 MB |
| Packages starting at boot | 51 | 37 |
| TCL background services resident | ~300 MB | 0 |
| TCL "Safety Guard" killing Play Services | 11× in 5 min | gone |

Everything the owner used kept working: HDMI/AV inputs and their names, Live TV, the remote's
mic, Source and Settings keys, Chromecast, T-Cast and Miracast mirroring, USB media, Assistant,
Play Store, system updates.

## Safety model

- System packages are never uninstalled. They are **disabled for the user** (`pm disable-user`),
  which stops them running and costs nothing; `pm enable` brings them back. Factory reset also
  restores everything.
- [`protected.py`](protected.py) lists packages the tool **refuses** to disable even if a plan
  file names them: inputs, remote, casting, Assistant, Play services, keyboards, updates.
- Every mutating command writes a snapshot of all package states to `snapshots/` first;
  `restore <snapshot>` returns the TV to exactly that state.
- Every mutating command is a **dry run** until you add `--yes`.
- The only exception to "disable, never remove": TCL's Safety Guard is a `PERSISTENT` app that the
  system starts even while disabled, so it is removed *for the user only* (`pm uninstall -k --user 0`);
  its APK stays on the system partition and `readd` restores it.

Things we learned the hard way, now built into the tool:

- Rebooting within a few seconds of a `pm` change loses that change (the package manager flushes
  lazily). `tvclean.py reboot` waits first.
- adb-shell's file push times out on this TV's adbd; APKs go over the shell as base64 chunks with
  an md5 check.
- On Google TV, `cmd package set-home-activity` alone **cannot** make a third-party home take the
  Home key: Android picks the highest-priority HOME activity (Google's launcher has priority 2,
  the setup wizard 1, third-party apps are capped at 0) before it even looks at the preference.
  The stock home and setup wizard have to sleep. Both come back with one command.

## Requirements

- macOS or Linux with Python 3.10+ (no `adb` binary needed; the Wi-Fi client is pure Python).
- For the launcher: JDK 17 and the Android SDK command-line tools with `platforms;android-34`
  and `build-tools;34.0.0` (`build.sh` reads `ANDROID_HOME`, defaults to the Android Studio SDK path).
- An Android TV / Google TV on the same Wi-Fi with **USB debugging** on:
  Settings → System → About → press OK on *Build* 7 times → Developer options → USB debugging.

## Quick start

```bash
git clone https://github.com/salih-g/google-tv-light.git && cd google-tv-light
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

.venv/bin/python -I tvclean.py find        # finds the TV, saves its IP to .tv_host; accept the prompt on the TV
.venv/bin/python -I tvclean.py probe       # model, Android version, RAM, which app owns Home
.venv/bin/python -I tvclean.py snapshot baseline
```

### 1. Debloat, batch by batch

The plan files in [`plans/`](plans/) are the ones applied to a TCL 50C645 (R51MT05 firmware).
Other TVs have other packages: run `tvclean.py shell 'pm list packages'`, read
[`plans/README.md`](plans/README.md) and write your own lists. Then, one batch at a time:

```bash
.venv/bin/python -I tvclean.py mem                               # before
.venv/bin/python -I tvclean.py sleep plans/02-sleep-tcl-resident.txt        # dry run: shows the commands
.venv/bin/python -I tvclean.py sleep plans/02-sleep-tcl-resident.txt --yes  # apply
```

After each batch, test with the remote: input switching, mic, Chromecast, a streaming app,
the on-screen keyboard. If anything broke: `wake <same plan> --yes`.

```bash
.venv/bin/python -I tvclean.py reboot --yes     # waits for the package manager, then reboots
.venv/bin/python -I tvclean.py mem              # after
```

### 2. The launcher

Edit [`launcher/res/values/config.xml`](launcher/res/values/config.xml): dock apps, hidden apps,
the Live TV package, your name for the greeting. Wallpapers live in
[`launcher/wallpapers/`](launcher/wallpapers/) as `<scene>-<slot>.jpg` with slots
`sabah` (06–10), `gunduz` (10–17), `aksam` (17–20), `gece` (20–06); a scene can have any subset.

```bash
launcher/build.sh                                                   # -> launcher/build/tvhome.apk
.venv/bin/python -I tvclean.py install launcher/build/tvhome.apk \
    --grant=dev.salih.tvhome:android.permission.ACCESS_FINE_LOCATION --yes   # location = Wi-Fi name only
.venv/bin/python -I tvclean.py shell 'am start -n dev.salih.tvhome/.MainActivity'   # try it; Home still goes to Google
```

When you like it, give it the Home key (Google TV only needs the second line):

```bash
.venv/bin/python -I tvclean.py sleep plans/08-sleep-stock-home.txt --yes
.venv/bin/python -I tvclean.py home set dev.salih.tvhome/.MainActivity --yes
```

Undo: `tvclean.py wake plans/08-sleep-stock-home.txt --yes && tvclean.py home reset --yes`.

On the TV: D-pad moves, OK opens, **Down** opens the all-apps grid with an inputs row, **Back**
returns, **Menu** opens the inputs menu, **long-press OK** on a tile rearranges the dock or adds
an app to it. Every other remote key (volume, mute, mic, Source, Settings, channel) is left to
the system and keeps working.

**Remote app buttons.** On Google TV builds, TCL's key handler sends the Netflix, YouTube and
Prime Video buttons only to the stock Google TV home (and Disney+ and the T key to
`com.tcl.partnercustomizer`). With those asleep the buttons do nothing. The launcher ships an
accessibility service that takes just those buttons first and opens the apps directly; it does
not read the screen. The key map is `app_keys` in `config.xml` (`4062` Netflix, `4065` YouTube,
`4099` Prime Video, `4141` Disney+, `4160` Apple TV, `4020` T key = all-apps grid, `4140` Safety Guard key = Nuvio). Turn it on
once; `APP_AUTO_START` is TCL's own auto-start gate (Safety Guard's "Auto launch" switch), without
it the system refuses to bind the service:

```bash
.venv/bin/python -I tvclean.py shell 'appops set dev.salih.tvhome APP_AUTO_START allow && settings put secure enabled_accessibility_services dev.salih.tvhome/.KeyService && settings put secure accessibility_enabled 1'
```

Off: `tvclean.py shell 'settings put secure enabled_accessibility_services "" && settings put secure accessibility_enabled 0'`.

### 3. Optional: block the telemetry at the router

If you run AdGuard Home or Pi-hole, [`adguard/tcl-google-tv-rules.txt`](adguard/tcl-google-tv-rules.txt)
has the TCL endpoints this TV talked to (promo pushes, silent app installs, launcher content).
The [Perflyst Smart-TV list](https://perflyst.github.io/PiHoleBlocklist/SmartTV.txt) is a good companion.

## Commands

| | |
|---|---|
| `find` / `probe` / `mem` / `snapshot [label]` | read-only |
| `sleep <plan>` / `wake <plan>` | disable / re-enable packages for user 0 |
| `uninstall <plan>` | remove Play Store apps (refuses system packages) |
| `remove <plan>` / `readd <plan>` | uninstall a system package for user 0 only / bring it back |
| `install <apk> [--grant=pkg:perm]` | push and install an APK |
| `home check` / `home set <component>` / `home reset` | the Home key |
| `restore <snapshot.json>` | back to that snapshot |
| `reboot` | reboot after the package manager has flushed |
| `shell '<cmd>'` | one shell command |

All mutating commands need `--yes`.

## Tested on

TCL 50C645 (Google TV, Android 11, Realtek RT51M, 2 GB RAM). The package names in `plans/` and
`protected.py` are from that TV; the tool itself works on any Android TV / Google TV with
USB debugging. Not for Samsung (Tizen), LG (webOS) or Fire TV.

## Licenses

Code is MIT ([LICENSE](LICENSE)). Wallpapers and icons have their own licenses, see
[`launcher/wallpapers/CREDITS.md`](launcher/wallpapers/CREDITS.md). Inspired by
[mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home).
