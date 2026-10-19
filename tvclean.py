#!/usr/bin/env python3
"""tvclean: reversible debloat + home-screen switch for an Android/Google TV over Wi-Fi ADB.

Safety model
- Never uninstalls a system package. System packages are only disabled for user 0 (`pm disable-user`),
  which stops them running and is undone with `pm enable`.
- `uninstall` only works on Play Store apps (installer = com.android.vending) and never on KEEP_APPS.
- Packages in protected.py are refused by every mutating command, even if a plan file lists them.
- Every mutating command first writes a snapshot of all package states to snapshots/, and
  `restore <snapshot>` brings the TV back to exactly that state.
- Mutating commands are dry-run unless --yes is given.

Usage
  tvclean.py probe                       model, Android version, memory, home activity
  tvclean.py mem                         free RAM, swap, resident app list (before/after comparisons)
  tvclean.py snapshot                    save package states to snapshots/<timestamp>.json
  tvclean.py sleep  <plan.txt> [--yes]   disable packages listed in the plan (one per line, # comments)
  tvclean.py wake   <plan.txt> [--yes]   re-enable them
  tvclean.py uninstall <plan.txt> [--yes]  remove Play Store apps listed in the plan
  tvclean.py restore <snapshot.json> [--yes]  re-enable everything that was enabled in the snapshot
  tvclean.py home check | home set <pkg/.Activity> [--yes] | home reset [--yes]
  tvclean.py shell '<cmd>'               one read-only shell command

TV address: TV_HOST env or .tv_host file next to this script.
"""
import datetime as dt
import json
import os
import re
import sys

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # `python -I` drops the script dir from sys.path
from protected import KEEP_APPS, is_protected  # noqa: E402
SNAPSHOTS = os.path.join(HERE, "snapshots")
KEY = os.path.join(HERE, ".adbkey", "adbkey")
STOCK_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity"


# ---------- connection ----------

def host() -> str:
    h = os.environ.get("TV_HOST")
    f = os.path.join(HERE, ".tv_host")
    if not h and os.path.exists(f):
        h = open(f).read().strip()
    if not h:
        sys.exit("TV address unknown: set TV_HOST=192.168.x.y or write it to .tv_host")
    return h


def connect() -> AdbDeviceTcp:
    os.makedirs(os.path.dirname(KEY), exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    signer = PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())
    dev = AdbDeviceTcp(host(), int(os.environ.get("TV_PORT", "5555")), default_transport_timeout_s=120.0)
    dev.connect(rsa_keys=[signer], auth_timeout_s=120)
    return dev


def sh(dev, cmd: str) -> str:
    return dev.shell(cmd, read_timeout_s=120).rstrip()


# ---------- package state ----------

def package_states(dev) -> dict:
    """{pkg: {"system": bool, "enabled": bool, "installer": str|None}} for user 0."""
    all_pkgs = set(re.findall(r"package:(\S+)", sh(dev, "pm list packages")))
    system = set(re.findall(r"package:(\S+)", sh(dev, "pm list packages -s")))
    disabled = set(re.findall(r"package:(\S+)", sh(dev, "pm list packages -d")))
    third = dict(re.findall(r"package:(\S+)\s+installer=(\S+)", sh(dev, "pm list packages -3 -i")))
    return {
        p: {"system": p in system, "enabled": p not in disabled, "installer": third.get(p)}
        for p in sorted(all_pkgs)
    }


def write_snapshot(dev, label: str) -> str:
    os.makedirs(SNAPSHOTS, exist_ok=True)
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = os.path.join(SNAPSHOTS, f"{stamp}-{label}.json")
    data = {
        "taken": stamp,
        "label": label,
        "home": sh(dev, "cmd package resolve-activity -a android.intent.action.MAIN "
                       "-c android.intent.category.HOME --brief").splitlines()[-1],
        "mem": mem_summary(dev),
        "packages": package_states(dev),
    }
    with open(path, "w") as f:
        json.dump(data, f, indent=1)
    print(f"snapshot: {os.path.relpath(path, HERE)}")
    return path


def read_plan(path: str) -> list:
    pkgs = []
    for line in open(path):
        line = line.split("#", 1)[0].strip()
        if line:
            pkgs.append(line.split()[0])
    return pkgs


# ---------- memory ----------

def mem_summary(dev) -> dict:
    info = dict(re.findall(r"^(\w+):\s+(\d+) kB", sh(dev, "cat /proc/meminfo"), re.M))
    kb = lambda k: int(info.get(k, 0))
    return {
        "total_mb": kb("MemTotal") // 1024,
        "available_mb": kb("MemAvailable") // 1024,
        "swap_used_mb": (kb("SwapTotal") - kb("SwapFree")) // 1024,
    }


def cmd_mem(dev):
    m = mem_summary(dev)
    print(f"RAM total {m['total_mb']} MB, available {m['available_mb']} MB, swap used {m['swap_used_mb']} MB")
    print("resident app processes (RSS MB):")
    rows = []
    for line in sh(dev, "ps -A -o RSS,NAME").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0].isdigit() and parts[1].startswith(("com.", "org.", "tv.", "in.")):
            rows.append((int(parts[0]) // 1024, parts[1]))
    for rss, name in sorted(rows, reverse=True)[:30]:
        print(f"  {rss:5d}  {name}")


# ---------- mutating commands ----------

def confirm_or_dry(args: list, label: str, actions: list):
    """Print the exact shell commands; run them only with --yes."""
    if not actions:
        print("nothing to do")
        return False
    print(f"{label}: {len(actions)} command(s)")
    for a in actions:
        print(f"  {a}")
    if "--yes" not in args:
        print("dry run: add --yes to apply")
        return False
    return True


def apply(dev, actions: list):
    for a in actions:  # one pm call per shell command: chained pm calls can stop early
        out = sh(dev, a)
        print(f"$ {a}\n  {out or 'ok'}")


def cmd_sleep(dev, args):
    plan, wanted = args[0], read_plan(args[0])
    states = package_states(dev)
    actions, skipped = [], []
    for p in wanted:
        if is_protected(p):
            skipped.append((p, "PROTECTED, refused"))
        elif p not in states:
            skipped.append((p, "not installed"))
        elif not states[p]["enabled"]:
            skipped.append((p, "already disabled"))
        else:
            actions.append(f"pm disable-user --user 0 {p}")
    for p, why in skipped:
        print(f"  skip {p}: {why}")
    if confirm_or_dry(args, f"sleep {os.path.basename(plan)}", actions):
        write_snapshot(dev, "before-sleep")
        apply(dev, actions)
        cmd_mem(dev)


def cmd_wake(dev, args):
    plan, wanted = args[0], read_plan(args[0])
    states = package_states(dev)
    actions = [f"pm enable {p}" for p in wanted if p in states and not states[p]["enabled"]]
    if confirm_or_dry(args, f"wake {os.path.basename(plan)}", actions):
        write_snapshot(dev, "before-wake")
        apply(dev, actions)


def cmd_uninstall(dev, args):
    plan, wanted = args[0], read_plan(args[0])
    states = package_states(dev)
    actions, skipped = [], []
    for p in wanted:
        s = states.get(p)
        if p in KEEP_APPS or is_protected(p):
            skipped.append((p, "PROTECTED, refused"))
        elif s is None:
            skipped.append((p, "not installed"))
        elif s["system"] or s["installer"] != "com.android.vending":
            skipped.append((p, "not a Play Store app, refused"))
        else:
            actions.append(f"pm uninstall --user 0 {p}")
    for p, why in skipped:
        print(f"  skip {p}: {why}")
    if confirm_or_dry(args, f"uninstall {os.path.basename(plan)}", actions):
        write_snapshot(dev, "before-uninstall")
        apply(dev, actions)


def cmd_restore(dev, args):
    snap = json.load(open(args[0]))
    states = package_states(dev)
    actions = []
    for p, s in snap["packages"].items():
        if s["enabled"] and p in states and not states[p]["enabled"]:
            actions.append(f"pm enable {p}")
    home = snap["home"].split()[-1] if snap.get("home") else ""
    if home and "/" in home:
        actions.append(f"cmd package set-home-activity --user 0 {home}")
    if confirm_or_dry(args, f"restore {os.path.basename(args[0])}", actions):
        write_snapshot(dev, "before-restore")
        apply(dev, actions)
        print("note: uninstalled Play Store apps are not restored; reinstall them from Play Store")


def cmd_home(dev, args):
    sub = args[0] if args else "check"
    if sub == "check":
        print(sh(dev, "cmd package resolve-activity -a android.intent.action.MAIN "
                      "-c android.intent.category.HOME --brief"))
        print(sh(dev, "cmd shortcut get-default-launcher"))
        return
    target = STOCK_HOME if sub == "reset" else args[1]
    actions = [f"cmd package set-home-activity --user 0 {target}"]
    if confirm_or_dry(args, f"home {sub}", actions):
        write_snapshot(dev, f"before-home-{sub}")
        apply(dev, actions)
        cmd_home(dev, ["check"])


# ---------- read-only commands ----------

def cmd_probe(dev):
    for p in ("ro.product.manufacturer", "ro.product.model", "ro.product.device",
              "ro.build.version.release", "ro.build.display.id", "ro.product.cpu.abilist"):
        print(f"{p} = {sh(dev, f'getprop {p}')}")
    print(sh(dev, "cat /proc/loadavg"))
    cmd_home(dev, ["check"])
    m = mem_summary(dev)
    print(f"RAM available {m['available_mb']} / {m['total_mb']} MB, swap used {m['swap_used_mb']} MB")
    st = package_states(dev)
    print(f"packages: {len(st)} total, {sum(s['system'] for s in st.values())} system, "
          f"{sum(not s['enabled'] for s in st.values())} disabled")


COMMANDS = {
    "probe": lambda d, a: cmd_probe(d),
    "mem": lambda d, a: cmd_mem(d),
    "snapshot": lambda d, a: write_snapshot(d, a[0] if a else "manual"),
    "sleep": cmd_sleep,
    "wake": cmd_wake,
    "uninstall": cmd_uninstall,
    "restore": cmd_restore,
    "home": cmd_home,
    "shell": lambda d, a: print(sh(d, " ".join(a))),
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        sys.exit(__doc__)
    dev = connect()
    try:
        COMMANDS[sys.argv[1]](dev, sys.argv[2:])
    finally:
        dev.close()


if __name__ == "__main__":
    main()
