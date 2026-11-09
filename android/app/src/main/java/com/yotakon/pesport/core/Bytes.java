package com.yotakon.pesport.core;

/** Lectura little-endian con comprobacion de limites. */
final class Bytes {
    private Bytes() {
    }

    private static void check(byte[] d, int off, int len) {
        if (off < 0 || off + len > d.length) {
            throw new ConversionException("Archivo truncado o corrupto (lectura en 0x"
                    + Integer.toHexString(off) + ")");
        }
    }

    static int u8(byte[] d, int off) {
        check(d, off, 1);
        return d[off] & 0xFF;
    }

    static int s8(byte[] d, int off) {
        check(d, off, 1);
        return d[off];
    }

    static int u16(byte[] d, int off) {
        check(d, off, 2);
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    static int s16(byte[] d, int off) {
        return (short) u16(d, off);
    }

    static long u32(byte[] d, int off) {
        check(d, off, 4);
        return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8) | ((d[off + 2] & 0xFFL) << 16)
                | ((d[off + 3] & 0xFFL) << 24);
    }

    static int s32(byte[] d, int off) {
        return (int) u32(d, off);
    }

    /** u32 usado como offset o contador: debe caber en un int positivo. */
    static int u32i(byte[] d, int off) {
        long v = u32(d, off);
        if (v > Integer.MAX_VALUE) {
            throw new ConversionException("Valor fuera de rango en 0x" + Integer.toHexString(off));
        }
        return (int) v;
    }

    static double f32(byte[] d, int off) {
        return Float.intBitsToFloat(s32(d, off));
    }

    static byte[] slice(byte[] d, int from, int to) {
        check(d, from, to - from);
        byte[] out = new byte[to - from];
        System.arraycopy(d, from, out, 0, out.length);
        return out;
    }

    static boolean startsWith(byte[] d, byte[] magic) {
        if (d.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (d[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    static int floorDiv(int a, int b) {
        int q = a / b;
        if ((a % b != 0) && ((a < 0) != (b < 0))) {
            q--;
        }
        return q;
    }
}
