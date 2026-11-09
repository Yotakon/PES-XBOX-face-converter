#!/usr/bin/env python3
"""Genera tests/referencia/*.bin con el conversor Python (la version validada en el juego).

Las pruebas de la app Android comparan su salida con estos archivos byte a byte.
Uso (desde la raiz del repositorio): python3 tools/gen_referencias.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

from pesport import convert  # noqa: E402

FACE = os.path.join(ROOT, "Ronaldinho FACE PES 2010 PS2.bin")
HAIR = os.path.join(ROOT, "Ronaldinho HAIR PES2010 PS2.bin")
OUT = os.path.join(ROOT, "tests", "referencia")

HAIR_CASES = [("281e18", 1.0), ("e6be5a", 1.0), ("808080", 0.75), ("103050", 1.3)]


def main():
    os.makedirs(OUT, exist_ok=True)
    with open(FACE, "rb") as f:
        _, data = convert.convert_bin(f.read())
    with open(os.path.join(OUT, "face.bin"), "wb") as f:
        f.write(data)
    with open(HAIR, "rb") as f:
        hair = f.read()
    for color, bright in HAIR_CASES:
        rgb = tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))
        _, data = convert.convert_bin(hair, rgb, bright)
        with open(os.path.join(OUT, "hair_%s_%s.bin" % (color, bright)), "wb") as f:
            f.write(data)
    print("Referencias generadas en", OUT)


if __name__ == "__main__":
    main()
