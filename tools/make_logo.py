#!/usr/bin/env python3
"""Dibuja desde cero el logo de la app (icono.png), minimalista. No usa ninguna imagen.

Uso (desde la raiz del repositorio):  python3 tools/make_logo.py
Necesita: pip install pillow numpy scipy

Despues, para crear los iconos de Android:  python3 tools/make_icons.py icono.png

Logo: circulo verde estilo Xbox con borde blanco, las formas de la X de Xbox en blanco
(transparentes) al fondo y un 5 amarillo italico estilo PES 5 delante.
Salida: 1254x1254, circulo sobre blanco (es el formato que espera make_icons.py).
"""
import os

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = 1254
C = W / 2

GREEN_CENTER = (46, 178, 40)     # verde Xbox, mas claro en el centro
GREEN_EDGE = (14, 112, 20)
YELLOW = (255, 214, 20)
OUTLINE = (5, 58, 10)            # verde muy oscuro, casi negro


def smooth_edges(mask, radius, hardness=10):
    """Redondea las esquinas de una mascara 'L' y deja el borde suave (antialias)."""
    a = np.asarray(mask.filter(ImageFilter.GaussianBlur(radius)), float)
    return Image.fromarray(np.clip((a - 127.5) * hardness + 127.5, 0, 255).astype("uint8"), "L")


def dilate(mask, px, blur=1.2):
    a = np.asarray(mask) > 127
    r = int(px)
    yy, xx = np.ogrid[-r:r + 1, -r:r + 1]
    d = ndi.binary_dilation(a, structure=(xx * xx + yy * yy <= r * r))
    return Image.fromarray((d * 255).astype("uint8"), "L").filter(ImageFilter.GaussianBlur(blur))


def disc(size, cx, cy, r, ss=4):
    m = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(m).ellipse(((cx - r) * ss, (cy - r) * ss, (cx + r) * ss, (cy + r) * ss), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def radial_background():
    y, x = np.mgrid[0:W, 0:W]
    d = np.clip(np.sqrt((x - C) ** 2 + (y - C + 80) ** 2) / (W * 0.62), 0, 1)
    a, b = np.array(GREEN_CENTER, float), np.array(GREEN_EDGE, float)
    return Image.fromarray((a + (b - a) * d[..., None]).astype("uint8"), "RGB")


def bezier(p0, p1, p2, n=1200):
    t = np.linspace(0, 1, n)[:, None]
    return (1 - t) ** 2 * np.array(p0) + 2 * (1 - t) * t * np.array(p1) + t ** 2 * np.array(p2)


def x_shapes(radius, ss=2):
    """Las dos bandas curvas cruzadas de la X de Xbox, como mascara 'L' del tamaño del icono."""
    S = W * ss
    m = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(m)
    bow = 0.20
    strokes = [((-1.05, -1.1), (bow, -bow), (1.05, 1.1)),
               ((1.05, -1.1), (-bow, -bow), (-1.05, 1.1))]
    for p0, p1, p2 in strokes:
        for i, (px, py) in enumerate(bezier(p0, p1, p2)):
            t = i / 1199
            hw = (0.17 + 0.10 * abs(2 * t - 1) ** 1.3) * radius * ss
            cx, cy = C * ss + px * radius * ss, C * ss + py * radius * ss
            d.ellipse((cx - hw, cy - hw, cx + hw, cy + hw), fill=255)
    return m.resize((W, W), Image.LANCZOS)


def five_layer(height):
    """Un 5 amarillo italico de 'height' px de alto con borde oscuro, estilo PES 5."""
    ss = 2
    cw, ch = 760 * ss, 900 * ss
    m = Image.new("L", (cw, ch), 0)
    d = ImageDraw.Draw(m)

    def r(x0, y0, x1, y1):
        d.rectangle((x0 * ss, y0 * ss, x1 * ss, y1 * ss), fill=255)

    r(80, 40, 640, 180)            # barra de arriba
    r(80, 40, 230, 440)            # palo de la izquierda
    r(80, 300, 430, 440)           # barra del medio
    bowl = Image.new("L", (cw, ch), 0)   # panza: anillo grueso abierto por la izquierda
    bd = ImageDraw.Draw(bowl)
    bd.ellipse((130 * ss, 300 * ss, 690 * ss, 880 * ss), fill=255)
    bd.ellipse((270 * ss, 440 * ss, 550 * ss, 740 * ss), fill=0)
    bd.rectangle((0, 445 * ss, 410 * ss, 690 * ss), fill=0)
    m = ImageChops.lighter(m, bowl)
    m = smooth_edges(m, 6 * ss, 8)
    k = 0.30                              # italica
    m = m.transform((cw + int(k * ch), ch), Image.AFFINE, (1, k, -k * ch, 0, 1, 0), Image.BICUBIC)
    m = m.crop(m.getbbox())
    s = height * ss / m.height
    m = m.resize((round(m.width * s / ss), round(m.height * s / ss)), Image.LANCZOS)

    outline_px = 24
    pad = outline_px + 20
    canvas = Image.new("L", (m.width + 2 * pad, m.height + 2 * pad), 0)
    canvas.paste(m, (pad, pad))
    out = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    edge = Image.new("RGBA", canvas.size, OUTLINE + (255,))
    edge.putalpha(dilate(canvas, outline_px))
    out.alpha_composite(edge)
    fill = Image.new("RGBA", canvas.size, YELLOW + (255,))
    fill.putalpha(canvas)
    out.alpha_composite(fill)
    return out


def main():
    inner_r = C - 2
    # circulo verde con la X de Xbox en blanco (transparente)
    art = radial_background().convert("RGBA")
    white = Image.new("RGBA", (W, W), (255, 255, 255, 255))
    inside_ring = disc(W, C, C, C * 0.93 - 40)      # las formas no pasan del borde blanco
    xa = ImageChops.multiply(x_shapes(C).point(lambda v: int(v * 0.30)), inside_ring)
    white.putalpha(xa)
    art.alpha_composite(white)

    # borde blanco del circulo, un poco hacia dentro (el verde queda por fuera)
    ring = ImageChops.subtract(disc(W, C, C, C * 0.93), disc(W, C, C, C * 0.93 - 40))
    ringl = Image.new("RGBA", (W, W), (255, 255, 255, 255))
    ringl.putalpha(ring)
    art.alpha_composite(ringl)

    # el 5 amarillo, con una sombra suave
    five = five_layer(640)
    x, y = int(C - five.width / 2 + 10), int(C - five.height / 2 - 6)
    sh = Image.new("L", (W, W), 0)
    sh.paste(five.getchannel("A"), (x + 4, y + 18))
    sh = sh.filter(ImageFilter.GaussianBlur(16)).point(lambda v: int(v * 0.55))
    shl = Image.new("RGBA", (W, W), (0, 20, 0, 255))
    shl.putalpha(sh)
    art.alpha_composite(shl)
    tmp = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    tmp.paste(five, (x, y), five)
    art.alpha_composite(tmp)

    final = Image.new("RGB", (W, W), (255, 255, 255))
    final.paste(art.convert("RGB"), (0, 0), disc(W, C, C, inner_r))
    out = os.path.join(ROOT, "icono.png")
    final.save(out)
    print("Logo guardado en", out)


if __name__ == "__main__":
    main()
