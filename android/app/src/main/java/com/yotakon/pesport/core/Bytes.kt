package com.yotakon.pesport.core

/** Error de conversión con un mensaje legible para el usuario. */
open class ConversionException(message: String) : RuntimeException(message)

/** Lectura little-endian con comprobación de límites. */
internal object Bytes {
    private fun check(d: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > d.size) {
            throw ConversionException("Archivo truncado o corrupto (lectura en 0x${Integer.toHexString(off)})")
        }
    }

    fun u8(d: ByteArray, off: Int): Int {
        check(d, off, 1)
        return d[off].toInt() and 0xFF
    }

    fun s8(d: ByteArray, off: Int): Int {
        check(d, off, 1)
        return d[off].toInt()
    }

    fun u16(d: ByteArray, off: Int): Int {
        check(d, off, 2)
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    fun s16(d: ByteArray, off: Int): Int = u16(d, off).toShort().toInt()

    fun u32(d: ByteArray, off: Int): Long {
        check(d, off, 4)
        return (d[off].toLong() and 0xFF) or ((d[off + 1].toLong() and 0xFF) shl 8) or
            ((d[off + 2].toLong() and 0xFF) shl 16) or ((d[off + 3].toLong() and 0xFF) shl 24)
    }

    fun s32(d: ByteArray, off: Int): Int = u32(d, off).toInt()

    /** u32 usado como offset o contador: debe caber en un Int positivo. */
    fun u32i(d: ByteArray, off: Int): Int {
        val v = u32(d, off)
        if (v > Int.MAX_VALUE) {
            throw ConversionException("Valor fuera de rango en 0x${Integer.toHexString(off)}")
        }
        return v.toInt()
    }

    fun f32(d: ByteArray, off: Int): Double = java.lang.Float.intBitsToFloat(s32(d, off)).toDouble()

    fun slice(d: ByteArray, from: Int, to: Int): ByteArray {
        check(d, from, to - from)
        return d.copyOfRange(from, to)
    }

    fun startsWith(d: ByteArray, magic: ByteArray): Boolean {
        if (d.size < magic.size) return false
        for (i in magic.indices) {
            if (d[i] != magic[i]) return false
        }
        return true
    }
}

/** Buffer de escritura little-endian que crece automáticamente. */
internal class ByteBuf {
    private var data = ByteArray(1024)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > data.size) {
            data = data.copyOf(maxOf(data.size * 2, size + extra))
        }
    }

    fun put(b: ByteArray) {
        ensure(b.size)
        System.arraycopy(b, 0, data, size, b.size)
        size += b.size
    }

    /** El array nuevo ya está a cero. */
    fun zeros(n: Int) {
        ensure(n)
        size += n
    }

    fun u16(v: Int) {
        ensure(2)
        data[size++] = v.toByte()
        data[size++] = (v ushr 8).toByte()
    }

    fun u32(v: Long) {
        ensure(4)
        setU32(size, v)
        size += 4
    }

    fun f32(v: Double) {
        u32(java.lang.Float.floatToRawIntBits(v.toFloat()).toLong() and 0xFFFFFFFFL)
    }

    fun setU32(pos: Int, v: Long) {
        data[pos] = v.toByte()
        data[pos + 1] = (v ushr 8).toByte()
        data[pos + 2] = (v ushr 16).toByte()
        data[pos + 3] = (v ushr 24).toByte()
    }

    fun align(n: Int) {
        while (size % n != 0) zeros(1)
    }

    fun toArray(): ByteArray = data.copyOf(size)
}
