package com.yotakon.pesport.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Contenedor .bin de caras/pelos, igual en PES 2010 PS2 y PES 5 Xbox. */
object Container {
    internal val MAGIC = byteArrayOf(0x00, 0x06, 0x01, 0x00)
    internal const val HEADER = 0x20

    fun unpack(data: ByteArray): ByteArray {
        if (!Bytes.startsWith(data, MAGIC)) {
            throw ConversionException("No es un archivo .bin de FACE o HAIR de PES")
        }
        val comp = Bytes.u32i(data, 4)
        val raw = Bytes.u32i(data, 8)
        if (HEADER + comp > data.size) {
            throw ConversionException("Archivo .bin truncado")
        }
        val inf = Inflater()
        try {
            inf.setInput(data, HEADER, comp)
            val out = ByteArray(raw)
            var n = 0
            while (n < raw && !inf.finished()) {
                val r = inf.inflate(out, n, raw - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            if (n != raw || !inf.finished()) {
                throw ConversionException("Datos comprimidos corruptos")
            }
            return out
        } catch (e: DataFormatException) {
            throw ConversionException("Datos comprimidos corruptos: ${e.message}")
        } finally {
            inf.end()
        }
    }

    fun pack(raw: ByteArray): ByteArray {
        val def = Deflater(9)
        val comp = ByteArrayOutputStream(raw.size / 2 + 64)
        try {
            def.setInput(raw)
            def.finish()
            val buf = ByteArray(8192)
            while (!def.finished()) {
                val n = def.deflate(buf)
                comp.write(buf, 0, n)
            }
        } finally {
            def.end()
        }
        val c = comp.toByteArray()
        val out = ByteBuf()
        out.put(MAGIC)
        out.u32(c.size.toLong())
        out.u32(raw.size.toLong())
        out.zeros(HEADER - 12)
        out.put(c)
        return out.toArray()
    }

    fun split(raw: ByteArray): List<ByteArray> {
        val n = Bytes.u32i(raw, 0)
        if (n < 1 || n > 64) {
            throw ConversionException("Numero de secciones invalido: $n")
        }
        val offs = IntArray(n + 1)
        for (i in 0 until n) offs[i] = Bytes.u32i(raw, 8 + 4 * i)
        offs[n] = raw.size
        val out = ArrayList<ByteArray>(n)
        for (i in 0 until n) {
            if (offs[i] > offs[i + 1]) {
                throw ConversionException("Tabla de secciones corrupta")
            }
            out.add(Bytes.slice(raw, offs[i], offs[i + 1]))
        }
        return out
    }

    fun join(sections: List<ByteArray>): ByteArray {
        var head = 8 + 4 * sections.size
        head = (head + 0x1F) and 0x1F.inv()
        val out = ByteBuf()
        out.zeros(head)
        out.setU32(0, sections.size.toLong())
        out.setU32(4, 8)
        for (i in sections.indices) {
            out.setU32(8 + 4 * i, out.size.toLong())
            out.put(sections[i])
            out.align(16)
        }
        return out.toArray()
    }
}

/** Texturas 8 bpp con paleta (formato común a PES 2010 PS2 y PES 5 Xbox). */
object Texture {
    internal val MAGIC = byteArrayOf(0x94.toByte(), 0x72, 0x85.toByte(), 0x29)

    fun isTexture(sec: ByteArray): Boolean = Bytes.startsWith(sec, MAGIC)

    /** {offsetPixeles, offsetPaleta, ancho, alto} */
    internal fun info(sec: ByteArray): IntArray {
        if (!isTexture(sec)) throw ConversionException("No es una textura de PES")
        return intArrayOf(Bytes.u16(sec, 0x10), Bytes.u16(sec, 0x12), Bytes.u16(sec, 0x14), Bytes.u16(sec, 0x16))
    }

    /** Orden de paleta de PS2: intercambia los bloques 8-15 y 16-23 de cada grupo de 32. */
    internal fun clutIndex(i: Int): Int = (i and 0x18.inv()) or ((i and 0x08) shl 1) or ((i and 0x10) shr 1)

    fun isGrayscale(sec: ByteArray): Boolean {
        val pal = info(sec)[1]
        var k = 0
        while (k < 1024) {
            val r = Bytes.u8(sec, pal + k)
            val g = Bytes.u8(sec, pal + k + 1)
            val b = Bytes.u8(sec, pal + k + 2)
            if (maxOf(r, g, b) - minOf(r, g, b) > 3) return false
            k += 4
        }
        return true
    }

    /** Colorea una paleta en gris: gris normalizado x color x brillo. El alfa no cambia. */
    fun tint(sec: ByteArray, red: Int, green: Int, blue: Int, brightness: Double): ByteArray {
        val pal = info(sec)[1]
        val out = sec.clone()
        val grays = DoubleArray(256)
        var gmax = 0.0
        for (i in 0 until 256) {
            val k = pal + 4 * i
            grays[i] = (Bytes.u8(sec, k) + Bytes.u8(sec, k + 1) + Bytes.u8(sec, k + 2)) / 3.0
            gmax = maxOf(gmax, grays[i])
        }
        if (gmax == 0.0) gmax = 1.0
        val rgb = intArrayOf(red, green, blue)
        for (i in 0 until 256) {
            val g = grays[i] / gmax * brightness
            for (c in 0 until 3) {
                val v = Math.rint(g * rgb[c])   // redondeo al par, como round() de Python
                out[pal + 4 * i + c] = v.toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Píxeles ARGB (para vista previa). Devuelve {ancho, alto, pixel0, pixel1, ...}. */
    fun toArgb(sec: ByteArray): IntArray {
        val (pix, pal, w, h) = info(sec)
        val out = IntArray(2 + w * h)
        out[0] = w
        out[1] = h
        for (i in 0 until w * h) {
            val k = pal + 4 * clutIndex(Bytes.u8(sec, pix + i))
            val a = minOf(255, Bytes.u8(sec, k + 3) * 2)
            out[2 + i] = (a shl 24) or (Bytes.u8(sec, k) shl 16) or (Bytes.u8(sec, k + 1) shl 8) or Bytes.u8(sec, k + 2)
        }
        return out
    }
}
