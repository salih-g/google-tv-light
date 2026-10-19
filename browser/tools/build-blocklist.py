#!/usr/bin/env python3
"""Build browser/assets/blocklist.txt.gz: one blocked domain per line, from
- StevenBlack unified hosts (MIT)        https://github.com/StevenBlack/hosts
- HaGeZi Pro, domains-only format (GPL-3) https://github.com/hagezi/dns-blocklists
Run: python3 -I browser/tools/build-blocklist.py
"""
import gzip
import os
import re
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets", "blocklist.txt.gz")
SOURCES = [
    ("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "hosts"),
    ("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt", "domains"),
]
# Never block these: the TV's own services and sites the browser must reach.
ALLOW = {"localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost"}
UA = {"User-Agent": "google-tv-light-blocklist/0.1"}

domains = set()
for url, kind in SOURCES:
    text = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120).read().decode("utf-8", "replace")
    n = 0
    for line in text.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        if kind == "hosts":
            parts = line.split()
            if len(parts) < 2 or parts[0] not in ("0.0.0.0", "127.0.0.1"):
                continue
            host = parts[1].lower()
        else:
            host = line.lower().lstrip("*.")
        if host in ALLOW or not re.fullmatch(r"[a-z0-9.-]+\.[a-z]{2,}", host):
            continue
        domains.add(host); n += 1
    print(f"{url.split('/')[-1]}: {n} entries")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with gzip.open(OUT, "wt", compresslevel=9) as f:
    for d in sorted(domains):
        f.write(d + "\n")
print(f"{len(domains)} domains -> {os.path.relpath(OUT)} ({os.path.getsize(OUT) // 1024} KB)")
