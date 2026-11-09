package com.yotakon.pesport.core;

import java.util.ArrayList;
import java.util.List;

/** Modelo de PES 2010 PS2 (magic 0xFFFF0003, geometria en paquetes VIF). */
final class Ps2Model {
    static final long MAGIC = 0xFFFF0003L;
    private static final int CHUNK_HEADER = 0xB0;

    static final class Chunk {
        int[][] positions;   // int16 x3
        int[][] normals;     // int16 x3
        int[][] uvs;         // int16 x2 (escala 4096)
        int[] stripIndex;    // indice local
        boolean[] stripAdc;  // true = no dibujar el triangulo que termina aqui
        int kind;            // 2 = rigido, 3 = con piel
        int bone;            // indice en la lista de huesos del modelo
        double[] params;     // 2 floats en +0x48
        int[] morphRange;    // {inicioBase, verticeLocalInicial, cantidad} o null

        /** Triangulos orientados; la paridad depende de la posicion global en la tira. */
        List<int[]> triangles() {
            List<int[]> tris = new ArrayList<>();
            for (int i = 2; i < stripIndex.length; i++) {
                if (stripAdc[i]) {
                    continue;
                }
                int a = stripIndex[i - 2], b = stripIndex[i - 1], c = stripIndex[i];
                if (a == b || b == c || a == c) {
                    continue;
                }
                tris.add(i % 2 == 0 ? new int[]{a, b, c} : new int[]{b, a, c});
            }
            return tris;
        }
    }

    List<Integer> bones = new ArrayList<>();
    List<Chunk> chunks = new ArrayList<>();
    int morphBaseCount;
    List<int[][]> morphs = new ArrayList<>();   // cada morph: entradas {dx, dy, dz, indiceBase}

    private static final class Unpack {
        int addr;
        int[][] values;
    }

    private static List<Unpack> unpacks(byte[] d, int start, int end) {
        List<Unpack> out = new ArrayList<>();
        int i = start;
        while (i + 4 <= end) {
            int imm = Bytes.u16(d, i);
            int num = Bytes.u8(d, i + 2);
            int cmd = Bytes.u8(d, i + 3);
            i += 4;
            int c = cmd & 0x7F;
            if (c >= 0x60) {
                int vn = (c >> 2) & 3, vl = c & 3;
                if (vl == 3) {
                    throw new ConversionException("Formato VIF no soportado (V4-5)");
                }
                int comps = vn + 1;
                int size = new int[]{4, 2, 1}[vl];
                int n = num != 0 ? num : 256;
                int nbytes = (n * comps * size + 3) & ~3;
                boolean unsigned = (imm & 0x4000) != 0;
                int[][] vals = new int[n][comps];
                for (int k = 0; k < n; k++) {
                    for (int j = 0; j < comps; j++) {
                        int p = i + (k * comps + j) * size;
                        int v;
                        if (size == 4) {
                            v = Bytes.s32(d, p);
                        } else if (size == 2) {
                            v = unsigned ? Bytes.u16(d, p) : Bytes.s16(d, p);
                        } else {
                            v = unsigned ? Bytes.u8(d, p) : Bytes.s8(d, p);
                        }
                        vals[k][j] = v;
                    }
                }
                Unpack u = new Unpack();
                u.addr = imm & 0x3FF;
                u.values = vals;
                out.add(u);
                i += nbytes;
            } else if (c == 0x20) {
                i += 4;
            } else if (c == 0x30 || c == 0x31) {
                i += 16;
            } else if (c == 0x4A) {
                i += (num != 0 ? num : 256) * 8;
            } else if (c == 0x50 || c == 0x51) {
                i += (imm != 0 ? imm : 65536) * 16;
            }
        }
        return out;
    }

    static Ps2Model read(byte[] d) {
        if (Bytes.u32(d, 0) != MAGIC) {
            throw new ConversionException("No es un modelo de PES 2010 PS2");
        }
        Ps2Model m = new Ps2Model();
        int nchunks = Bytes.u32i(d, 0x20), off = Bytes.u32i(d, 0x24);
        int nbones = Bytes.u32i(d, 0x38), boff = Bytes.u32i(d, 0x3C);
        for (int i = 0; i < nbones; i++) {
            m.bones.add(Bytes.u32i(d, boff + 4 * i));
        }
        m.morphBaseCount = Bytes.u32i(d, 0x44);
        int nmorph = Bytes.u32i(d, 0x4C), moff = Bytes.u32i(d, 0x50);
        for (int k = 0; k < nmorph; k++) {
            int c = Bytes.u32i(d, moff + 8 * k), o = Bytes.u32i(d, moff + 8 * k + 4);
            int[][] e = new int[c][4];
            for (int i = 0; i < c; i++) {
                for (int j = 0; j < 4; j++) {
                    e[i][j] = Bytes.s16(d, o + 8 * i + 2 * j);
                }
            }
            m.morphs.add(e);
        }

        int o = off;
        for (int k = 0; k < nchunks; k++) {
            int size = Bytes.u32i(d, o);
            int nidx = Bytes.u32i(d, o + 16);
            if (size <= 0) {
                throw new ConversionException("Chunk de tamano invalido en 0x" + Integer.toHexString(o));
            }
            List<Unpack> geo = new ArrayList<>();
            for (Unpack u : unpacks(d, o + CHUNK_HEADER, o + size)) {
                if (u.addr >= 4) {
                    geo.add(u);
                }
            }
            if (geo.size() < 4) {
                throw new ConversionException("Chunk VIF incompleto en 0x" + Integer.toHexString(o));
            }
            Chunk ch = new Chunk();
            ch.positions = geo.get(0).values;
            ch.normals = geo.get(1).values;
            ch.uvs = geo.get(2).values;
            List<Integer> raw = new ArrayList<>();
            for (int[] t : geo.get(3).values) {
                for (int x : t) {
                    raw.add(x);
                }
            }
            if (nidx > raw.size()) {
                throw new ConversionException("Tira de triangulos incompleta");
            }
            ch.stripIndex = new int[nidx];
            ch.stripAdc = new boolean[nidx];
            for (int i = 0; i < nidx; i++) {
                int x = raw.get(i);
                ch.stripIndex[i] = Bytes.floorDiv((x & 0x7FFF) - 4, 4);
                ch.stripAdc[i] = (x & 0x8000) != 0;
            }
            if (Bytes.u32(d, o + 20) != 0) {
                int bs = Bytes.u16(d, o + 0x90), addr = Bytes.u16(d, o + 0x92), cnt = Bytes.u16(d, o + 0x94);
                ch.morphRange = new int[]{bs, Bytes.floorDiv(addr - 4, 4), cnt};
            }
            ch.params = new double[]{Bytes.f32(d, o + 0x48), Bytes.f32(d, o + 0x4C)};
            ch.kind = Bytes.u32i(d, o + 64);
            ch.bone = Bytes.u32i(d, o + 52);
            m.chunks.add(ch);
            o += size;
        }
        return m;
    }
}
