#!/usr/bin/env python3
"""Dibuja desde cero el logo de la app (icono.png). No usa ninguna imagen: todo se genera por codigo.

Uso (desde la raiz del repositorio):  python3 tools/make_logo.py
Necesita: pip install pillow numpy scipy

Despues, para crear los iconos de Android:  python3 tools/make_icons.py icono.png

Logo: un 5 amarillo italico con borde negro y plateado (estilo PES 5) y la esfera verde de Xbox,
sobre fondo negro con una curva verde. Salida: 1254x1254, esquinas redondeadas sobre blanco
(es el formato que espera make_icons.py).
"""
import math
import os

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter
from scipy import ndimage as ndi

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
W = 1254
CORNER = 230


# ------------------------------------------------------------------ utilidades

def rounded_mask(size, radius):
    big = 4
    m = Image.new("L", (size * big, size * big), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size * big - 1, size * big - 1), radius * big, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def vgradient(w, h, top, bottom):
    t = np.linspace(0, 1, h)[:, None, None]
    row = np.array(top, float) + (np.array(bottom, float) - np.array(top, float)) * t
    return Image.fromarray(np.repeat(row, w, axis=1).astype("uint8"), "RGB")


def glow(size, center, radius, color, strength):
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - center[0]) ** 2 + (y - center[1]) ** 2) / radius
    a = np.clip(1 - d, 0, 1) ** 2 * strength
    layer = Image.new("RGBA", (size, size), color + (0,))
    layer.putalpha(Image.fromarray((a * 255).astype("uint8"), "L"))
    return layer


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


def paste_with_shadow(base, layer, center, dy=16, blur=14, opacity=0.8):
    x, y = int(center[0] - layer.width / 2), int(center[1] - layer.height / 2)
    m = Image.new("L", base.size, 0)
    m.paste(layer.getchannel("A"), (x + 4, y + dy))
    m = m.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * opacity))
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    sh.putalpha(m)
    base.alpha_composite(sh)
    tmp = Image.new("RGBA", base.size, (0, 0, 0, 0))
    tmp.paste(layer, (x, y), layer)
    base.alpha_composite(tmp)


# ------------------------------------------------------------------ el 5

def five_layer(height):
    """Un 5 amarillo italico con borde negro y plateado, de 'height' px de alto (sin borde)."""
    ss = 2
    cw, ch = 760 * ss, 900 * ss
    m = Image.new("L", (cw, ch), 0)
    d = ImageDraw.Draw(m)

    def r(x0, y0, x1, y1):
        d.rectangle((x0 * ss, y0 * ss, x1 * ss, y1 * ss), fill=255)

    r(80, 40, 640, 180)            # barra de arriba
    r(80, 40, 230, 440)            # palo de la izquierda
    r(80, 300, 430, 440)           # barra del medio
    # panza: anillo grueso, abierto por la izquierda entre la barra del medio y la cola
    bowl = Image.new("L", (cw, ch), 0)
    bd = ImageDraw.Draw(bowl)
    bd.ellipse((130 * ss, 300 * ss, 690 * ss, 880 * ss), fill=255)
    bd.ellipse((270 * ss, 440 * ss, 550 * ss, 740 * ss), fill=0)
    bd.rectangle((0, 445 * ss, 410 * ss, 690 * ss), fill=0)
    m = ImageChops.lighter(m, bowl)
    m = smooth_edges(m, 6 * ss, 8)
    # italica: la parte de arriba se corre a la derecha
    k = 0.30
    m = m.transform((cw + int(k * ch), ch), Image.AFFINE, (1, k, -k * ch, 0, 1, 0), Image.BICUBIC)
    bbox = m.getbbox()
    m = m.crop(bbox)
    s = height * ss / m.height
    m = m.resize((round(m.width * s / ss), round(m.height * s / ss)), Image.LANCZOS)

    # relleno amarillo con brillo arriba y borde inferior mas oscuro
    fill = vgradient(m.width, m.height, (255, 244, 70), (255, 188, 0)).convert("RGBA")
    gloss = Image.new("RGBA", fill.size, (255, 255, 255, 0))
    ga = np.zeros((fill.height, fill.width))
    ga[: int(fill.height * 0.38)] = 0.22
    gloss.putalpha(Image.fromarray((ga * 255).astype("uint8"), "L"))
    fill.alpha_composite(gloss)
    fill.putalpha(m)

    # bordes: negro grueso y plateado fino por fuera
    black_px, silver_px = 26, 11
    pad = black_px + silver_px + 20
    canvas_m = Image.new("L", (m.width + 2 * pad, m.height + 2 * pad), 0)
    canvas_m.paste(m, (pad, pad))
    out = Image.new("RGBA", canvas_m.size, (0, 0, 0, 0))
    plate = vgradient(out.width, out.height, (255, 255, 255), (150, 160, 170)).convert("RGBA")
    plate.putalpha(dilate(canvas_m, black_px + silver_px))
    out.alpha_composite(plate)
    black = Image.new("RGBA", out.size, (4, 4, 4, 255))
    black.putalpha(dilate(canvas_m, black_px))
    out.alpha_composite(black)
    out.alpha_composite(fill, (pad, pad))
    return out


# ------------------------------------------------------------------ la esfera de Xbox

def bezier(p0, p1, p2, n=900):
    t = np.linspace(0, 1, n)[:, None]
    return (1 - t) ** 2 * np.array(p0) + 2 * (1 - t) * t * np.array(p1) + t ** 2 * np.array(p2)


def ball_layer(d):
    """Esfera plateada con la X verde de Xbox y aro oscuro, de 'd' px de diametro (con el aro)."""
    ss = 3
    S = d * ss
    c = S / 2
    R = S / 2 - 2
    y, x = np.mgrid[0:S, 0:S]
    dx, dy = (x - c) / R, (y - c) / R
    r2 = dx * dx + dy * dy

    # la X: dos trazos curvos que se cruzan en el centro; arriba se juntan mas que abajo
    xm = Image.new("L", (S, S), 0)
    xd = ImageDraw.Draw(xm)
    bow = 0.20
    strokes = [((-0.78, -0.86), (bow, -bow), (0.78, 0.86)),
               ((0.78, -0.86), (-bow, -bow), (-0.78, 0.86))]
    for p0, p1, p2 in strokes:
        pts = bezier(p0, p1, p2)
        for i, (px, py) in enumerate(pts):
            t = i / (len(pts) - 1)
            hw = (0.165 + 0.095 * abs(2 * t - 1) ** 1.3) * R
            cx, cy = c + px * R, c + py * R
            xd.ellipse((cx - hw, cy - hw, cx + hw, cy + hw), fill=255)
    xmask = np.asarray(xm.filter(ImageFilter.GaussianBlur(ss * 0.7)), float) / 255

    # plata: luz arriba a la izquierda
    dist = np.sqrt((dx + 0.35) ** 2 + (dy + 0.45) ** 2)
    shade = np.clip(1 - dist / 1.6, 0, 1)
    silver = np.array([118, 124, 132]) + shade[..., None] * np.array([137, 131, 123])
    # verde: mas claro hacia el centro
    gl = np.clip(1 - np.sqrt(dx * dx + (dy - 0.05) ** 2) / 0.9, 0, 1)
    green = np.array([22, 128, 8]) + gl[..., None] ** 1.3 * np.array([135, 120, 70])
    img = silver * (1 - xmask[..., None]) + green * xmask[..., None]
    # forma de esfera: bordes mas oscuros y brillo
    img *= (1 - 0.40 * np.clip(r2, 0, 1) ** 3)[..., None]
    spec = np.exp(-(((dx + 0.30) ** 2) / 0.10 + ((dy + 0.62) ** 2) / 0.018)) * 0.55
    img = img + (255 - img) * spec[..., None]
    img = np.clip(img, 0, 255)

    inside = (r2 <= 1.0).astype(float)
    face = Image.fromarray(np.dstack([img, inside * 255]).astype("uint8"), "RGBA")

    # aro: plata por fuera y negro por dentro
    ring = round(0.06 * S)
    full = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    outer = Image.new("L", (S, S), 0)
    ImageDraw.Draw(outer).ellipse((0, 0, S - 1, S - 1), fill=255)
    metal = vgradient(S, S, (240, 244, 248), (70, 78, 86)).convert("RGBA")
    metal.putalpha(outer)
    full.alpha_composite(metal)
    dark_m = Image.new("L", (S, S), 0)
    ImageDraw.Draw(dark_m).ellipse((S * 0.02, S * 0.02, S * 0.98, S * 0.98), fill=255)
    dark = Image.new("RGBA", (S, S), (6, 8, 6, 255))
    dark.putalpha(dark_m)
    full.alpha_composite(dark)
    inner = face.resize((S - 2 * ring, S - 2 * ring), Image.LANCZOS)
    full.alpha_composite(inner, (ring, ring))
    return full.resize((d, d), Image.LANCZOS)


# ------------------------------------------------------------------ fondo

def swoosh(size):
    big = 2
    layer = Image.new("L", (size * big, size * big), 0)
    dr = ImageDraw.Draw(layer)
    dr.ellipse((-330 * big, 520 * big, 1584 * big, 1215 * big), fill=255)
    dr.ellipse((-290 * big, 488 * big, 1544 * big, 1160 * big), fill=0)
    layer = layer.resize((size, size), Image.LANCZOS)
    ImageDraw.Draw(layer).rectangle((0, 0, size, 860), fill=0)
    col = vgradient(size, size, (150, 240, 55), (28, 125, 14)).convert("RGBA")
    col.putalpha(layer)
    return col


def main():
    base = vgradient(W, W, (3, 6, 3), (8, 22, 8)).convert("RGBA")
    base.alpha_composite(glow(W, (420, 760), 700, (60, 200, 30), 0.35))
    base.alpha_composite(swoosh(W))

    paste_with_shadow(base, five_layer(740), (690, 470))
    paste_with_shadow(base, ball_layer(500), (285, 890))

    final = Image.new("RGB", (W, W), (255, 255, 255))
    final.paste(base.convert("RGB"), (0, 0), rounded_mask(W, CORNER))
    out = os.path.join(ROOT, "icono.png")
    final.save(out)
    print("Logo guardado en", out)


if __name__ == "__main__":
    main()
