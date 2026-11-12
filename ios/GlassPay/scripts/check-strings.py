#!/usr/bin/env python3
"""Verifica que cada texto L("...") de la app iOS tenga traducción al inglés con los mismos formatos.

Uso (desde ios/GlassPay): python3 scripts/check-strings.py
Sale con código 1 si falta una traducción o si no coinciden los %@ / %d.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "GlassPay"
LITERAL = r'"((?:[^"\\]|\\.)*)"'

keys = []
for path in sorted(ROOT.rglob("*.swift")):
    if path.name == "EnglishStrings.swift":
        continue
    for match in re.finditer(r"\bL\(" + LITERAL, path.read_text()):
        if match.group(1) not in keys:
            keys.append(match.group(1))

table = {}
for line in (ROOT / "App" / "EnglishStrings.swift").read_text().splitlines():
    match = re.match(r"\s*" + LITERAL + r":\s*" + LITERAL + r",\s*$", line)
    if match:
        table[match.group(1)] = match.group(2)

problems = [f"Sin traducción: {key!r}" for key in keys if key not in table]
problems += [f"Clave sin uso: {key!r}" for key in table if key not in keys]
problems += [
    f"Formatos distintos: {key!r} -> {value!r}"
    for key, value in table.items()
    if re.findall(r"%[@d]", key) != re.findall(r"%[@d]", value)
]
print("\n".join(problems) if problems else f"OK: {len(keys)} textos traducidos.")
sys.exit(1 if problems else 0)
