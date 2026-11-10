package com.yotakon.pesport.core;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Reconoce si un .bin es un FACE o un HAIR de PES 2010 PS2 mirando solo su principio. */
public final class FaceHair {
    public enum Kind { FACE, HAIR }

    /**
     * Bytes comprimidos que bastan para clasificar: la tabla de secciones y el principio de la
     * ultima seccion caben de sobra (un FACE descomprimido ocupa unos 36 KB).
     */
    public static final int HEAD_BYTES = 128 * 1024;

    private FaceHair() {
    }

    /**
     * FACE = 3 secciones: modelo, modelo, textura. HAIR = modelo, textura, textura.
     * Devuelve null si no es ninguno de los dos. {@code bin} puede ser solo el principio del archivo.
     */
    public static Kind classify(byte[] bin) {
        if (bin.length < Container.HEADER || !Bytes.startsWith(bin, Container.MAGIC)) {
            return null;
        }
        try {
            int comp = Bytes.u32i(bin, 4);
            int raw = Bytes.u32i(bin, 8);
            byte[] h = inflateHead(bin, Math.min(comp, bin.length - Container.HEADER), Math.min(raw, HEAD_BYTES));
            if (h == null || h.length < 20 || Bytes.u32(h, 0) != 3 || Bytes.u32(h, 4) != 8) {
                return null;
            }
            int o0 = Bytes.u32i(h, 8), o1 = Bytes.u32i(h, 12), o2 = Bytes.u32i(h, 16);
            if (!(o0 < o1 && o1 < o2 && o2 < raw) || o2 + 4 > h.length) {
                return null;
            }
            if (Bytes.u32(h, o0) != Ps2Model.MAGIC) {
                return null;
            }
            boolean t1 = isTextureAt(h, o1), t2 = isTextureAt(h, o2);
            if (t1 && t2) {
                return Kind.HAIR;
            }
            if (!t1 && t2) {
                return Kind.FACE;
            }
            return null;
        } catch (ConversionException e) {
            return null;
        }
    }

    private static boolean isTextureAt(byte[] h, int off) {
        for (int i = 0; i < Texture.MAGIC.length; i++) {
            if (h[off + i] != Texture.MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** Descomprime como mucho {@code max} bytes; null si los datos no son zlib valido. */
    private static byte[] inflateHead(byte[] bin, int compLen, int max) {
        if (compLen <= 0 || max <= 0) {
            return null;
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(bin, Container.HEADER, compLen);
            byte[] out = new byte[max];
            int n = 0;
            while (n < max && !inf.finished()) {
                int r = inf.inflate(out, n, max - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += r;
            }
            if (n == max) {
                return out;
            }
            byte[] cut = new byte[n];
            System.arraycopy(out, 0, cut, 0, n);
            return cut;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }
}
