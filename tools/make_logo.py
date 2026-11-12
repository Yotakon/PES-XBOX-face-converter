#!/usr/bin/env python3
"""Genera icono.png (el logo de la app) a partir de los recortes de tools/logo/.

Uso (desde la raiz del repositorio):  python3 tools/make_logo.py
Necesita: pip install pillow numpy scipy

Despues, para crear los iconos de Android:  python3 tools/make_icons.py icono.png

Logo: un 5 amarillo con el borde negro y blanco del logo de PES 5 y la esfera verde de Xbox,
sobre fondo negro con una curva verde. Salida: 1254x1254, esquinas redondeadas sobre blanco
(es el formato que espera make_icons.py).
"""
import os

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "tools", "logo")
W = 1254
CORNER = 230
GREEN = (62, 190, 28)


def rounded_mask(size, radius):
    big = 4
    m = Image.new("L", (size * big, size * big), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size * big - 1, size * big - 1), radius * big, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def vgradient(size, top, bottom):
    t = np.linspace(0, 1, size)[:, None, None]
    top, bottom = np.array(top, float), np.array(bottom, float)
    row = top + (bottom - top) * t
    return Image.fromarray(np.repeat(row, size, axis=1).astype("uint8"), "RGB")


def glow(size, center, radius, color, strength):
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - center[0]) ** 2 + (y - center[1]) ** 2) / radius
    a = np.clip(1 - d, 0, 1) ** 2 * strength
    layer = Image.new("RGBA", (size, size), color + (0,))
    layer.putalpha(Image.fromarray((a * 255).astype("uint8"), "L"))
    return layer


def dilate(alpha, px):
    """Ensancha una mascara 'L' px pixeles (disco)."""
    a = np.asarray(alpha) > 127
    r = int(px)
    yy, xx = np.ogrid[-r:r + 1, -r:r + 1]
    disk = xx * xx + yy * yy <= r * r
    d = ndi.binary_dilation(a, structure=disk)
    m = Image.fromarray((d * 255).astype("uint8"), "L")
    return m.filter(ImageFilter.GaussianBlur(1.2))


def swoosh(size):
    """Curva verde bajo el logo: se afina hacia los lados."""
    big = 2
    layer = Image.new("L", (size * big, size * big), 0)
    d = ImageDraw.Draw(layer)
    d.ellipse((-330 * big, 520 * big, 1584 * big, 1215 * big), fill=255)
    d.ellipse((-290 * big, 488 * big, 1544 * big, 1160 * big), fill=0)
    layer = layer.resize((size, size), Image.LANCZOS)
    ImageDraw.Draw(layer).rectangle((0, 0, size, 860), fill=0)
    col = vgradient(size, (150, 240, 55), (28, 125, 14)).convert("RGBA")
    col.putalpha(layer)
    return col


def sticker(img, ink, silver_px=10, black_px=26):
    """Pone borde negro y borde blanco/plata alrededor de una imagen RGBA, como el logo de PES 5."""
    pad = black_px + silver_px + 20
    canvas = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    canvas.paste(img, (pad, pad), img)
    alpha = canvas.getchannel("A")
    black = dilate(alpha, black_px)
    silver = dilate(alpha, black_px + silver_px)
    out = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    plate = vgradient(canvas.height, (255, 255, 255), (150, 160, 170)).resize(canvas.size).convert("RGBA")
    plate.putalpha(silver)
    out = Image.alpha_composite(out, plate)
    blk = Image.new("RGBA", canvas.size, (4, 4, 4, 255))
    blk.putalpha(black)
    out = Image.alpha_composite(out, blk)
    return Image.alpha_composite(out, canvas)


def paste_with_shadow(base, layer, center, dy=16, blur=14, opacity=0.8):
    x, y = int(center[0] - layer.width / 2), int(center[1] - layer.height / 2)
    m = Image.new("L", base.size, 0)
    m.paste(layer.getchannel("A"), (x + 4, y + dy))
    m = m.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * opacity))
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sh.putalpha(m)
    base.alpha_composite(sh)
    base.alpha_composite(layer, (x, y)) if x >= 0 and y >= 0 else base.paste(layer, (x, y), layer)


def main():
    five = Image.open(os.path.join(ASSETS, "cinco_amarillo.png")).convert("RGBA")
    ball = Image.open(os.path.join(ASSETS, "esfera_xbox.png")).convert("RGBA")

    base = vgradient(W, (3, 6, 3), (8, 22, 8)).convert("RGBA")
    base.alpha_composite(glow(W, (420, 760), 700, (60, 200, 30), 0.35))
    base.alpha_composite(swoosh(W))

    # 5 amarillo: grande, ocupa casi todo el icono
    s5 = 2.85
    f = five.resize((round(five.width * s5), round(five.height * s5)), Image.LANCZOS)
    f = sticker(f, None, silver_px=11, black_px=28)
    # esfera Xbox abajo a la izquierda, con borde oscuro y aro plateado
    d = 440
    b = ball.resize((d, d), Image.LANCZOS)
    ring = 30
    bw = Image.new("RGBA", (d + 2 * ring, d + 2 * ring), (0, 0, 0, 0))
    rg = Image.new("L", bw.size, 0)
    ImageDraw.Draw(rg).ellipse((0, 0, bw.width - 1, bw.height - 1), fill=255)
    metal = vgradient(bw.height, (235, 240, 245), (70, 78, 86)).convert("RGBA")
    metal.putalpha(rg)
    bw.alpha_composite(metal)
    inner = Image.new("L", bw.size, 0)
    ImageDraw.Draw(inner).ellipse((10, 10, bw.width - 11, bw.height - 11), fill=255)
    dark = Image.new("RGBA", bw.size, (6, 8, 6, 255))
    dark.putalpha(inner)
    bw.alpha_composite(dark)
    bw.alpha_composite(b, (ring, ring))

    paste_with_shadow(base, f, (690, 500))
    paste_with_shadow(base, bw, (305, 885))

    final = Image.new("RGB", (W, W), (255, 255, 255))
    final.paste(base.convert("RGB"), (0, 0), rounded_mask(W, CORNER))
    out = os.path.join(ROOT, "icono.png")
    final.save(out)
    print("Logo guardado en", out)


if __name__ == "__main__":
    main()
