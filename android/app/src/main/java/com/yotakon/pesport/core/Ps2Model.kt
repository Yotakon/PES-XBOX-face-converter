package com.yotakon.pesport.core

/** Modelo de PES 2010 PS2 (magic 0xFFFF0003, geometría en paquetes VIF). */
internal class Ps2Model {
    class Chunk(
        val positions: Array<IntArray>,   // int16 x3
        val normals: Array<IntArray>,     // int16 x3
        val uvs: Array<IntArray>,         // int16 x2 (escala 4096)
        val stripIndex: IntArray,         // índice local
        val stripAdc: BooleanArray,       // true = no dibujar el triángulo que termina aquí
        val kind: Int,                    // 2 = rígido, 3 = con piel
        val bone: Int,                    // índice en la lista de huesos del modelo
        val params: DoubleArray,          // 2 floats en +0x48
        val morphRange: IntArray?,        // {inicioBase, vérticeLocalInicial, cantidad} o null
    ) {
        /** Triángulos orientados; la paridad depende de la posición global en la tira. */
        fun triangles(): List<IntArray> {
            val tris = ArrayList<IntArray>()
            for (i in 2 until stripIndex.size) {
                if (stripAdc[i]) continue
                val a = stripIndex[i - 2]
                val b = stripIndex[i - 1]
                val c = stripIndex[i]
                if (a == b || b == c || a == c) continue
                tris.add(if (i % 2 == 0) intArrayOf(a, b, c) else intArrayOf(b, a, c))
            }
            return tris
        }
    }

    val bones = ArrayList<Int>()
    val chunks = ArrayList<Chunk>()
    var morphBaseCount = 0
    /** Cada morph: entradas {dx, dy, dz, índiceBase}. */
    val morphs = ArrayList<Array<IntArray>>()

    private class Unpack(val addr: Int, val values: Array<IntArray>)

    companion object {
        const val MAGIC = 0xFFFF0003L
        private const val CHUNK_HEADER = 0xB0
        private val ELEMENT_SIZE = intArrayOf(4, 2, 1)

        private fun unpacks(d: ByteArray, start: Int, end: Int): List<Unpack> {
            val out = ArrayList<Unpack>()
            var i = start
            while (i + 4 <= end) {
                val imm = Bytes.u16(d, i)
                val num = Bytes.u8(d, i + 2)
                val cmd = Bytes.u8(d, i + 3)
                i += 4
                val c = cmd and 0x7F
                if (c >= 0x60) {
                    val vn = (c shr 2) and 3
                    val vl = c and 3
                    if (vl == 3) throw ConversionException("Formato VIF no soportado (V4-5)")
                    val comps = vn + 1
                    val size = ELEMENT_SIZE[vl]
                    val n = if (num != 0) num else 256
                    val nbytes = (n * comps * size + 3) and 3.inv()
                    val unsigned = (imm and 0x4000) != 0
                    val base = i
                    val vals = Array(n) { k ->
                        IntArray(comps) { j ->
                            val p = base + (k * comps + j) * size
                            when (size) {
                                4 -> Bytes.s32(d, p)
                                2 -> if (unsigned) Bytes.u16(d, p) else Bytes.s16(d, p)
                                else -> if (unsigned) Bytes.u8(d, p) else Bytes.s8(d, p)
                            }
                        }
                    }
                    out.add(Unpack(imm and 0x3FF, vals))
                    i += nbytes
                } else if (c == 0x20) {
                    i += 4
                } else if (c == 0x30 || c == 0x31) {
                    i += 16
                } else if (c == 0x4A) {
                    i += (if (num != 0) num else 256) * 8
                } else if (c == 0x50 || c == 0x51) {
                    i += (if (imm != 0) imm else 65536) * 16
                }
            }
            return out
        }

        fun read(d: ByteArray): Ps2Model {
            if (Bytes.u32(d, 0) != MAGIC) throw ConversionException("No es un modelo de PES 2010 PS2")
            val m = Ps2Model()
            val nchunks = Bytes.u32i(d, 0x20)
            val off = Bytes.u32i(d, 0x24)
            val nbones = Bytes.u32i(d, 0x38)
            val boff = Bytes.u32i(d, 0x3C)
            for (i in 0 until nbones) m.bones.add(Bytes.u32i(d, boff + 4 * i))
            m.morphBaseCount = Bytes.u32i(d, 0x44)
            val nmorph = Bytes.u32i(d, 0x4C)
            val moff = Bytes.u32i(d, 0x50)
            for (k in 0 until nmorph) {
                val c = Bytes.u32i(d, moff + 8 * k)
                val o = Bytes.u32i(d, moff + 8 * k + 4)
                m.morphs.add(Array(c) { i -> IntArray(4) { j -> Bytes.s16(d, o + 8 * i + 2 * j) } })
            }

            var o = off
            for (k in 0 until nchunks) {
                val size = Bytes.u32i(d, o)
                val nidx = Bytes.u32i(d, o + 16)
                if (size <= 0) throw ConversionException("Chunk de tamano invalido en 0x${Integer.toHexString(o)}")
                val geo = unpacks(d, o + CHUNK_HEADER, o + size).filter { it.addr >= 4 }
                if (geo.size < 4) throw ConversionException("Chunk VIF incompleto en 0x${Integer.toHexString(o)}")
                val raw = ArrayList<Int>()
                for (t in geo[3].values) for (x in t) raw.add(x)
                if (nidx > raw.size) throw ConversionException("Tira de triangulos incompleta")
                val stripIndex = IntArray(nidx) { Math.floorDiv((raw[it] and 0x7FFF) - 4, 4) }
                val stripAdc = BooleanArray(nidx) { (raw[it] and 0x8000) != 0 }
                var morphRange: IntArray? = null
                if (Bytes.u32(d, o + 20) != 0L) {
                    val bs = Bytes.u16(d, o + 0x90)
                    val addr = Bytes.u16(d, o + 0x92)
                    val cnt = Bytes.u16(d, o + 0x94)
                    morphRange = intArrayOf(bs, Math.floorDiv(addr - 4, 4), cnt)
                }
                m.chunks.add(
                    Chunk(
                        positions = geo[0].values,
                        normals = geo[1].values,
                        uvs = geo[2].values,
                        stripIndex = stripIndex,
                        stripAdc = stripAdc,
                        kind = Bytes.u32i(d, o + 64),
                        bone = Bytes.u32i(d, o + 52),
                        params = doubleArrayOf(Bytes.f32(d, o + 0x48), Bytes.f32(d, o + 0x4C)),
                        morphRange = morphRange,
                    )
                )
                o += size
            }
            return m
        }
    }
}
