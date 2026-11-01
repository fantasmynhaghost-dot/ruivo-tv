#!/usr/bin/env python3
"""Gera o ZIP determinístico do addon de exemplo em app/src/main/assets/kodi-addons/."""
import os, sys, zipfile
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
aid = "plugin.video.ruivo.demo"
src = os.path.join(root, "kodi-addons", aid)
out = os.path.join(root, "app/src/main/assets/kodi-addons", aid + ".zip")
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(src)):
        zi = zipfile.ZipInfo(aid + "/" + name, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED; zi.external_attr = 0o644 << 16
        with open(os.path.join(src, name), "rb") as f: z.writestr(zi, f.read())
print(out)
