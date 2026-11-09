"""Cuantizacion a 256 colores y escritura de texturas PES (8 bpp, paleta PS2)."""
import struct, sys
sys.path.insert(0, '/projects/sandbox/PES-XBOX-face-converter/tools')
from pesport import texture


def median_cut(colors, n=256):
    boxes = [list(colors)]
    while len(boxes) < n:
        # caja con mayor rango ponderado
        best = None
        for bi, b in enumerate(boxes):
            if len(b) < 2: continue
            rng = [max(c[k] for c in b) - min(c[k] for c in b) for k in range(4)]
            k = max(range(4), key=lambda i: rng[i])
            score = rng[k] * len(b) ** 0.5
            if best is None or score > best[0]: best = (score, bi, k)
        if best is None or best[0] == 0: break
        _, bi, k = best
        b = sorted(boxes.pop(bi), key=lambda c: c[k])
        h = len(b) // 2
        boxes += [b[:h], b[h:]]
    pal = [tuple(sum(c[k] for c in b) / len(b) for k in range(4)) for b in boxes if b]
    return pal


def kmeans_refine(colors, pal, iters=4):
    for _ in range(iters):
        sums = [[0, 0, 0, 0, 0] for _ in pal]
        for c in colors:
            i = nearest(pal, c)
            s = sums[i]
            for k in range(4): s[k] += c[k]
            s[4] += 1
        pal = [tuple(s[k] / s[4] for k in range(4)) if s[4] else p for s, p in zip(sums, pal)]
    return pal


def nearest(pal, c):
    best, bi = 1e18, 0
    for i, p in enumerate(pal):
        d = (p[0] - c[0]) ** 2 + (p[1] - c[1]) ** 2 + (p[2] - c[2]) ** 2 + 2 * (p[3] - c[3]) ** 2
        if d < best: best, bi = d, i
    return bi


def downsample(px, w, h):
    out = []
    for y in range(h // 2):
        for x in range(w // 2):
            q = [px[(2 * y + dy) * w + 2 * x + dx] for dy in (0, 1) for dx in (0, 1)]
            out.append(tuple(sum(c[k] for c in q) / 4 for k in range(4)))
    return out


def build_texture(template_sec, img):
    """Nueva seccion de textura con la cabecera de la plantilla.

    img: Image RGBA (alfa 0-255). Si la plantilla trae mipmap a mitad de tamano, se regenera.
    """
    pix, pal_off, w, h = texture.info(template_sec)
    assert (w, h) == (img.w, img.h), (w, h, img.w, img.h)
    colors = [tuple(float(x) for x in c) for c in img.px]
    pal = kmeans_refine(colors, median_cut(colors, 256))
    while len(pal) < 256: pal.append((0, 0, 0, 0))
    out = bytearray(template_sec)
    for i, p in enumerate(pal):
        slot = texture.clut_index(i) * 4
        out[pal_off + slot:pal_off + slot + 4] = bytes((
            max(0, min(255, round(p[0]))), max(0, min(255, round(p[1]))),
            max(0, min(255, round(p[2]))), max(0, min(128, round(p[3] / 2)))))
    cache = {}

    def idx(c):
        k = tuple(int(x) >> 1 for x in c)
        if k not in cache: cache[k] = nearest(pal, c)
        return cache[k]

    for i, c in enumerate(colors):
        out[pix + i] = idx(c)
    extra = len(template_sec) - pix - w * h
    if extra == (w // 2) * (h // 2):
        mip = downsample(colors, w, h)
        for i, c in enumerate(mip):
            out[pix + w * h + i] = idx(c)
    elif extra:
        raise ValueError('bloque extra de textura desconocido: %d bytes' % extra)
    return bytes(out)


def read_palette(sec):
    pix, pal_off, w, h = texture.info(sec)
    p = sec[pal_off:pal_off + 1024]
    out = []
    for i in range(256):
        k = texture.clut_index(i) * 4
        out.append((p[k], p[k + 1], p[k + 2], min(255, p[k + 3] * 2)))
    return out


def index_with_palette(template_sec, palette_sec, colors):
    """Textura con la cabecera de template_sec y la paleta de palette_sec."""
    pix, pal_off, w, h = texture.info(template_sec)
    ppix, ppal, _, _ = texture.info(palette_sec)
    out = bytearray(template_sec)
    out[pal_off:pal_off + 1024] = palette_sec[ppal:ppal + 1024]
    pal = read_palette(palette_sec)
    assert len(colors) == w * h
    for i, c in enumerate(colors):
        out[pix + i] = nearest(pal, c)
    return bytes(out)
