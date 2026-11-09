package com.yotakon.pesport.core;

/** Texturas 8 bpp con paleta (formato comun a PES 2010 PS2 y PES 5 Xbox). */
public final class Texture {
    static final byte[] MAGIC = {(byte) 0x94, 0x72, (byte) 0x85, 0x29};

    private Texture() {
    }

    public static boolean isTexture(byte[] sec) {
        return Bytes.startsWith(sec, MAGIC);
    }

    /** {offsetPixeles, offsetPaleta, ancho, alto} */
    static int[] info(byte[] sec) {
        if (!isTexture(sec)) {
            throw new ConversionException("No es una textura de PES");
        }
        return new int[]{Bytes.u16(sec, 0x10), Bytes.u16(sec, 0x12), Bytes.u16(sec, 0x14),
                Bytes.u16(sec, 0x16)};
    }

    /** Orden de paleta de PS2: intercambia los bloques 8-15 y 16-23 de cada grupo de 32. */
    static int clutIndex(int i) {
        return (i & ~0x18) | ((i & 0x08) << 1) | ((i & 0x10) >> 1);
    }

    public static boolean isGrayscale(byte[] sec) {
        int pal = info(sec)[1];
        for (int k = 0; k < 1024; k += 4) {
            int r = Bytes.u8(sec, pal + k), g = Bytes.u8(sec, pal + k + 1), b = Bytes.u8(sec, pal + k + 2);
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 3) {
                return false;
            }
        }
        return true;
    }

    /** Colorea una paleta en gris: gris normalizado x color x brillo. El alfa no cambia. */
    public static byte[] tint(byte[] sec, int red, int green, int blue, double brightness) {
        int pal = info(sec)[1];
        byte[] out = sec.clone();
        double[] grays = new double[256];
        double gmax = 0;
        for (int i = 0; i < 256; i++) {
            int k = pal + 4 * i;
            grays[i] = (Bytes.u8(sec, k) + Bytes.u8(sec, k + 1) + Bytes.u8(sec, k + 2)) / 3.0;
            gmax = Math.max(gmax, grays[i]);
        }
        if (gmax == 0) {
            gmax = 1;
        }
        int[] rgb = {red, green, blue};
        for (int i = 0; i < 256; i++) {
            double g = grays[i] / gmax * brightness;
            for (int c = 0; c < 3; c++) {
                double v = Math.rint(g * rgb[c]);   // redondeo al par, como round() de Python
                out[pal + 4 * i + c] = (byte) Math.max(0, Math.min(255, (int) v));
            }
        }
        return out;
    }

    /** Pixeles ARGB (para vista previa). Devuelve {ancho, alto, pixel0, pixel1, ...}. */
    public static int[] toArgb(byte[] sec) {
        int[] inf = info(sec);
        int pix = inf[0], pal = inf[1], w = inf[2], h = inf[3];
        int[] out = new int[2 + w * h];
        out[0] = w;
        out[1] = h;
        for (int i = 0; i < w * h; i++) {
            int k = pal + 4 * clutIndex(Bytes.u8(sec, pix + i));
            int a = Math.min(255, Bytes.u8(sec, k + 3) * 2);
            out[2 + i] = (a << 24) | (Bytes.u8(sec, k) << 16) | (Bytes.u8(sec, k + 1) << 8)
                    | Bytes.u8(sec, k + 2);
        }
        return out;
    }
}
