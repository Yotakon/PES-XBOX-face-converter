"""Utilidades de imagen en Python puro: PNG, DXT1/DXT5, FTEX y DDS."""
import struct, zlib

class Image:
    def __init__(self, w, h, px=None):
        self.w, self.h = w, h
        self.px = px if px is not None else [(0, 0, 0, 255)] * (w * h)
    def get(self, x, y):
        return self.px[y * self.w + x]
    def sample(self, u, v):
        """Bilineal, u,v en [0,1], v=0 arriba. Con repeticion."""
        x = (u % 1.0) * self.w - 0.5; y = (v % 1.0) * self.h - 0.5
        x0 = int(x // 1); y0 = int(y // 1); fx = x - x0; fy = y - y0
        out = [0.0] * 4
        for dx, dy, wgt in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            p = self.px[((y0 + dy) % self.h) * self.w + (x0 + dx) % self.w]
            for c in range(4): out[c] += p[c] * wgt
        return tuple(out)
    def save_png(self, fn):
        rows = []
        for y in range(self.h):
            r = bytearray(b'\x00')
            for x in range(self.w):
                p = self.px[y * self.w + x]
                r += bytes(max(0, min(255, int(round(c)))) for c in p[:4])
            rows.append(bytes(r))
        def chunk(t, d):
            return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
        data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b'IDAT', zlib.compress(b''.join(rows), 6)) + chunk(b'IEND', b''))
        open(fn, 'wb').write(data)

def _c565(c):
    r = (c >> 11) & 31; g = (c >> 5) & 63; b = c & 31
    return ((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2))

def _color_block(d, o, dxt1):
    c0, c1, bits = struct.unpack_from('<HHI', d, o)
    a, b = _c565(c0), _c565(c1)
    if c0 > c1 or not dxt1:
        pal = [a + (255,), b + (255,), tuple((2 * a[i] + b[i]) // 3 for i in range(3)) + (255,), tuple((a[i] + 2 * b[i]) // 3 for i in range(3)) + (255,)]
    else:
        pal = [a + (255,), b + (255,), tuple((a[i] + b[i]) // 2 for i in range(3)) + (255,), (0, 0, 0, 0)]
    return [pal[(bits >> (2 * i)) & 3] for i in range(16)]

def _alpha_block(d, o):
    a0, a1 = d[o], d[o + 1]
    bits = int.from_bytes(d[o + 2:o + 8], 'little')
    if a0 > a1:
        pal = [a0, a1] + [((6 - i) * a0 + (i + 1) * a1) // 7 for i in range(6)]
    else:
        pal = [a0, a1] + [((4 - i) * a0 + (i + 1) * a1) // 5 for i in range(4)] + [0, 255]
    return [pal[(bits >> (3 * i)) & 7] for i in range(16)]

def decode_dxt(d, w, h, fmt, off=0):
    px = [None] * (w * h)
    bs = 8 if fmt == 'DXT1' else 16
    o = off
    for by in range(0, h, 4):
        for bx in range(0, w, 4):
            if fmt == 'DXT1':
                cols = _color_block(d, o, True)
            else:
                al = _alpha_block(d, o) if fmt == 'DXT5' else [((int.from_bytes(d[o:o + 8], 'little') >> (4 * i)) & 15) * 17 for i in range(16)]
                cols = _color_block(d, o + 8, False)
                cols = [c[:3] + (al[i],) for i, c in enumerate(cols)]
            o += bs
            for i in range(16):
                x = bx + (i & 3); y = by + (i >> 2)
                if x < w and y < h: px[y * w + x] = cols[i]
    return Image(w, h, px)

FTEX_FMT = {0: 'RGBA', 1: 'L8', 2: 'DXT1', 3: 'DXT3', 4: 'DXT5'}

def read_ftex(d):
    assert d[:4] == b'FTEX'
    pf, w, h, depth = struct.unpack_from('<HHHH', d, 8)
    mips = d[0x10]
    fsc = d[0x20]
    # primera entrada de mip
    off, usize, csize, idx, _, nch = struct.unpack_from('<IIIBBH', d, 0x40)
    chunks = []
    p = off
    tbl = [struct.unpack_from('<HHI', d, p + 8 * i) for i in range(nch)]
    raw = bytearray()
    for cs, ds, co in tbl:
        unc = co & 0x80000000
        co &= 0x7fffffff
        blob = d[off + co: off + co + cs]
        raw += blob if unc or cs == ds else zlib.decompress(blob)
    return pf, w, h, bytes(raw)

def load_ftex(fn):
    d = open(fn, 'rb').read()
    pf, w, h, raw = read_ftex(d)
    fmt = FTEX_FMT.get(pf)
    if fmt in ('DXT1', 'DXT3', 'DXT5'):
        return decode_dxt(raw, w, h, fmt)
    if fmt == 'RGBA':
        return Image(w, h, [tuple(raw[i * 4:i * 4 + 4][k] for k in (2, 1, 0, 3)) for i in range(w * h)])
    raise ValueError('formato FTEX %d' % pf)

def load_dds(fn):
    d = open(fn, 'rb').read()
    h, w = struct.unpack_from('<II', d, 12)
    fourcc = d[84:88].decode()
    return decode_dxt(d, w, h, fourcc, 128)
