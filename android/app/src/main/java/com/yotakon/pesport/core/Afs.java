package com.yotakon.pesport.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Archivo AFS (por ejemplo 0_text.afs): "AFS\0", u32 numero de archivos y tabla de
 * (u32 offset, u32 tamaño). Se lee por posiciones: el AFS (cientos de MB) nunca se carga entero.
 */
public final class Afs {
    private static final int MAX_FILES = 100000;

    private final FileChannel channel;
    private final long[] offsets;
    private final int[] sizes;

    private Afs(FileChannel channel, long[] offsets, int[] sizes) {
        this.channel = channel;
        this.offsets = offsets;
        this.sizes = sizes;
    }

    public static Afs open(FileChannel channel) throws IOException {
        long total = channel.size();
        if (total < 8) {
            throw new ConversionException("No es un archivo AFS");
        }
        byte[] head = readAt(channel, 0, 8);
        if (head[0] != 'A' || head[1] != 'F' || head[2] != 'S' || head[3] != 0) {
            throw new ConversionException("No es un archivo AFS");
        }
        int n = Bytes.u32i(head, 4);
        if (n < 1 || n > MAX_FILES || 8 + 8L * n > total) {
            throw new ConversionException("Tabla del AFS no válida");
        }
        byte[] table = readAt(channel, 8, 8 * n);
        long[] offsets = new long[n];
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            long off = Bytes.u32(table, 8 * i);
            long size = Bytes.u32(table, 8 * i + 4);
            if (off + size > total) {
                throw new ConversionException("El AFS está incompleto: el archivo " + i + " termina fuera");
            }
            offsets[i] = off;
            sizes[i] = (int) size;
        }
        return new Afs(channel, offsets, sizes);
    }

    public int count() {
        return sizes.length;
    }

    public int size(int i) {
        return sizes[i];
    }

    /** Contenido completo del archivo {@code i}. */
    public byte[] read(int i) throws IOException {
        return readAt(channel, offsets[i], sizes[i]);
    }

    /** Primeros {@code max} bytes (o menos) del archivo {@code i}. */
    public byte[] readHead(int i, int max) throws IOException {
        return readAt(channel, offsets[i], Math.min(max, sizes[i]));
    }

    private static byte[] readAt(FileChannel ch, long pos, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) {
                throw new IOException("fin de archivo inesperado");
            }
        }
        return b.array();
    }
}
