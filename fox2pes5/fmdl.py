"""Lector minimo de FMDL (Fox Engine, PES 2016-2021 PC)."""
import struct

def half(h):
    return struct.unpack('<e', struct.pack('<H', h))[0]

class Fmdl:
    def __init__(self, d):
        self.d = d
        magic, ver, sio, f0, f1, n0, n1, o0, l0, o1, l1 = struct.unpack_from('<4sfQQQIIIIII', d, 0)
        assert magic == b'FMDL'
        self.o0, self.o1 = o0, o1
        p = sio
        self.b0 = {}
        for i in range(n0):
            bid, cnt, off = struct.unpack_from('<HHI', d, p); p += 8
            self.b0[bid] = (cnt, o0 + off)
        self.b1 = {}
        for i in range(n1):
            bid, off, ln = struct.unpack_from('<III', d, p); p += 12
            self.b1[bid] = (o1 + off, ln)
        self._parse()

    def recs(self, bid, size):
        if bid not in self.b0: return []
        cnt, off = self.b0[bid]
        return [self.d[off + i * size: off + (i + 1) * size] for i in range(cnt)]

    def _parse(self):
        d = self.d
        # strings
        self.strings = []
        for r in self.recs(12, 8):
            blk, ln, off = struct.unpack('<HHI', r)
            base = self.b1[blk][0]
            self.strings.append(d[base + off: base + off + ln].decode('latin1'))
        S = lambda i: self.strings[i] if i < len(self.strings) else '#%d' % i
        self.bones = []
        for r in self.recs(0, 0x30):
            name, parent, bbox, unk = struct.unpack_from('<HhHH', r, 0)
            lp = struct.unpack_from('<4f', r, 0x10); wp = struct.unpack_from('<4f', r, 0x20)
            self.bones.append(dict(name=S(name), parent=parent, local=lp, world=wp))
        self.mesh_groups = []
        for r in self.recs(1, 8):
            name, inv, parent, u = struct.unpack('<HHhh', r)
            self.mesh_groups.append(dict(name=S(name), parent=parent))
        self.mge = []
        for r in self.recs(2, 0x20):
            u0, gi, mc, fm = struct.unpack_from('<IHHH', r, 0)
            self.mge.append(dict(group=gi, count=mc, first=fm))
        self.materials = [(S(a), S(b)) for a, b in (struct.unpack('<HH', r) for r in self.recs(8, 4))]
        self.textures = [(S(a), S(b)) for a, b in (struct.unpack('<HH', r) for r in self.recs(6, 4))]
        self.params = [struct.unpack('<HH', r) for r in self.recs(7, 4)]
        self.matinst = []
        for r in self.recs(4, 0x10):
            name, pad, mat, tc, pc, ft, fp = struct.unpack_from('<HHHBBHH', r, 0)
            texs = []
            for k in range(tc):
                pn, ref = self.params[ft + k]
                texs.append((S(pn),) + self.textures[ref])
            self.matinst.append(dict(name=S(name), mat=self.materials[mat] if mat < len(self.materials) else mat, textures=texs))
        self.bonegroups = []
        for r in self.recs(5, 0x44):
            u, bc = struct.unpack_from('<HH', r, 0)
            self.bonegroups.append(list(struct.unpack_from('<%dH' % bc, r, 4)))
        self.mfa = [struct.unpack('<BBBBHH', r) for r in self.recs(9, 8)]
        self.mformats = [struct.unpack('<BBBBI', r) for r in self.recs(10, 8)]
        self.vformats = [struct.unpack('<BBH', r) for r in self.recs(11, 4)]
        self.bufoffs = [struct.unpack_from('<III', r, 0) for r in self.recs(14, 0x10)]
        self.meshes = []
        for i, r in enumerate(self.recs(3, 0x30)):
            alpha, shadow, u, mi, bg, idx, nv = struct.unpack_from('<BBHHHHH', r, 0)
            ffv, nfv = struct.unpack_from('<II', r, 0x10)
            self.meshes.append(dict(alpha=alpha, shadow=shadow, matinst=mi, bonegroup=bg, nverts=nv, ffv=ffv, nfv=nfv))
        # mesh -> group name
        for e in self.mge:
            for k in range(e['first'], e['first'] + e['count']):
                self.meshes[k]['group'] = self.mesh_groups[e['group']]['name']
        self._read_vertices()

    def _read_vertices(self):
        d = self.d
        bufbase = self.b1[2][0]
        bstart = [bufbase + off for isfinal, ln, off in self.bufoffs]
        for mi, m in enumerate(self.meshes):
            nmf, nvf, _, _, fmf, fvf = self.mfa[mi]
            attrs = {}
            vfi = fvf
            for k in range(nmf):
                boi, cnt, stride, typ, off = self.mformats[fmf + k]
                base = bstart[boi] + off
                for j in range(cnt):
                    usage, dt, voff = self.vformats[vfi]; vfi += 1
                    vals = []
                    for v in range(m['nverts']):
                        p = base + v * stride + voff
                        if dt == 1: vals.append(struct.unpack_from('<3f', d, p))
                        elif dt == 6: vals.append(tuple(half(x) for x in struct.unpack_from('<4H', d, p)))
                        elif dt == 7: vals.append(tuple(half(x) for x in struct.unpack_from('<2H', d, p)))
                        elif dt in (8, 9): vals.append(struct.unpack_from('<4B', d, p))
                        else: vals.append(('dt%d' % dt,))
                    attrs[usage] = (dt, vals)
            m['attrs'] = attrs
            fb = bstart[2] + m['ffv'] * 2
            idx = struct.unpack_from('<%dH' % m['nfv'], d, fb)
            m['tris'] = [idx[i:i + 3] for i in range(0, len(idx), 3)]
