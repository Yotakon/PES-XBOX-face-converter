#!/usr/bin/env python3
"""Simula como se ve el icono adaptable con distintas formas de launcher.

Uso: python3 tools/preview_icons.py salida.png
Lee los iconos ya generados (xxxhdpi) y crea una tira: circulo, squircle, cuadrado
redondeado, gota, y los iconos clasico y redondo.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_icons as m  # noqa: E402

RES = m.RES
D = 4.0              # xxxhdpi
LAYER = int(108 * D)
VIEW = int(72 * D)   # zona visible del icono adaptable
GAP = 24


def shape_fn(name):
    r = VIEW / 2.0
    if name == "circulo":
        return lambda x, y: x * x + y * y <= r * r
    if name == "squircle":
        return lambda x, y: abs(x / r) ** 4 + abs(y / r) ** 4 <= 1
    if name == "cuadrado":
        k = r * 0.35
        return lambda x, y: (max(abs(x) - (r - k), 0) ** 2 + max(abs(y) - (r - k), 0) ** 2) <= k * k
    if name == "gota":   # esquina inferior derecha cuadrada
        return lambda x, y: (x > 0 and y > 0 and abs(x) <= r and abs(y) <= r) or x * x + y * y <= r * r
    raise ValueError(name)


def main():
    out_path = sys.argv[1]
    bgxml = open(os.path.join(RES, "values", "ic_launcher_colors.xml")).read()
    bg = re.search(r"#([0-9A-Fa-f]{6})", bgxml).group(1)
    bg = tuple(int(bg[i:i + 2], 16) for i in (0, 2, 4))
    fw, fh, fg = m.read_png(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png"))
    shapes = ["circulo", "squircle", "cuadrado", "gota"]
    legacy = [m.read_png(os.path.join(RES, "mipmap-xxxhdpi", n)) for n in ("ic_launcher.png", "ic_launcher_round.png")]
    W = GAP + len(shapes) * (VIEW + GAP) + sum(l[0] + GAP for l in legacy)
    H = VIEW + 2 * GAP
    img = [(235, 235, 240, 255)] * (W * H)
    x0 = GAP
    off = (LAYER - VIEW) // 2
    for s in shapes:
        inside = shape_fn(s)
        for y in range(VIEW):
            for x in range(VIEW):
                cx, cy = x - VIEW / 2 + 0.5, y - VIEW / 2 + 0.5
                if not inside(cx, cy):
                    continue
                r, g, b, a = fg[(y + off) * LAYER + x + off]
                k = a / 255.0
                img[(y + GAP) * W + x0 + x] = (round(r * k + bg[0] * (1 - k)), round(g * k + bg[1] * (1 - k)),
                                               round(b * k + bg[2] * (1 - k)), 255)
        x0 += VIEW + GAP
    for lw, lh, lp in legacy:
        y0 = GAP + (VIEW - lh) // 2
        for y in range(lh):
            for x in range(lw):
                r, g, b, a = lp[y * lw + x]
                k = a / 255.0
                q = img[(y + y0) * W + x0 + x]
                img[(y + y0) * W + x0 + x] = (round(r * k + q[0] * (1 - k)), round(g * k + q[1] * (1 - k)),
                                              round(b * k + q[2] * (1 - k)), 255)
        x0 += lw + GAP
    m.write_png(out_path, W, H, img)
    print("Vista previa:", out_path)


if __name__ == "__main__":
    main()
