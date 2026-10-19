# Plan files

One package per line, `#` comments allowed. Numbered in the order they were applied to a
TCL 50C645 (Google TV, Android 11, R51MT05). Treat them as examples: your TV will have a different
list. How this one was built:

1. `tvclean.py shell 'pm list packages -s'` for system packages, `pm list packages -3 -i` for
   Play Store apps, `tvclean.py mem` for what is resident in RAM, and
   `cmd package query-receivers --brief -a android.intent.action.BOOT_COMPLETED` for what starts at boot.
2. Sort into four boxes: **sleep** (ads, promos, telemetry, companions you don't use),
   **test one at a time** (anything with side-effect reports on other models),
   **keep** (inputs, remote, casting, Assistant, Play, keyboards, updates — put these in
   `protected.py`), **don't touch** (Android plumbing: `android`, `systemui`, providers, overlays).
3. Apply the resident ones first (`02`), then promo/companions (`03`), small leftovers (`04`),
   Google extras (`05`), and the risky ones last and alone (`06a`, `06b`, `07`).
4. Test with the remote after every batch; `wake` the batch if anything broke.

| File | What |
|---|---|
| `01-uninstall-playstore.txt` | Play Store apps the owner no longer used (`uninstall`) |
| `02-sleep-tcl-resident.txt` | TCL services resident in RAM: account, app showcase, home tab, analytics, popups |
| `03-sleep-tcl-promo.txt` | promo stickers, partner rows, TCL Channel, dashboard, browser, companions |
| `04-sleep-tcl-small.txt` | manual, key help, log kit, first-run setup, EULA prompts |
| `05-sleep-google-extras.txt` | silent app installer, Play Games, YouTube Music, partner tracking, calendar, SMS, camera, print |
| `06a-sleep-waterfall.txt` | TCL content feed (bootloop reports on other models: alone) |
| `06b-sleep-guard.txt` | Safety Guard via `sleep` — did not stick, it is PERSISTENT; see `07` |
| `07-remove-guard.txt` | Safety Guard via `remove` (uninstall for user 0, APK stays) |
| `08-sleep-stock-home.txt` | Google TV home + setup wizard, needed for a third-party Home key |

Known-bad on some TCL models (keep in `protected.py` unless you have tested): `com.android.location.fused`
(bootloop reports), `com.tcl.waterfall.overseas` (bootloop reports when *uninstalled*; sleeping it was fine here).
