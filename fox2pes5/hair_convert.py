"""Pelo de Tevez (PES 2016+ PC) -> pelo PES 5 Xbox.

Plantilla: pelo original de Ronaldinho PES 5 Xbox (malla, UV, huesos, grupos).
- Forma: cada vertice se lleva a la superficie exterior de la cabeza de Tevez (pelo + cuero
  cabelludo + piel) a lo largo del rayo desde el centro de la cabeza, un poco por fuera.
- Textura: en cada texel se compone el pelo de Tevez (tarjetas con alfa) sobre la piel.
  Donde solo hay piel (frente, orejas, nuca) el texel queda transparente para que se vea la
  cara/cuello de debajo; asi el pelo largo de la plantilla desaparece.
"""
import sys, math, pickle
sys.path.insert(0, '/projects/sandbox/tevez/tools'); sys.path.insert(0, '/projects/sandbox/tevez/work')
sys.path.insert(0, '/projects/sandbox/PES-XBOX-face-converter/tools')
import fox, pes5, quant
from geom import *
from img import Image
from pesport import container, texture, xbox_model as xm

TEMPLATE = '/projects/sandbox/PES-XBOX-face-converter/Ronaldinho Hair PES5 XBOX.bin'
OUT = sys.argv[1] if len(sys.argv) > 1 else '/projects/sandbox/tevez/work/out_hair.bin'
LIFT = 0.0025     # m por encima de la superficie
DARK, LIGHT = 55.0, 80.0
NAPE_LO, NAPE_HI = 1.615, 1.635   # por debajo, la piel pintada oscura es cuello/sombra   # luminancia: pelo <= DARK, piel >= LIGHT

import align   # transformacion plantilla PES5 <-> Fox (de la cara)
W, A_dir, A_inv_delta, lin = align.W, align.A_dir, align.A_inv_delta, align.lin

secs = pes5.load(TEMPLATE)
hm = xm.read(secs[0]); t1, t2 = secs[1], secs[2]

face, hair = fox.load_models()
face_tex = fox.load_tex('face_bsm_alp'); hair_tex = fox.load_tex('hair_parts_bsm_alp')
tris = []
for f, mi, kind in ((face, 0, 'skin'), (face, 2, 'skin'), (hair, 0, 'skin'), (hair, 1, 'hair')):
    m = f.meshes[mi]; P = m['attrs'][0][1]; UV = m['attrs'][8][1]
    for t in m['tris']:
        tris.append((P[t[0]], P[t[1]], P[t[2]], (kind, (UV[t[0]], UV[t[1]], UV[t[2]]))))
MESH = Mesh(tris, 0.012)
CENTER = (0.0, 1.70, 0.045)


def lum(c): return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]


def hit_uv(h):
    t, ti, b1, b2 = h
    uvs = MESH.tris[ti][3][1]
    return ((1 - b1 - b2) * uvs[0][0] + b1 * uvs[1][0] + b2 * uvs[2][0],
            (1 - b1 - b2) * uvs[0][1] + b1 * uvs[1][1] + b2 * uvs[2][1])


# ---------------------------------------------------------------- forma
newpos = []
for v in hm.vertices:
    q = W(v[:3])
    d = norm(sub(q, CENTER))
    hits = MESH.ray_all(CENTER, d, 0.0, 0.3)
    if hits:
        t = hits[-1][0]          # superficie mas exterior
    else:
        t = length(sub(q, CENTER))
    newpos.append(add(CENTER, mul(d, t + LIFT)))
verts = []
for v, p in zip(hm.vertices, newpos):
    # de Fox a PES5: desplazamiento respecto a la posicion transformada
    o = A_inv_delta(sub(p, W(v[:3])))
    verts.append(add(v[:3], o) + tuple(v[3:]))
# normales
acc = {}
for part in hm.parts:
    for t in xm.strip_triangles(part.indices):
        ps = [verts[i][:3] for i in t]
        n = cross(sub(ps[1], ps[0]), sub(ps[2], ps[0]))
        for i in t:
            k = tuple(round(c, 3) for c in hm.vertices[i][:3])
            acc[k] = add(acc.get(k, (0, 0, 0)), n)
out = []
for i, v in enumerate(verts):
    on = norm(hm.vertices[i][3:6])
    nn = norm(acc.get(tuple(round(c, 3) for c in hm.vertices[i][:3]), on))
    if length(nn) == 0: nn = on
    if dot(nn, on) < 0: nn = mul(nn, -1)
    out.append(tuple(v[:3]) + mul(nn, 8.0) + tuple(v[6:8]))
orig_verts = hm.vertices
hm.vertices = out
moved = [length(sub(a[:3], b[:3])) for a, b in zip(out, orig_verts)]
print('desplazamiento pelo: medio %.1f max %.1f' % (sum(moved) / len(moved), max(moved)))

# ---------------------------------------------------------------- UV nuevas
# Las UV de la plantilla reutilizan cada zona de la textura en varios sitios. Como todos los
# vertices quedan sobre una superficie "estrellada" respecto a CENTER, la direccion desde
# CENTER identifica el punto: UV = proyeccion polar de esa direccion (sin costuras utiles).
UP = (0.0, 1.0, 0.0)
FWD = (0.0, 0.0, 1.0)
SIDE = (1.0, 0.0, 0.0)
THMAX = math.radians(150)
MARGIN = 0.02


def dir_to_uv(d):
    th = math.acos(max(-1.0, min(1.0, dot(d, UP))))
    r = min(1.0, th / THMAX)
    f = dot(d, FWD); sd = dot(d, SIDE)
    l = math.hypot(f, sd) or 1.0
    # u: delante (0) -> detras (1); v: lateral
    u = 0.5 - 0.5 * r * f / l
    v = 0.5 + 0.5 * r * sd / l
    return MARGIN + (1 - 2 * MARGIN) * u, MARGIN + (1 - 2 * MARGIN) * v


def uv_to_dir(u, v):
    u = (u - MARGIN) / (1 - 2 * MARGIN); v = (v - MARGIN) / (1 - 2 * MARGIN)
    a = -(u - 0.5) * 2; b = (v - 0.5) * 2
    r = math.hypot(a, b)
    if r > 1.0: return None
    th = r * THMAX
    if r < 1e-9: return UP
    f = a / r; sd = b / r
    return norm(add(mul(UP, math.cos(th)), add(mul(FWD, math.sin(th) * f), mul(SIDE, math.sin(th) * sd))))


final = []
for v, p in zip(hm.vertices, newpos):
    u_, v_ = dir_to_uv(norm(sub(p, CENTER)))
    final.append(tuple(v[:6]) + (u_, v_))
hm.vertices = final

# ---------------------------------------------------------------- textura
pix, pal, TW, TH = texture.info(t1)
SS = 3


def shade(pf):
    d = norm(sub(pf, CENTER))
    start = add(CENTER, mul(d, 0.2))
    hits = MESH.ray_all(start, mul(d, -1), 0.0, 0.2)
    C = [0.0, 0.0, 0.0]; Acc = 0.0; hairA = 0.0
    for h in hits:
        kind = MESH.tris[h[1]][3][0]
        u, v = hit_uv(h)
        if kind == 'hair':
            c = hair_tex.sample(u, v); a = c[3] / 255.0
            if a < 0.05: continue
            hl = 1.0
        else:
            c = face_tex.sample(u, v); a = 1.0
            L = lum(c)
            hl = max(0.0, min(1.0, (LIGHT - L) / (LIGHT - DARK)))
            hy = start[1] - d[1] * h[0]          # altura del punto (m)
            hl *= max(0.0, min(1.0, (hy - NAPE_LO) / (NAPE_HI - NAPE_LO)))
        w = (1 - Acc) * a
        for k in range(3): C[k] += w * c[k]
        hairA += w * hl
        Acc += w
        if Acc > 0.98: break
    if Acc < 1e-3:
        return None
    return tuple(x / Acc for x in C) + (255.0 * min(1.0, hairA / Acc),)


# Cara PES 5 convertida en espacio Fox: donde no hay cara debajo, la piel se deja opaca
FACE_BIN = '/projects/sandbox/tevez/work/out_face.bin'
_fm = xm.read(pes5.load(FACE_BIN)[0])
_ft = []
for part in _fm.parts:
    for t in xm.strip_triangles(part.indices):
        _ft.append(tuple(W(_fm.vertices[i][:3]) for i in t) + (None,))
FACEM = Mesh(_ft, 0.012)


def face_below(d):
    return bool(FACEM.ray_all(CENTER, d, 0.0, 0.25))


texels = [None] * (TW * TH)
for y in range(TH):
    for x in range(TW):
        cs = []
        for sy in range(SS):
            for sx in range(SS):
                d = uv_to_dir((x + (sx + .5) / SS) / TW, (y + (sy + .5) / SS) / TH)
                if d is None: continue
                c = shade(add(CENTER, d))
                if c:
                    if c[3] < 250 and d[1] * 0.1 + CENTER[1] > NAPE_HI and not face_below(d):
                        c = c[:3] + (255.0,)
                    cs.append(c)
        if cs:
            texels[y * TW + x] = tuple(sum(c[k] for c in cs) / len(cs) for k in range(4))
print('texels pelo con datos:', sum(1 for t in texels if t), 'de', TW * TH)
# relleno de huecos por dilatacion (color), alfa 0
for it in range(TW):
    if all(texels): break
    nxt = list(texels)
    for i, t in enumerate(texels):
        if t: continue
        x, y = i % TW, i // TW
        ns = [texels[(y + dy) * TW + x + dx] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
              if 0 <= x + dx < TW and 0 <= y + dy < TH and texels[(y + dy) * TW + x + dx]]
        if ns:
            nxt[i] = tuple(sum(c[k] for c in ns) / len(ns) for k in range(3)) + (0.0,)
    texels = nxt
texels = [t or (30, 25, 22, 0) for t in texels]
img = Image(TW, TH, texels)
Image(TW, TH, [c[:3] + (255,) for c in texels]).save_png('/projects/sandbox/tevez/work/hair_tex_rgb.png')
Image(TW, TH, [(c[3],) * 3 + (255,) for c in texels]).save_png('/projects/sandbox/tevez/work/hair_tex_alpha.png')
new1 = quant.build_texture(t1, img)
# textura pequena (64x32): misma paleta
_, pal2, W2, H2 = texture.info(t2)
small = quant.downsample(texels, TW, TH)
new2 = quant.index_with_palette(t2, new1, small)
raw = container.join([xm.write(hm), new1, new2])
open(OUT, 'wb').write(container.pack(raw))
print('escrito', OUT)
