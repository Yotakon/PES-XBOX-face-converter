"""Uso: python -m pesport ENTRADA_PS2.bin SALIDA_XBOX.bin [--color RRGGBB] [--brillo 1.0]"""
import argparse
import sys

from .convert import ConversionError, convert_bin


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pesport",
                                 description="Convierte caras/pelos de PES 2010 PS2 a PES 5 Xbox")
    ap.add_argument("entrada")
    ap.add_argument("salida")
    ap.add_argument("--color", help="color del pelo en hex, p. ej. 2a1e18 (obligatorio para pelos)")
    ap.add_argument("--brillo", type=float, default=1.0)
    a = ap.parse_args(argv)

    rgb = None
    if a.color:
        c = a.color.lstrip("#")
        if len(c) != 6:
            ap.error("--color debe tener 6 digitos hex")
        rgb = tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))
    try:
        with open(a.entrada, "rb") as f:
            kind, out = convert_bin(f.read(), rgb, a.brillo)
    except (ConversionError, ValueError) as e:
        print("Error:", e, file=sys.stderr)
        return 1
    with open(a.salida, "wb") as f:
        f.write(out)
    print("%s convertido: %s (%d bytes)" % ("Cara" if kind == "face" else "Pelo", a.salida, len(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
