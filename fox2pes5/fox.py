import sys, pickle
sys.path.insert(0, '/projects/sandbox/tevez/tools')
from fmdl import Fmdl
from img import Image
BASE = '/projects/sandbox/tevez/CARLOS TEVES UPDATE/'
def load_models():
    face = Fmdl(open(BASE + '3712/#Win/face_fpk/face_high.fmdl', 'rb').read())
    hair = Fmdl(open(BASE + '3712/#Win/face_fpk/hair_high.fmdl', 'rb').read())
    return face, hair
def load_tex(n):
    w, h, px = pickle.load(open('/projects/sandbox/tevez/work/%s.pkl' % n, 'rb'))
    return Image(w, h, px)
def mesh_tris(f, mi, vflip=False):
    m = f.meshes[mi]; P = m['attrs'][0][1]; UV = m['attrs'][8][1]
    out = []
    for t in m['tris']:
        uv = tuple((UV[i][0], (1 - UV[i][1]) if vflip else UV[i][1]) for i in t)
        out.append((tuple(P[i] for i in t), uv, None))
    return out
