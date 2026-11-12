package com.yotakon.pesport.core

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Reconoce si un .bin es un FACE o un HAIR de PES 2010 PS2 mirando solo su principio. */
object FaceHair {
    enum class Kind { FACE, HAIR }

    /**
     * Bytes comprimidos que bastan para clasificar: la tabla de secciones y el principio de la
     * última sección caben de sobra (un FACE descomprimido ocupa unos 36 KB).
     */
    const val HEAD_BYTES = 128 * 1024

    /**
     * FACE = 3 secciones: modelo, modelo, textura. HAIR = modelo, textura, textura.
     * Devuelve null si no es ninguno de los dos. [bin] puede ser solo el principio del archivo.
     */
    fun classify(bin: ByteArray): Kind? {
        if (bin.size < Container.HEADER || !Bytes.startsWith(bin, Container.MAGIC)) return null
        return try {
            val comp = Bytes.u32i(bin, 4)
            val raw = Bytes.u32i(bin, 8)
            val h = inflateHead(bin, minOf(comp, bin.size - Container.HEADER), minOf(raw, HEAD_BYTES))
            if (h == null || h.size < 20 || Bytes.u32(h, 0) != 3L || Bytes.u32(h, 4) != 8L) return null
            val o0 = Bytes.u32i(h, 8)
            val o1 = Bytes.u32i(h, 12)
            val o2 = Bytes.u32i(h, 16)
            if (!(o0 < o1 && o1 < o2 && o2 < raw) || o2 + 4 > h.size) return null
            if (Bytes.u32(h, o0) != Ps2Model.MAGIC) return null
            val t1 = isTextureAt(h, o1)
            val t2 = isTextureAt(h, o2)
            when {
                t1 && t2 -> Kind.HAIR
                !t1 && t2 -> Kind.FACE
                else -> null
            }
        } catch (e: ConversionException) {
            null
        }
    }

    private fun isTextureAt(h: ByteArray, off: Int): Boolean {
        for (i in Texture.MAGIC.indices) {
            if (h[off + i] != Texture.MAGIC[i]) return false
        }
        return true
    }

    /** Descomprime como mucho [max] bytes; null si los datos no son zlib válido. */
    private fun inflateHead(bin: ByteArray, compLen: Int, max: Int): ByteArray? {
        if (compLen <= 0 || max <= 0) return null
        val inf = Inflater()
        return try {
            inf.setInput(bin, Container.HEADER, compLen)
            val out = ByteArray(max)
            var n = 0
            while (n < max && !inf.finished()) {
                val r = inf.inflate(out, n, max - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            if (n == max) out else out.copyOf(n)
        } catch (e: DataFormatException) {
            null
        } finally {
            inf.end()
        }
    }
}
