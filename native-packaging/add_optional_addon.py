#!/usr/bin/env python3
"""Declara um addon como 'opcional de sistema' no addon-manifest.xml do Kodi: ele entra habilitado na primeira varredura.
Uso: add_optional_addon.py <addon-manifest.xml> <addon-id>"""
import re, sys
path, addon_id = sys.argv[1:3]
if not re.fullmatch(r"[A-Za-z0-9._-]{3,100}", addon_id): sys.exit("id inválido")
with open(path, encoding="utf-8") as f: s = f.read()
if "</addons>" not in s: sys.exit("addon-manifest.xml inesperado: sem </addons>")
entry = '  <addon optional="true">%s</addon>\n' % addon_id
if entry not in s:
    s = s.replace("</addons>", entry + "</addons>", 1)
    with open(path, "w", encoding="utf-8") as f: f.write(s)
print("add_optional_addon: ok", addon_id)
