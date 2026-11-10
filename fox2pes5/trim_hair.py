"""Recorta el pelo convertido: quita los triangulos donde la textura es transparente
(melena del molde por debajo de la nuca y patillas largas) y rehace las tiras."""
import sys
sys.path.insert(0, '/projects/sandbox/tevez/tools'); sys.path.insert(0, '/projects/sandbox/tevez/work')
import pes5, align
from pesport import container, xbox_model as xm

SRC = sys.argv[1] if len(sys.argv) > 1 else 'out_hair.bin'
DST = sys.argv[2] if len(sys.argv) > 2 else 'out_hair_trim.bin'
MIN_ALPHA = 0.35        # alfa media minima del triangulo para conservarlo
NAPE_Y = 1.605          # m (espacio Fox): por debajo no hay pelo en Tevez

secs = pes5.load(SRC)
hm = xm.read(secs[0]); tex = pes5.tex_image(secs[1])
V = hm.vertices


def alpha_of(t):
    pts = [V[i] for i in t]
    samples = [p[6:8] for p in pts]
    for a in range(3):
        b = (a + 1) % 3
        samples.append(((pts[a][6] + pts[b][6]) / 2, (pts[a][7] + pts[b][7]) / 2))
    samples.append((sum(p[6] for p in pts) / 3, sum(p[7] for p in pts) / 3))
    return sum(tex.sample(u, v)[3] for u, v in samples) / len(samples) / 255.0


removed = kept = 0
for part in hm.parts:
    tris = xm.strip_triangles(part.indices)
    good = []
    for t in tris:
        ys = [align.W(V[i][:3])[1] for i in t]
        if alpha_of(t) >= MIN_ALPHA and sum(ys) / 3 >= NAPE_Y and min(ys) >= NAPE_Y - 0.012:
            good.append(t)
        else:
            removed += 1
    kept += len(good)
    if not good:   # una parte vacia: se deja un triangulo degenerado minimo
        good_strip = [part.vstart] * 3
        part.indices, part.ntris = good_strip, 0
        continue
    strip = xm.build_strip(good)
    assert [xm.canonical(t) for t in xm.strip_triangles(strip)] == [xm.canonical(t) for t in good]
    part.indices = strip
    part.ntris = len(xm.strip_triangles(strip))
print('triangulos conservados %d, quitados %d' % (kept, removed))
raw = container.join([xm.write(hm), secs[1], secs[2]])
open(DST, 'wb').write(container.pack(raw))
print('escrito', DST)
