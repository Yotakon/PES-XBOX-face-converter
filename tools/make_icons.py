#!/usr/bin/env python3
"""Genera los iconos de la app Android a partir de un PNG cuadrado (solo libreria estandar).

Uso (desde la raiz del repositorio):  python3 tools/make_icons.py icono.png

Crea:
  - mipmap-*/ic_launcher.png            icono clasico (cuadrado con esquinas redondeadas)
  - mipmap-*/ic_launcher_round.png      icono redondo para launchers antiguos
  - mipmap-*/ic_launcher_foreground.png capa frontal del icono adaptable (Android 8+)
  - mipmap-anydpi-v26/ic_launcher(.xml, _round.xml)  icono adaptable: el launcher aplica
    la forma (circulo, cuadrado, gota, squircle...) y la imagen queda dentro de la zona segura
  - drawable-nodpi/logo.png             logo para la cabecera de la app
  - values/ic_launcher_colors.xml       color de fondo del icono adaptable (tomado del borde)

El fondo blanco de fuera de las esquinas redondeadas se vuelve transparente.
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


# ------------------------------------------------------------------ PNG

def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("El icono debe ser un PNG")
    pos, idat, plte, trns = 8, b"", None, None
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if typ == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
    if depth != 8 or interlace:
        raise SystemExit("PNG no soportado: usa 8 bits por canal y sin entrelazado")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    raw = zlib.decompress(idat)
    stride = w * channels
    rows, prev = [], bytearray(stride)
    p = 0
    for _ in range(h):
        f = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - channels] if i >= channels else 0
            b = prev[i]
            c = prev[i - channels] if i >= channels else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        rows.append(line)
        prev = line
    px = []
    for line in rows:
        for x in range(w):
            q = line[x * channels:(x + 1) * channels]
            if ctype == 0:
                px.append((q[0], q[0], q[0], 255))
            elif ctype == 2:
                px.append((q[0], q[1], q[2], 255))
            elif ctype == 3:
                k = q[0]
                al = trns[k] if trns and k < len(trns) else 255
                px.append((plte[3 * k], plte[3 * k + 1], plte[3 * k + 2], al))
            elif ctype == 4:
                px.append((q[0], q[0], q[0], q[1]))
            else:
                px.append(tuple(q))
    return w, h, px


def write_png(path, w, h, px):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for r, g, b, a in px[y * w:(y + 1) * w]:
            raw += bytes((r, g, b, a))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


# ------------------------------------------------------------------ imagen (RGBA premultiplicado, floats)

def to_premul(px):
    return [(r * a / 255.0, g * a / 255.0, b * a / 255.0, float(a)) for r, g, b, a in px]


def from_premul(px):
    out = []
    for r, g, b, a in px:
        if a <= 0.5:
            out.append((0, 0, 0, 0))
        else:
            k = 255.0 / a
            out.append((min(255, round(r * k)), min(255, round(g * k)), min(255, round(b * k)), min(255, round(a))))
    return out


def resample_axis(src, sw, sh, dw, horizontal):
    """Reduccion por area (promedio exacto de cobertura) en un eje."""
    n_src = sw if horizontal else sh
    scale = n_src / dw
    weights = []
    for i in range(dw):
        a, b = i * scale, (i + 1) * scale
        taps = []
        j = int(a)
        while j < b and j < n_src:
            cov = min(b, j + 1) - max(a, j)
            if cov > 0:
                taps.append((j, cov / scale))
            j += 1
        weights.append(taps)
    if horizontal:
        out = []
        for y in range(sh):
            row = src[y * sw:(y + 1) * sw]
            for taps in weights:
                acc = [0.0, 0.0, 0.0, 0.0]
                for j, wt in taps:
                    p = row[j]
                    acc[0] += p[0] * wt; acc[1] += p[1] * wt; acc[2] += p[2] * wt; acc[3] += p[3] * wt
                out.append(tuple(acc))
        return out, dw, sh
    out = [None] * (sw * dw)
    for i, taps in enumerate(weights):
        for x in range(sw):
            acc = [0.0, 0.0, 0.0, 0.0]
            for j, wt in taps:
                p = src[j * sw + x]
                acc[0] += p[0] * wt; acc[1] += p[1] * wt; acc[2] += p[2] * wt; acc[3] += p[3] * wt
            out[i * sw + x] = tuple(acc)
    return out, sw, dw


def resize(src, sw, sh, d):
    if d > sw or d > sh:
        raise SystemExit("El icono debe medir al menos %d px" % d)
    tmp, tw, th = resample_axis(src, sw, sh, d, True)
    out, _, _ = resample_axis(tmp, tw, th, d, False)
    return out


def canvas(size, color=None):
    if color is None:
        return [(0.0, 0.0, 0.0, 0.0)] * (size * size)
    r, g, b = color
    return [(float(r), float(g), float(b), 255.0)] * (size * size)


def paste(dst, ds, src, ss, ox, oy):
    """Composicion 'over' en premultiplicado."""
    for y in range(ss):
        for x in range(ss):
            p = src[y * ss + x]
            if p[3] <= 0:
                continue
            i = (oy + y) * ds + ox + x
            q = dst[i]
            k = 1 - p[3] / 255.0
            dst[i] = (p[0] + q[0] * k, p[1] + q[1] * k, p[2] + q[2] * k, p[3] + q[3] * k)


def circle_mask(px, size):
    """Recorta a circulo con borde suavizado (supermuestreo 4x4)."""
    c = size / 2.0
    out = []
    for y in range(size):
        for x in range(size):
            inside = 0
            for sy in range(4):
                for sx in range(4):
                    dx = x + (sx + 0.5) / 4 - c
                    dy = y + (sy + 0.5) / 4 - c
                    inside += dx * dx + dy * dy <= c * c
            f = inside / 16.0
            p = px[y * size + x]
            out.append((p[0] * f, p[1] * f, p[2] * f, p[3] * f))
    return out


# ------------------------------------------------------------------ preparacion del arte

def clear_outside(w, h, px, tol=40):
    """Fondo claro de fuera de las esquinas redondeadas -> transparente (relleno desde las esquinas)."""
    out = list(px)
    seen = bytearray(w * h)
    stack = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]

    def is_bg(p):
        r, g, b, a = p
        return a < 30 or (min(r, g, b) >= 255 - tol and max(r, g, b) - min(r, g, b) < 30)

    while stack:
        x, y = stack.pop()
        if x < 0 or y < 0 or x >= w or y >= h:
            continue
        i = y * w + x
        if seen[i]:
            continue
        seen[i] = 1
        if not is_bg(px[i]):
            continue
        out[i] = (0, 0, 0, 0)
        stack += [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
    return out


def crop_square(w, h, px):
    xs = [i % w for i, p in enumerate(px) if p[3] > 0]
    ys = [i // w for i, p in enumerate(px) if p[3] > 0]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    side = max(x1 - x0, y1 - y0) + 1
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    sx, sy = cx - side // 2, cy - side // 2
    out = []
    for y in range(sy, sy + side):
        for x in range(sx, sx + side):
            out.append(px[y * w + x] if 0 <= x < w and 0 <= y < h else (0, 0, 0, 0))
    return side, out


def corner_ratio(side, px):
    """Radio de las esquinas redondeadas relativo al lado (por la diagonal transparente)."""
    k = 0
    while k < side // 2 and px[k * side + k][3] < 128:
        k += 1
    # en un cuadrado con esquina de radio r, la diagonal entra r*(1 - 1/sqrt2)
    return (k / (1 - 1 / math.sqrt(2))) / side


def border_color(side, px):
    ring = [px[y * side + x] for y in range(side) for x in range(side)
            if px[y * side + x][3] > 250 and min(x, y, side - 1 - x, side - 1 - y) < side * 0.03]
    n = len(ring) or 1
    return tuple(round(sum(p[c] for p in ring) / n) for c in range(3))


# ------------------------------------------------------------------ main

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    w, h, px = read_png(sys.argv[1])
    print("Icono %dx%d" % (w, h))
    px = clear_outside(w, h, px)
    side, art = crop_square(w, h, px)
    k = corner_ratio(side, art)
    bg = border_color(side, art)
    print("Arte %d px, radio de esquina %.3f, color de borde #%02X%02X%02X" % ((side, k) + bg))

    # Reducimos una vez a 768 px para que el resto de tamaños sean rapidos.
    master_side = min(side, 768)
    master = resize(to_premul(art), side, side, master_side)

    # Lado del arte dentro de la capa adaptable (108 dp) para que quepa entero en el circulo
    # de la zona segura (66 dp): (s/2 - r)*sqrt2 + r <= 33  con r = k*s
    fg_dp = 33.0 / ((0.5 - k) * math.sqrt(2) + k)
    fg_dp = min(fg_dp, 72.0)
    print("Arte en la capa adaptable: %.1f dp de 108 dp" % fg_dp)

    for name, d in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + name)
        legacy = round(48 * d)
        write_png(os.path.join(folder, "ic_launcher.png"), legacy, legacy,
                  from_premul(resize(master, master_side, master_side, legacy)))

        # redondo: circulo del color del borde con el arte dentro (cabe entero)
        inner = int(round(legacy * (0.5 / ((0.5 - k) * math.sqrt(2) + k)) * 0.96))
        rnd = canvas(legacy, bg)
        paste(rnd, legacy, resize(master, master_side, master_side, inner), inner,
              (legacy - inner) // 2, (legacy - inner) // 2)
        write_png(os.path.join(folder, "ic_launcher_round.png"), legacy, legacy,
                  from_premul(circle_mask(rnd, legacy)))

        layer = round(108 * d)
        inner = int(round(fg_dp * d))
        fg = canvas(layer)
        paste(fg, layer, resize(master, master_side, master_side, inner), inner,
              (layer - inner) // 2, (layer - inner) // 2)
        write_png(os.path.join(folder, "ic_launcher_foreground.png"), layer, layer, from_premul(fg))
        print("  %-8s %3d px  (capa adaptable %d px)" % (name, legacy, layer))

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, n), "w") as f:
            f.write(ADAPTIVE_XML)

    write_png(os.path.join(RES, "drawable-nodpi", "logo.png"), 192, 192,
              from_premul(resize(master, master_side, master_side, 192)))

    with open(os.path.join(RES, "values", "ic_launcher_colors.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n</resources>\n' % bg)
    print("Iconos generados en", RES)


if __name__ == "__main__":
    main()
