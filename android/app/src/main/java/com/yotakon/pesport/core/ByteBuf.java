package com.yotakon.pesport.core;

import java.util.Arrays;

/** Buffer de escritura little-endian que crece automaticamente. */
final class ByteBuf {
    private byte[] data = new byte[1024];
    private int size;

    int size() {
        return size;
    }

    private void ensure(int extra) {
        if (size + extra > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
        }
    }

    void put(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, data, size, b.length);
        size += b.length;
    }

    void zeros(int n) {
        ensure(n);
        size += n;   // el array nuevo ya esta a cero
    }

    void u16(int v) {
        ensure(2);
        data[size++] = (byte) v;
        data[size++] = (byte) (v >>> 8);
    }

    void u32(long v) {
        ensure(4);
        setU32(size, v);
        size += 4;
    }

    void f32(double v) {
        u32(Float.floatToRawIntBits((float) v) & 0xFFFFFFFFL);
    }

    void setU32(int pos, long v) {
        data[pos] = (byte) v;
        data[pos + 1] = (byte) (v >>> 8);
        data[pos + 2] = (byte) (v >>> 16);
        data[pos + 3] = (byte) (v >>> 24);
    }

    void align(int n) {
        while (size % n != 0) {
            zeros(1);
        }
    }

    byte[] toArray() {
        return Arrays.copyOf(data, size);
    }
}
