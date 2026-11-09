"""Alineacion plantilla PES 5 (Ronaldinho) -> Tevez (Fox) con puntos de referencia."""
import sys
sys.path.insert(0, '/projects/sandbox/tevez/tools'); sys.path.insert(0, '/projects/sandbox/tevez/work')
sys.path.insert(0, '/projects/sandbox/PES-XBOX-face-converter/tools')
import fox, pes5
from geom import *
from landmarks import LM
from pesport import xbox_model as xm

TEMPLATE = '/projects/sandbox/PES-XBOX-face-converter/Ronaldinho Face PES5 XBOX.bin'


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


_m0 = xm.read(pes5.load(TEMPLATE)[0])
_face, _hair = fox.load_models()
_fox_face_tris = fox.mesh_tris(_face, 2) + fox.mesh_tris(_face, 0)
src = [uv_lookup(pes5.model_tris(_m0, [3]), *l[1]) for l in LM]
dst = [uv_lookup(_fox_face_tris, *l[2]) for l in LM]

# Fox_x <- z, Fox_y <- y, Fox_z <- x (PES 5: x delante, -y arriba, z lateral)
lin = {}
for fk, pk in ((0, 2), (1, 1)):
    lin[fk] = (pk,) + linfit([s[pk] for s in src], [d[fk] for d in dst])
s_mean = (abs(lin[0][1]) + abs(lin[1][1])) / 2
lin[2] = (0, s_mean, sum(d[2] for d in dst) / len(dst) - s_mean * sum(s[0] for s in src) / len(src))


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
rbf = RBF([A(s) for s in src], res, sigma=0.03, reg=1e-3)


def W(p):
    q = A(p)
    return add(q, rbf(q))
