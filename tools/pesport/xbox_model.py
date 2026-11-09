"""Lectura y escritura del modelo de PES 5 Xbox (magic 0x20040520).

Ver docs/FORMATOS.md. El escritor reproduce byte a byte los archivos originales.
"""
import struct
from dataclasses import dataclass, field

MAGIC = 0x20040520
VERTEX_STRIDE = 32
# Orden en que el juego emite los cambios de estado antes de cada dibujo.
STATE_ORDER = (3, 1, 2, 0x0D, 4, 0x0A)
DRAW_TAG = 0x0507


@dataclass
class Morph:
    id: int
    indices: list          # indices de vertice (u16)
    deltas: list           # (dx, dy, dz) por indice


@dataclass
class Part:
    state: dict            # tag -> valor (estado completo vigente en el dibujo)
    indices: list          # tira de triangulos, indices globales
    vstart: int
    vcount: int
    ntris: int


@dataclass
class XboxModel:
    groups: list           # entradas de 24 bytes (bytes)
    bones: list            # IDs de hueso
    vertices: list         # (x, y, z, nx, ny, nz, u, v)
    morphs: list = field(default_factory=list)
    parts: list = field(default_factory=list)


def strip_triangles(seq):
    """Triangulos de una tira con la convencion de PES 5 Xbox (sin degenerados)."""
    tris = []
    for i in range(len(seq) - 2):
        a, b, c = seq[i], seq[i + 1], seq[i + 2]
        if a == b or b == c or a == c:
            continue
        tris.append((a, b, c) if i % 2 == 0 else (b, a, c))
    return tris


def _same_tri(t, u):
    a, b, c = u
    return t in ((a, b, c), (b, c, a), (c, a, b))


def canonical(t):
    """Rotacion canonica de un triangulo orientado (conserva el sentido de giro)."""
    a, b, c = t
    return min((a, b, c), (b, c, a), (c, a, b))


def build_strip(tris):
    """Convierte una lista de triangulos (orientados) en una tira con degenerados."""
    seq = []
    for t in tris:
        if len(seq) >= 2:
            x, y = seq[-2], seq[-1]
            par = (len(seq) - 2) % 2
            done = False
            for c in t:
                cand = (x, y, c) if par == 0 else (y, x, c)
                if _same_tri(cand, t) and len({x, y, c}) == 3:
                    seq.append(c)
                    done = True
                    break
            if done:
                continue
            # Union con degenerados: ... y, y, a, a, [a], b, c  (a en posicion par)
            a, b, c = t
            seq += [seq[-1], a, a]
            if (len(seq) - 1) % 2 == 1:
                seq.append(a)
            seq += [b, c]
        else:
            seq += list(t)
    return seq


# ------------------------------------------------------------------ lectura

def read(data):
    h = struct.unpack_from("<10I", data, 0)
    if h[0] != MAGIC:
        raise ValueError("No es un modelo PES 5 Xbox (magic %08x)" % h[0])

    ngroups = struct.unpack_from("<I", data, h[2])[0]
    groups = [bytes(data[h[2] + 4 + 24 * i:h[2] + 28 + 24 * i]) for i in range(ngroups)]
    nb = struct.unpack_from("<H", data, h[3])[0]
    bones = list(struct.unpack_from("<%dH" % nb, data, h[3] + 2))

    vb = h[4]
    one, eight, nv, stride, moff = struct.unpack_from("<IIHHI", data, vb)
    if (one, eight, stride) != (1, 8, VERTEX_STRIDE):
        raise ValueError("Bloque de vertices inesperado")
    verts = [struct.unpack_from("<8f", data, vb + 16 + i * stride) for i in range(nv)]

    morphs = []
    if moff:
        mb = vb + 8 + moff
        n = struct.unpack_from("<I", data, mb)[0]
        for k in range(n):
            mid, c, io, do = struct.unpack_from("<HHII", data, mb + 4 + 12 * k)
            idx = list(struct.unpack_from("<%dH" % c, data, mb + io))
            d = [struct.unpack_from("<3f", data, mb + do + 12 * j) for j in range(c)]
            morphs.append(Morph(mid, idx, d))

    ni = struct.unpack_from("<H", data, h[5])[0]
    allidx = list(struct.unpack_from("<%dH" % ni, data, h[5] + 2))

    parts, state, o, pos = [], {}, h[6], 0
    while True:
        tag, val = struct.unpack_from("<HH", data, o)
        if tag == DRAW_TAG:
            ic = val
            vs, vc, nt = struct.unpack_from("<HHI", data, o + 4)
            parts.append(Part(dict(state), allidx[pos:pos + ic], vs, vc, nt))
            pos += ic
            o += 12
        elif tag == 0 and val == 0:
            break
        else:
            state[tag] = val
            o += 4
    return XboxModel(groups, bones, verts, morphs, parts)


# ------------------------------------------------------------------ escritura

def _align(buf, n=4):
    while len(buf) % n:
        buf += b"\x00"


def _morph_block(morphs):
    order = sorted(morphs, key=lambda m: (-len(m.indices), m.id))
    n = len(order)
    table = bytearray(struct.pack("<I", n))
    deltas = bytearray()
    idx_blob = bytearray()
    idx_off = {}
    base_d = 4 + 12 * n
    entries = []
    for m in order:
        d_off = base_d + len(deltas)
        for d in m.deltas:
            deltas += struct.pack("<3f", *d)
        entries.append((m, d_off))
    base_i = base_d + len(deltas)
    for m, d_off in entries:
        key = tuple(m.indices)
        if key not in idx_off:
            idx_off[key] = base_i + len(idx_blob)
            idx_blob += struct.pack("<%dH" % len(key), *key)
        table += struct.pack("<HHII", m.id, len(m.indices), idx_off[key], d_off)
    blob = table + deltas + idx_blob
    _align(blob)
    return blob


def write(model):
    out = bytearray(b"\x00" * 0x48)

    # comandos de dibujo
    h6 = len(out)
    prev = {}
    for p in model.parts:
        for tag in STATE_ORDER:
            if tag in p.state and prev.get(tag) != p.state[tag]:
                out += struct.pack("<HH", tag, p.state[tag])
        for tag, val in p.state.items():   # tags desconocidos, por si acaso
            if tag not in STATE_ORDER and prev.get(tag) != val:
                out += struct.pack("<HH", tag, val)
        prev = dict(p.state)
        out += struct.pack("<HHHHI", DRAW_TAG, len(p.indices), p.vstart, p.vcount, p.ntris)
    out += b"\x00" * 4

    h1 = len(out)
    out += struct.pack("<I", 0)
    h2 = len(out)
    out += struct.pack("<I", len(model.groups))
    for g in model.groups:
        out += g
    h3 = len(out)
    out += struct.pack("<H", len(model.bones)) + struct.pack("<%dH" % len(model.bones), *model.bones)
    _align(out)

    h4 = len(out)
    nv = len(model.vertices)
    moff = 8 + nv * VERTEX_STRIDE if model.morphs else 0
    out += struct.pack("<IIHHI", 1, 8, nv, VERTEX_STRIDE, moff)
    for v in model.vertices:
        out += struct.pack("<8f", *v)
    if model.morphs:
        out += _morph_block(model.morphs)

    h5 = len(out)
    allidx = [i for p in model.parts for i in p.indices]
    out += struct.pack("<H", len(allidx)) + struct.pack("<%dH" % len(allidx), *allidx)
    _align(out)

    h7 = len(out)
    out += struct.pack("<I", len(model.morphs))
    h8 = len(out)
    out += struct.pack("<I", 2) + b"\x00" * 24
    h9 = len(out)
    out += struct.pack("<II", 1, 0)
    _align(out, 16)

    struct.pack_into("<10I", out, 0, MAGIC, h1, h2, h3, h4, h5, h6, h7, h8, h9)
    return bytes(out)
