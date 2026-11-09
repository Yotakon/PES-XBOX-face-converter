package com.yotakon.pesport.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Escritura del modelo de PES 5 Xbox (magic 0x20040520). Ver docs/FORMATOS.md. */
final class XboxModel {
    static final long MAGIC = 0x20040520L;
    static final int VERTEX_STRIDE = 32;
    static final int[] STATE_ORDER = {3, 1, 2, 0x0D, 4, 0x0A};
    static final int DRAW_TAG = 0x0507;

    static final class Morph {
        final int id;
        final int[] indices;
        final double[][] deltas;

        Morph(int id, int[] indices, double[][] deltas) {
            this.id = id;
            this.indices = indices;
            this.deltas = deltas;
        }
    }

    static final class Part {
        final LinkedHashMap<Integer, Integer> state;
        final int[] indices;
        final int vstart, vcount, ntris;

        Part(LinkedHashMap<Integer, Integer> state, int[] indices, int vstart, int vcount, int ntris) {
            this.state = state;
            this.indices = indices;
            this.vstart = vstart;
            this.vcount = vcount;
            this.ntris = ntris;
        }
    }

    List<byte[]> groups = new ArrayList<>();
    List<Integer> bones = new ArrayList<>();
    List<double[]> vertices = new ArrayList<>();   // x, y, z, nx, ny, nz, u, v
    List<Morph> morphs = new ArrayList<>();
    List<Part> parts = new ArrayList<>();

    // ------------------------------------------------------------ tiras de triangulos

    static List<int[]> stripTriangles(int[] seq) {
        List<int[]> tris = new ArrayList<>();
        for (int i = 0; i + 2 < seq.length; i++) {
            int a = seq[i], b = seq[i + 1], c = seq[i + 2];
            if (a == b || b == c || a == c) {
                continue;
            }
            tris.add(i % 2 == 0 ? new int[]{a, b, c} : new int[]{b, a, c});
        }
        return tris;
    }

    /** Rotacion canonica de un triangulo orientado. */
    static int[] canonical(int[] t) {
        int[][] r = {{t[0], t[1], t[2]}, {t[1], t[2], t[0]}, {t[2], t[0], t[1]}};
        int[] best = r[0];
        for (int[] x : r) {
            if (compare(x, best) < 0) {
                best = x;
            }
        }
        return best;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static boolean sameTri(int x, int y, int z, int[] u) {
        int a = u[0], b = u[1], c = u[2];
        return (x == a && y == b && z == c) || (x == b && y == c && z == a) || (x == c && y == a && z == b);
    }

    /** Convierte triangulos orientados en una tira con degenerados. */
    static int[] buildStrip(List<int[]> tris) {
        List<Integer> seq = new ArrayList<>();
        for (int[] t : tris) {
            if (seq.size() >= 2) {
                int x = seq.get(seq.size() - 2), y = seq.get(seq.size() - 1);
                int par = (seq.size() - 2) % 2;
                boolean done = false;
                for (int c : t) {
                    boolean same = par == 0 ? sameTri(x, y, c, t) : sameTri(y, x, c, t);
                    if (same && x != y && y != c && x != c) {
                        seq.add(c);
                        done = true;
                        break;
                    }
                }
                if (done) {
                    continue;
                }
                int last = seq.get(seq.size() - 1);
                seq.add(last);
                seq.add(t[0]);
                seq.add(t[0]);
                if ((seq.size() - 1) % 2 == 1) {
                    seq.add(t[0]);
                }
                seq.add(t[1]);
                seq.add(t[2]);
            } else {
                for (int v : t) {
                    seq.add(v);
                }
            }
        }
        int[] out = new int[seq.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = seq.get(i);
        }
        return out;
    }

    // ------------------------------------------------------------ escritura

    private byte[] morphBlock() {
        List<Morph> order = new ArrayList<>(morphs);
        Collections.sort(order, new Comparator<Morph>() {
            @Override
            public int compare(Morph a, Morph b) {
                return a.indices.length != b.indices.length
                        ? Integer.compare(b.indices.length, a.indices.length) : Integer.compare(a.id, b.id);
            }
        });
        int n = order.size();
        ByteBuf table = new ByteBuf();
        table.u32(n);
        ByteBuf deltas = new ByteBuf();
        int baseD = 4 + 12 * n;
        int[] dOff = new int[n];
        for (int k = 0; k < n; k++) {
            dOff[k] = baseD + deltas.size();
            for (double[] d : order.get(k).deltas) {
                deltas.f32(d[0]);
                deltas.f32(d[1]);
                deltas.f32(d[2]);
            }
        }
        int baseI = baseD + deltas.size();
        ByteBuf idxBlob = new ByteBuf();
        Map<String, Integer> idxOff = new HashMap<>();
        for (int k = 0; k < n; k++) {
            Morph m = order.get(k);
            String key = Arrays.toString(m.indices);
            Integer io = idxOff.get(key);
            if (io == null) {
                io = baseI + idxBlob.size();
                idxOff.put(key, io);
                for (int i : m.indices) {
                    idxBlob.u16(i);
                }
            }
            table.u16(m.id);
            table.u16(m.indices.length);
            table.u32(io);
            table.u32(dOff[k]);
        }
        ByteBuf blob = new ByteBuf();
        blob.put(table.toArray());
        blob.put(deltas.toArray());
        blob.put(idxBlob.toArray());
        blob.align(4);
        return blob.toArray();
    }

    byte[] write() {
        ByteBuf out = new ByteBuf();
        out.zeros(0x48);

        int h6 = out.size();
        Map<Integer, Integer> prev = new HashMap<>();
        for (Part p : parts) {
            for (int tag : STATE_ORDER) {
                Integer v = p.state.get(tag);
                if (v != null && !v.equals(prev.get(tag))) {
                    out.u16(tag);
                    out.u16(v);
                }
            }
            for (Map.Entry<Integer, Integer> e : p.state.entrySet()) {
                boolean known = false;
                for (int t : STATE_ORDER) {
                    known |= t == e.getKey();
                }
                if (!known && !e.getValue().equals(prev.get(e.getKey()))) {
                    out.u16(e.getKey());
                    out.u16(e.getValue());
                }
            }
            prev = new HashMap<>(p.state);
            out.u16(DRAW_TAG);
            out.u16(p.indices.length);
            out.u16(p.vstart);
            out.u16(p.vcount);
            out.u32(p.ntris);
        }
        out.zeros(4);

        int h1 = out.size();
        out.u32(0);
        int h2 = out.size();
        out.u32(groups.size());
        for (byte[] g : groups) {
            out.put(g);
        }
        int h3 = out.size();
        out.u16(bones.size());
        for (int b : bones) {
            out.u16(b);
        }
        out.align(4);

        int h4 = out.size();
        int nv = vertices.size();
        long moff = morphs.isEmpty() ? 0 : 8 + (long) nv * VERTEX_STRIDE;
        out.u32(1);
        out.u32(8);
        out.u16(nv);
        out.u16(VERTEX_STRIDE);
        out.u32(moff);
        for (double[] v : vertices) {
            for (double x : v) {
                out.f32(x);
            }
        }
        if (!morphs.isEmpty()) {
            out.put(morphBlock());
        }

        int h5 = out.size();
        int total = 0;
        for (Part p : parts) {
            total += p.indices.length;
        }
        if (total > 0xFFFF) {
            throw new ConversionException("Demasiados indices (" + total + ")");
        }
        out.u16(total);
        for (Part p : parts) {
            for (int i : p.indices) {
                out.u16(i);
            }
        }
        out.align(4);

        int h7 = out.size();
        out.u32(morphs.size());
        int h8 = out.size();
        out.u32(2);
        out.zeros(24);
        int h9 = out.size();
        out.u32(1);
        out.u32(0);
        out.align(16);

        long[] h = {MAGIC, h1, h2, h3, h4, h5, h6, h7, h8, h9};
        for (int i = 0; i < h.length; i++) {
            out.setU32(4 * i, h[i]);
        }
        return out.toArray();
    }
}
