"""Rasterizador ortografico en Python puro para previsualizar mallas texturizadas."""
import math
from img import Image

def render(tris, size=256, view=(0, 1, 2), flip=(False, True), tex=None, bg=(40, 40, 48, 255), bounds=None, light=(0.3, 0.4, 1.0)):
    """tris: lista de ((p0,p1,p2),(uv0,uv1,uv2)|None, color|None). view = (eje_x, eje_y, eje_profundidad[,signo])."""
    ax, ay, az = view[:3]; sz = view[3] if len(view) > 3 else 1
    pts = [p for t in tris for p in t[0]]
    if bounds is None:
        xs = [p[ax] for p in pts]; ys = [p[ay] for p in pts]
        bounds = (min(xs), max(xs), min(ys), max(ys))
    x0, x1, y0, y1 = bounds
    sc = (size - 8) / max(x1 - x0, y1 - y0, 1e-9)
    ox = (size - (x1 - x0) * sc) / 2; oy = (size - (y1 - y0) * sc) / 2
    def proj(p):
        X = (p[ax] - x0) * sc + ox; Y = (p[ay] - y0) * sc + oy
        if flip[0]: X = size - X
        if flip[1]: Y = size - Y
        return X, Y, p[az] * sz
    im = Image(size, size, [bg] * (size * size))
    zb = [-1e30] * (size * size)
    ln = math.sqrt(sum(c * c for c in light)); L = [c / ln for c in light]
    for tt in tris:
        ps, uvs, col = tt[:3]
        ttex = tt[3] if len(tt) > 3 else tex
        a, b, c = (proj(p) for p in ps)
        # normal en espacio de vista para sombreado
        e1 = [ps[1][k] - ps[0][k] for k in range(3)]; e2 = [ps[2][k] - ps[0][k] for k in range(3)]
        n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
        nv = [n[ax], n[ay], n[az] * sz]
        nl = math.sqrt(sum(x * x for x in nv)) or 1
        shade = 0.35 + 0.65 * abs(sum(nv[k] / nl * L[k] for k in range(3)))
        minx = max(0, int(min(a[0], b[0], c[0]))); maxx = min(size - 1, int(max(a[0], b[0], c[0])) + 1)
        miny = max(0, int(min(a[1], b[1], c[1]))); maxy = min(size - 1, int(max(a[1], b[1], c[1])) + 1)
        den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(den) < 1e-12: continue
        for y in range(miny, maxy + 1):
            for x in range(minx, maxx + 1):
                px, py = x + 0.5, y + 0.5
                w0 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / den
                w1 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / den
                w2 = 1 - w0 - w1
                if w0 < 0 or w1 < 0 or w2 < 0: continue
                z = w0 * a[2] + w1 * b[2] + w2 * c[2]
                i = y * size + x
                if z <= zb[i]: continue
                if uvs is not None and ttex is not None:
                    u = w0 * uvs[0][0] + w1 * uvs[1][0] + w2 * uvs[2][0]
                    v = w0 * uvs[0][1] + w1 * uvs[1][1] + w2 * uvs[2][1]
                    t = ttex.sample(u, v)
                    if t[3] < 40: continue
                    cc = t
                else:
                    cc = col or (200, 200, 200, 255)
                zb[i] = z
                im.px[i] = (cc[0] * shade, cc[1] * shade, cc[2] * shade, 255)
    return im

def side_by_side(ims):
    w = sum(i.w for i in ims); h = max(i.h for i in ims)
    out = Image(w, h, [(0, 0, 0, 255)] * (w * h)); ox = 0
    for im in ims:
        for y in range(im.h):
            out.px[y * w + ox: y * w + ox + im.w] = im.px[y * im.w:(y + 1) * im.w]
        ox += im.w
    return out
