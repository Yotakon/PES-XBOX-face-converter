package com.yotakon.pesport.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Contenedor .bin de caras/pelos, igual en PES 2010 PS2 y PES 5 Xbox. */
public final class Container {
    static final byte[] MAGIC = {0x00, 0x06, 0x01, 0x00};
    static final int HEADER = 0x20;

    private Container() {
    }

    public static byte[] unpack(byte[] data) {
        if (!Bytes.startsWith(data, MAGIC)) {
            throw new ConversionException("No es un archivo .bin de FACE o HAIR de PES");
        }
        int comp = Bytes.u32i(data, 4);
        int raw = Bytes.u32i(data, 8);
        if (HEADER + comp > data.length) {
            throw new ConversionException("Archivo .bin truncado");
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(data, HEADER, comp);
            byte[] out = new byte[raw];
            int n = 0;
            while (n < raw && !inf.finished()) {
                int r = inf.inflate(out, n, raw - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                n += r;
            }
            if (n != raw || !inf.finished()) {
                throw new ConversionException("Datos comprimidos corruptos");
            }
            return out;
        } catch (DataFormatException e) {
            throw new ConversionException("Datos comprimidos corruptos: " + e.getMessage());
        } finally {
            inf.end();
        }
    }

    public static byte[] pack(byte[] raw) {
        Deflater def = new Deflater(9);
        ByteArrayOutputStream comp = new ByteArrayOutputStream(raw.length / 2 + 64);
        try {
            def.setInput(raw);
            def.finish();
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                comp.write(buf, 0, n);
            }
        } finally {
            def.end();
        }
        byte[] c = comp.toByteArray();
        ByteBuf out = new ByteBuf();
        out.put(MAGIC);
        out.u32(c.length);
        out.u32(raw.length);
        out.zeros(HEADER - 12);
        out.put(c);
        return out.toArray();
    }

    public static List<byte[]> split(byte[] raw) {
        int n = Bytes.u32i(raw, 0);
        if (n < 1 || n > 64) {
            throw new ConversionException("Numero de secciones invalido: " + n);
        }
        int[] offs = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offs[i] = Bytes.u32i(raw, 8 + 4 * i);
        }
        offs[n] = raw.length;
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (offs[i] > offs[i + 1]) {
                throw new ConversionException("Tabla de secciones corrupta");
            }
            out.add(Bytes.slice(raw, offs[i], offs[i + 1]));
        }
        return out;
    }

    public static byte[] join(List<byte[]> sections) {
        int head = 8 + 4 * sections.size();
        head = (head + 0x1F) & ~0x1F;
        ByteBuf out = new ByteBuf();
        out.zeros(head);
        out.setU32(0, sections.size());
        out.setU32(4, 8);
        for (int i = 0; i < sections.size(); i++) {
            out.setU32(8 + 4 * i, out.size());
            out.put(sections.get(i));
            out.align(16);
        }
        return out.toArray();
    }
}
