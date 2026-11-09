"""Geometria en Python puro: vectores, RBF, raycast con rejilla."""
import math


def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, s): return (a[0] * s, a[1] * s, a[2] * s)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def length(a): return math.sqrt(dot(a, a))


def norm(a):
    l = length(a)
    return mul(a, 1 / l) if l > 1e-12 else (0.0, 0.0, 0.0)


def solve(A, b):
    """Gauss con pivoteo parcial (A cuadrada, lista de listas)."""
    n = len(A)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = max(range(c, n), key=lambda r: abs(M[r][c]))
        M[c], M[p] = M[p], M[c]
        if abs(M[c][c]) < 1e-14:
            continue
        for r in range(n):
            if r != c:
                f = M[r][c] / M[c][c]
                if f:
                    for k in range(c, n + 1):
                        M[r][k] -= f * M[c][k]
    return [M[i][n] / M[i][i] if abs(M[i][i]) > 1e-14 else 0.0 for i in range(n)]


def linfit(xs, ys):
    """y = a*x + b por minimos cuadrados."""
    n = len(xs); mx = sum(xs) / n; my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs); sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    a = sxy / sxx
    return a, my - a * mx


class RBF:
    """Corrector residual gaussiano: f(p) = sum w_i exp(-|p-c_i|^2 / s^2)."""

    def __init__(self, centers, values, sigma, reg=1e-6):
        self.c = centers; self.s2 = sigma * sigma
        n = len(centers)
        K = [[math.exp(-dot(sub(a, b), sub(a, b)) / self.s2) + (reg if i == j else 0)
              for j, b in enumerate(centers)] for i, a in enumerate(centers)]
        self.w = [solve(K, [v[k] for v in values]) for k in range(3)]

    def __call__(self, p):
        out = [0.0, 0.0, 0.0]
        for i, c in enumerate(self.c):
            d = sub(p, c)
            g = math.exp(-dot(d, d) / self.s2)
            for k in range(3):
                out[k] += self.w[k][i] * g
        return tuple(out)


class Mesh:
    """Triangulos con rejilla uniforme para raycast y punto mas cercano."""

    def __init__(self, tris, cell):
        # tris: lista de (p0,p1,p2, payload)
        self.tris = tris
        self.cell = cell
        self.grid = {}
        for ti, t in enumerate(tris):
            ps = t[:3]
            lo = [min(p[k] for p in ps) for k in range(3)]
            hi = [max(p[k] for p in ps) for k in range(3)]
            for i in range(int(math.floor(lo[0] / cell)), int(math.floor(hi[0] / cell)) + 1):
                for j in range(int(math.floor(lo[1] / cell)), int(math.floor(hi[1] / cell)) + 1):
                    for k in range(int(math.floor(lo[2] / cell)), int(math.floor(hi[2] / cell)) + 1):
                        self.grid.setdefault((i, j, k), []).append(ti)

    def _cells_on_segment(self, a, b):
        n = int(length(sub(b, a)) / (self.cell * 0.5)) + 2
        seen = set()
        for s in range(n + 1):
            p = add(a, mul(sub(b, a), s / n))
            key = tuple(int(math.floor(p[k] / self.cell)) for k in range(3))
            for di in (-1, 0, 1):
                for dj in (-1, 0, 1):
                    for dk in (-1, 0, 1):
                        kk = (key[0] + di, key[1] + dj, key[2] + dk)
                        if kk not in seen:
                            seen.add(kk)
                            yield kk

    def ray_all(self, o, d, tmin, tmax):
        """Todas las intersecciones (t, ti, b1, b2) del segmento o + t*d, t en [tmin, tmax]."""
        cand = set()
        for kk in self._cells_on_segment(add(o, mul(d, tmin)), add(o, mul(d, tmax))):
            cand.update(self.grid.get(kk, ()))
        hits = []
        for ti in cand:
            p0, p1, p2 = self.tris[ti][:3]
            e1 = sub(p1, p0); e2 = sub(p2, p0)
            h = cross(d, e2); a = dot(e1, h)
            if abs(a) < 1e-15:
                continue
            f = 1 / a; s = sub(o, p0)
            u = f * dot(s, h)
            if u < -1e-6 or u > 1 + 1e-6:
                continue
            q = cross(s, e1); v = f * dot(d, q)
            if v < -1e-6 or u + v > 1 + 1e-6:
                continue
            t = f * dot(e2, q)
            if tmin <= t <= tmax:
                hits.append((t, ti, u, v))
        hits.sort()
        return hits

    def ray_nearest(self, o, d, tmax):
        hits = self.ray_all(o, d, -tmax, tmax)
        if not hits:
            return None
        return min(hits, key=lambda h: abs(h[0]))
