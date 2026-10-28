#!/usr/bin/env python3
"""Append classes*.dex from a directory to an APK with a fixed timestamp (reproducible)."""
import sys, zipfile, os
apk, dexdir = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
    for n in sorted(os.listdir(dexdir)):
        if n.startswith("classes") and n.endswith(".dex"):
            zi = zipfile.ZipInfo(n, date_time=(2026, 10, 27, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, open(os.path.join(dexdir, n), "rb").read())
