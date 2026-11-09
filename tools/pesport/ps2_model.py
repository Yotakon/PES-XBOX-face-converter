"""Lectura del modelo de PES 2010 PS2 (magic 0xFFFF0003, geometria en paquetes VIF)."""
import struct
from dataclasses import dataclass

MAGIC = 0xFFFF0003
CHUNK_HEADER = 0xB0

# (vn, vl) -> (componentes, bytes por componente)
_UNPACK = {(vn, vl): (vn + 1, (4, 2, 1)[vl]) for vn in range(4) for vl in range(3)}


@dataclass
class Chunk:
    positions: list        # int16 x3
    normals: list          # int16 x3
    uvs: list              # int16 x2 (escala 4096)
    strip: list            # (indice_local, adc)
    kind: int              # 2 = rigido, 3 = con piel
    bone: int              # u32[13]: indice en la lista de huesos del modelo
    params: tuple          # floats de cabecera +0x48 (2 valores)
    morph_range: tuple     # (inicio_base, vertice_local_inicial, cantidad) o None

    def triangles(self):
        """Triangulos orientados. La paridad depende de la posicion global en la tira."""
        tris = []
        s = self.strip
        for i in range(2, len(s)):
            if s[i][1]:
                continue
            a, b, c = s[i - 2][0], s[i - 1][0], s[i][0]
            if a == b or b == c or a == c:
                continue
            tris.append((a, b, c) if i % 2 == 0 else (b, a, c))
        return tris


@dataclass
class PS2Model:
    bones: list
    chunks: list
    morph_base_count: int
    morphs: list           # lista de listas (dx, dy, dz, indice_base)


def _unpacks(data, start, end):
    out = []
    i = start
    while i + 4 <= end:
        imm, num, cmd = struct.unpack_from("<HBB", data, i)
        i += 4
        c = cmd & 0x7F
        if c >= 0x60:
            comps, size = _UNPACK[((c >> 2) & 3, c & 3)]
            n = num or 256
            nbytes = (n * comps * size + 3) & ~3
            fmt = "<%d%s" % (comps, {4: "i", 2: "h", 1: "b"}[size])
            if imm & 0x4000:
                fmt = fmt.upper()
            vals = [struct.unpack_from(fmt, data, i + k * comps * size) for k in range(n)]
            out.append((imm & 0x3FF, comps, size, vals))
            i += nbytes
        elif c in (0x20,):
            i += 4
        elif c in (0x30, 0x31):
            i += 16
        elif c == 0x4A:
            i += (num or 256) * 8
        elif c in (0x50, 0x51):
            i += (imm or 65536) * 16
    return out


def read(data):
    if struct.unpack_from("<I", data, 0)[0] != MAGIC:
        raise ValueError("No es un modelo PES PS2")
    nchunks, off = struct.unpack_from("<2I", data, 0x20)
    nbones, boff = struct.unpack_from("<2I", data, 0x38)
    bones = list(struct.unpack_from("<%dI" % nbones, data, boff))
    nbase, _, nmorph, moff = struct.unpack_from("<4I", data, 0x44)

    morphs = []
    for k in range(nmorph):
        c, o = struct.unpack_from("<2I", data, moff + 8 * k)
        morphs.append([struct.unpack_from("<4h", data, o + 8 * i) for i in range(c)])

    chunks = []
    o = off
    for _ in range(nchunks):
        w = struct.unpack_from("<44I", data, o)
        size, nidx = w[0], w[4]
        geo = [u for u in _unpacks(data, o + CHUNK_HEADER, o + size) if u[0] >= 4]
        if len(geo) < 4:
            raise ValueError("Chunk VIF incompleto en 0x%x" % o)
        pos, nrm, uv, strip = geo[0][3], geo[1][3], geo[2][3], geo[3][3]
        raw = [x for t in strip for x in t][:nidx]
        strip = [(((x & 0x7FFF) - 4) // 4, bool(x & 0x8000)) for x in raw]
        mr = None
        if w[5]:
            bs, addr, cnt = struct.unpack_from("<3H", data, o + 0x90)
            mr = (bs, (addr - 4) // 4, cnt)
        params = struct.unpack_from("<2f", data, o + 0x48)
        chunks.append(Chunk(pos, nrm, uv, strip, w[16], w[13], params, mr))
        o += size
    return PS2Model(bones, chunks, nbase, morphs)
