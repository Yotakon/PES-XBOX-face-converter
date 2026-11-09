"""Acceso a caras/pelos PES 5 Xbox usando las herramientas del repositorio."""
import sys
sys.path.insert(0, '/projects/sandbox/PES-XBOX-face-converter/tools')
from pesport import container, texture, xbox_model as xm
from img import Image

def load(fn):
    secs = container.split(container.unpack(open(fn, 'rb').read()))
    return secs

def tex_image(sec):
    pix, pal, w, h = texture.info(sec)
    p = sec[pal:pal + 1024]
    px = []
    for i in range(w * h):
        k = texture.clut_index(sec[pix + i]) * 4
        px.append((p[k], p[k + 1], p[k + 2], min(255, p[k + 3] * 2)))
    return Image(w, h, px)

def model_tris(m, parts=None):
    out = []
    for pi, p in enumerate(m.parts):
        if parts is not None and pi not in parts: continue
        for t in xm.strip_triangles(p.indices):
            vs = [m.vertices[i] for i in t]
            out.append((tuple(v[:3] for v in vs), tuple(v[6:8] for v in vs), None, pi))
    return out
