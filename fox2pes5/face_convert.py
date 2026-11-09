"""Cara de Tevez (PES 2016+ PC, Fox Engine) -> cara PES 5 Xbox.

Plantilla: cara original de Ronaldinho PES 5 Xbox (malla, UV, huesos, morphs, borde).
1. Alineacion plantilla -> Tevez con puntos de referencia (escala por eje + RBF).
2. Forma: cada vertice de la piel se desplaza hasta la superficie de Tevez a lo largo
   de su normal; el desplazamiento se anula en el borde de union con la cabeza.
3. Textura: cada texel de la plantilla se proyecta sobre Tevez y se lee su textura.
"""
import sys, math, pickle, struct
sys.path.insert(0, '/projects/sandbox/tevez/tools'); sys.path.insert(0, '/projects/sandbox/tevez/work')
sys.path.insert(0, '/projects/sandbox/PES-XBOX-face-converter/tools')
import fox, pes5, render, quant
from geom import *
from img import Image
from landmarks import LM
from pesport import container, texture, xbox_model as xm
from pesport.convert import FACE_RIM

TEMPLATE = '/projects/sandbox/PES-XBOX-face-converter/Ronaldinho Face PES5 XBOX.bin'
OUT = sys.argv[1] if len(sys.argv) > 1 else '/projects/sandbox/tevez/work/out_face.bin'
BRIGHT = 1.0

secs = pes5.load(TEMPLATE)
m0 = xm.read(secs[0]); m1 = xm.read(secs[1]); tsec = secs[2]
orig_tex = pes5.tex_image(tsec)
SKIN = 3


def uv_lookup(tris, u, v):
    best = None
    for t in tris:
        ps, uvs = t[0], t[1]
        a, b, c = uvs
        den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(den) < 1e-12: continue
        w0 = ((b[1] - c[1]) * (u - c[0]) + (c[0] - b[0]) * (v - c[1])) / den
        w1 = ((c[1] - a[1]) * (u - c[0]) + (a[0] - c[0]) * (v - c[1])) / den
        w2 = 1 - w0 - w1
        err = -min(w0, w1, w2, 0)
        if best is None or err < best[0]:
            ww = [max(0, x) for x in (w0, w1, w2)]; s = sum(ww)
            best = (err, tuple(sum(ww[i] / s * ps[i][k] for i in range(3)) for k in range(3)))
    return best[1]


# ---------------------------------------------------------------- modelos
face, hair = fox.load_models()
fox_skin = []
for f, mi in ((face, 0), (face, 2), (hair, 0)):
    m = f.meshes[mi]; P = m['attrs'][0][1]; UV = m['attrs'][8][1]
    for t in m['tris']:
        fox_skin.append((P[t[0]], P[t[1]], P[t[2]], (UV[t[0]], UV[t[1]], UV[t[2]])))
FOX = Mesh(fox_skin, 0.01)
fox_tex = fox.load_tex('face_bsm_alp')
fox_face_tris = fox.mesh_tris(face, 2) + fox.mesh_tris(face, 0)

tmpl_tris = pes5.model_tris(m0, [SKIN])
src = [uv_lookup(tmpl_tris, *l[1]) for l in LM]
dst = [uv_lookup(fox_face_tris, *l[2]) for l in LM]

# Escala por eje: Fox_x <- z, Fox_y <- y, Fox_z <- x
ax = [(2, 0), (1, 1), (0, 2)]
lin = {}
for fk, pk in ax:
    lin[fk] = (pk,) + linfit([s[pk] for s in src], [d[fk] for d in dst])
# la escala de profundidad se toma como la media de las otras (poco definida con puntos casi planos)
sx, sy = lin[0][1], lin[1][1]
s_mean = (abs(sx) + abs(sy)) / 2
pk = lin[2][0]
bz = sum(d[2] for d in dst) / len(dst) - s_mean * sum(s[pk] for s in src) / len(src)
lin[2] = (pk, s_mean, bz)
print('escalas', {k: round(v[1], 5) for k, v in lin.items()})


def A(p):
    return tuple(lin[k][1] * p[lin[k][0]] + lin[k][2] for k in range(3))


def A_dir(n):
    return norm(tuple(lin[k][1] * n[lin[k][0]] for k in range(3)))


def A_inv_delta(d):
    out = [0.0, 0.0, 0.0]
    for k in range(3):
        out[lin[k][0]] = d[k] / lin[k][1]
    return tuple(out)


res = [sub(d, A(s)) for s, d in zip(src, dst)]
for (n, _, _), r in zip(LM, res):
    print('  residuo %-10s %6.1f mm' % (n, length(r) * 1000))
rbf = RBF([A(s) for s in src], res, sigma=0.03, reg=1e-3)


def W(p):
    q = A(p)
    return add(q, rbf(q))


# ---------------------------------------------------------------- forma
RIM = FACE_RIM


def rim_dist(p):
    return min(length(sub(p, r)) for r in RIM)


def weight(p):
    d = rim_dist(p)
    t = max(0.0, min(1.0, (d - 2.0) / 14.0))
    return t * t * (3 - 2 * t)


MAXOFF = 6.0
verts = m0.vertices
skin_ids = range(m0.parts[SKIN].vstart, m0.parts[SKIN].vstart + m0.parts[SKIN].vcount)
offs = {}
miss = 0
for i in skin_ids:
    v = verts[i]
    p = v[:3]; n = norm(v[3:6])
    q = W(p); d = A_dir(n)
    h = FOX.ray_nearest(q, d, 0.02)
    if h is None:
        offs[i] = None; miss += 1; continue
    hit = add(q, mul(d, h[0]))
    o = A_inv_delta(sub(hit, q))
    l = length(o)
    if l > MAXOFF: o = mul(o, MAXOFF / l)
    offs[i] = mul(o, weight(p))
print('vertices piel sin impacto:', miss)
# rellenar y suavizar con vecinos (por posicion)
nbr = {i: set() for i in skin_ids}
for t in xm.strip_triangles(m0.parts[SKIN].indices):
    for a in t:
        for b in t:
            if a != b: nbr[a].add(b)
poskey = {}
for i in skin_ids:
    poskey.setdefault(tuple(round(c, 3) for c in verts[i][:3]), []).append(i)
for g in poskey.values():
    for a in g:
        for b in g:
            if a != b: nbr[a] |= nbr[b]
for it in range(30):
    if all(offs[i] is not None for i in skin_ids): break
    for i in skin_ids:
        if offs[i] is None:
            ok = [offs[j] for j in nbr[i] if offs[j] is not None]
            if ok: offs[i] = mul(tuple(sum(o[k] for o in ok) for k in range(3)), 1 / len(ok))
for i in skin_ids:
    if offs[i] is None: offs[i] = (0.0, 0.0, 0.0)
for it in range(2):
    new = {}
    for i in skin_ids:
        ns = [offs[j] for j in nbr[i]] or [offs[i]]
        avg = mul(tuple(sum(o[k] for o in ns) for k in range(3)), 1 / len(ns))
        new[i] = mul(add(offs[i], avg), 0.5)
    offs = new
# vertices duplicados (misma posicion) -> mismo desplazamiento
for g in poskey.values():
    o = mul(tuple(sum(offs[i][k] for i in g) for k in range(3)), 1 / len(g))
    for i in g: offs[i] = o
for i in skin_ids:   # borde exacto
    if rim_dist(verts[i][:3]) < 0.05: offs[i] = (0.0, 0.0, 0.0)
skin_pts = [(verts[i][:3], offs[i]) for i in skin_ids]


def interp_off(p):
    near = sorted(skin_pts, key=lambda s: length(sub(s[0], p)))[:4]
    ws = [1 / (length(sub(s[0], p)) + 0.5) for s in near]
    o = tuple(sum(w * s[1][k] for w, s in zip(ws, near)) / sum(ws) for k in range(3))
    return mul(o, weight(p))


def deform(model, use_direct):
    newv = []
    for i, v in enumerate(model.vertices):
        p = v[:3]
        if rim_dist(p) < 0.05:
            o = (0.0, 0.0, 0.0)
        elif use_direct and i in offs:
            o = offs[i]
        else:
            o = interp_off(p)
        newv.append(add(p, o) + tuple(v[3:]))
    # normales recalculadas por posicion, mezcladas con la original segun el peso
    acc = {}
    for part in model.parts:
        for t in xm.strip_triangles(part.indices):
            ps = [newv[i][:3] for i in t]
            n = cross(sub(ps[1], ps[0]), sub(ps[2], ps[0]))
            for i in t:
                k = tuple(round(c, 3) for c in model.vertices[i][:3])
                acc[k] = add(acc.get(k, (0, 0, 0)), n)
    out = []
    for i, v in enumerate(newv):
        on = norm(model.vertices[i][3:6])
        k = tuple(round(c, 3) for c in model.vertices[i][:3])
        nn = norm(acc.get(k, on))
        if dot(nn, on) < 0: nn = mul(nn, -1)
        w = weight(model.vertices[i][:3])
        fn = norm(add(mul(on, 1 - w), mul(nn, w))) if length(nn) > 0 else on
        if length(fn) == 0: fn = on
        out.append(tuple(v[:3]) + mul(fn, 8.0) + tuple(v[6:8]))
    model.vertices = out
    return model


orig_m0_verts = list(m0.vertices)
m0 = deform(m0, True)
m1 = deform(m1, False)
print('desplazamiento max cara:', round(max(length(o) for o in offs.values()), 2))

# ---------------------------------------------------------------- textura
pix, pal, TW, TH = texture.info(tsec)
SS = 3
tex_tris = xm.strip_triangles(m0.parts[SKIN].indices)
cover = {}
for ti, t in enumerate(tex_tris):
    uv = [(orig_m0_verts[i][6] * TW * SS, orig_m0_verts[i][7] * TH * SS) for i in t]
    xs = [a[0] for a in uv]; ys = [a[1] for a in uv]
    a, b, c = uv
    den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
    if abs(den) < 1e-9: continue
    for y in range(max(0, int(min(ys))), min(TH * SS, int(max(ys)) + 1)):
        for x in range(max(0, int(min(xs))), min(TW * SS, int(max(xs)) + 1)):
            px_, py_ = x + 0.5, y + 0.5
            w0 = ((b[1] - c[1]) * (px_ - c[0]) + (c[0] - b[0]) * (py_ - c[1])) / den
            w1 = ((c[1] - a[1]) * (px_ - c[0]) + (a[0] - c[0]) * (py_ - c[1])) / den
            w2 = 1 - w0 - w1
            if min(w0, w1, w2) >= -1e-4:
                cover[(x, y)] = (ti, (w0, w1, w2))
print('cobertura subtexels', len(cover), 'de', TW * TH * SS * SS)
_u = linfit([l[1][0] for l in LM], [l[2][0] for l in LM])
_v = linfit([l[1][1] for l in LM], [l[2][1] for l in LM])
_uvA = lambda u, v: (_u[0] * u + _u[1], _v[0] * v + _v[1], 0.0)
_uvR = RBF([(l[1][0], l[1][1], 0.0) for l in LM], [sub((l[2][0], l[2][1], 0.0), _uvA(*l[1])) for l in LM], 0.12, 1e-4)


def uvwarp(u, v):
    p = add(_uvA(u, v), _uvR((u, v, 0.0)))
    return p[0], p[1]


acc = {}
nohit = 0
for (x, y), (ti, ws) in cover.items():
    t = tex_tris[ti]
    vs = [orig_m0_verts[i] for i in t]
    p = tuple(sum(ws[j] * vs[j][k] for j in range(3)) for k in range(3))
    n = norm(tuple(sum(ws[j] * vs[j][3 + k] for j in range(3)) for k in range(3)))
    q = W(p); d = A_dir(n)
    h = FOX.ray_nearest(q, d, 0.03)
    tu, tv = (x + 0.5) / (TW * SS), (y + 0.5) / (TH * SS)
    if h is None:
        nohit += 1
        if tv < 0.45:
            continue          # ojos: se conservan los de la plantilla
        u, v = uvwarp(tu, tv)  # boca abierta: se lee la textura de Tevez por correspondencia 2D
    else:
        tt, tri, b1, b2 = h
        uvs = FOX.tris[tri][3]
        u = (1 - b1 - b2) * uvs[0][0] + b1 * uvs[1][0] + b2 * uvs[2][0]
        v = (1 - b1 - b2) * uvs[0][1] + b1 * uvs[1][1] + b2 * uvs[2][1]
    c = fox_tex.sample(u, v)
    acc.setdefault((x // SS, y // SS), []).append(c[:3])
print('subtexels sin impacto', nohit)
out = []
for y in range(TH):
    for x in range(TW):
        l = acc.get((x, y))
        if l and len(l) >= 2:
            out.append(tuple(min(255, sum(c[k] for c in l) / len(l) * BRIGHT) for k in range(3)) + (255,))
        else:
            out.append(None)
# huecos (ojos, interior de boca, zonas sin cobertura): se conserva el texel original
keep = 0
for i, c in enumerate(out):
    if c is None:
        x, y = i % TW, i // TW
        tv = (y + 0.5) / TH
        if tv >= 0.45:
            # boca: textura de Tevez por correspondencia 2D (promedio 3x3 sub-muestras)
            cs = [fox_tex.sample(*uvwarp((x + (a + 0.5) / 3) / TW, (y + (b + 0.5) / 3) / TH)) for a in range(3) for b in range(3)]
            out[i] = tuple(sum(cc[k] for cc in cs) / len(cs) for k in range(3)) + (255,)
        else:
            out[i] = orig_tex.px[i]; keep += 1
print('texels conservados de la plantilla:', keep)
# Ojos: la textura de Tevez los tiene cerrados (en PES 2016+ el globo ocular es aparte).
# Dentro de la abertura del ojo de la plantilla se usan los ojos pintados de PES 5.
EYES = [((0.273, 0.296), (0.120, 0.034)), ((0.728, 0.296), (0.120, 0.034))]
for i in range(TW * TH):
    x, y = i % TW, i // TW
    u, v = (x + 0.5) / TW, (y + 0.5) / TH
    mk = 0.0
    for (cu, cv), (au, av) in EYES:
        r = math.hypot((u - cu) / au, (v - cv) / av)
        mk = max(mk, max(0.0, min(1.0, (1.15 - r) / 0.35)))
    if mk > 0:
        o = orig_tex.px[i]; c = out[i]
        out[i] = tuple(c[k] * (1 - mk) + o[k] * mk for k in range(3)) + (255,)
img = Image(TW, TH, out)
img.save_png('/projects/sandbox/tevez/work/face_tex_new.png')
pickle.dump(out, open('/projects/sandbox/tevez/work/face_tex_new.pkl', 'wb'))
newtex = quant.build_texture(tsec, img)

raw = container.join([xm.write(m0), xm.write(m1), newtex])
open(OUT, 'wb').write(container.pack(raw))
print('escrito', OUT)
