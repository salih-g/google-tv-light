# tv

Make a TCL 50C645 (Google TV, Android 11, 2 GB RAM) light again without losing features:
reversible debloat over Wi-Fi ADB, then a custom Apple TV-style home screen.

## Setup

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
echo 192.168.2.157 > .tv_host          # the TV's IP; USB debugging must be on
.venv/bin/python -I tvclean.py probe
```

## Workflow

Every mutating command is a dry run until `--yes` is added, writes a snapshot first,
and refuses anything in `protected.py`.

```bash
.venv/bin/python -I tvclean.py mem                                   # before
.venv/bin/python -I tvclean.py uninstall plans/01-uninstall-playstore.txt --yes
.venv/bin/python -I tvclean.py sleep plans/02-sleep-tcl-resident.txt --yes
.venv/bin/python -I tvclean.py mem                                   # after
.venv/bin/python -I tvclean.py restore snapshots/<file>.json --yes   # undo to that point
```

Plans are applied in number order, one batch at a time, with a remote test in between
(HDMI switch, mic, Chromecast, Netflix, keyboard, power cycle).
