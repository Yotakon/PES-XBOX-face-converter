package com.yotakon.pesport.core

/** Escritura del modelo de PES 5 Xbox (magic 0x20040520). Ver docs/FORMATOS.md. */
internal class XboxModel {
    class Morph(val id: Int, val indices: IntArray, val deltas: Array<DoubleArray>)

    class Part(
        val state: LinkedHashMap<Int, Int>,
        val indices: IntArray,
        val vstart: Int,
        val vcount: Int,
        val ntris: Int,
    )

    val groups = ArrayList<ByteArray>()
    var bones: List<Int> = emptyList()
    /** x, y, z, nx, ny, nz, u, v */
    val vertices = ArrayList<DoubleArray>()
    val morphs = ArrayList<Morph>()
    val parts = ArrayList<Part>()

    private fun morphBlock(): ByteArray {
        // Orden estable: más índices primero; a igualdad, por id.
        val order = morphs.sortedWith(compareByDescending<Morph> { it.indices.size }.thenBy { it.id })
        val n = order.size
        val table = ByteBuf()
        table.u32(n.toLong())
        val deltas = ByteBuf()
        val baseD = 4 + 12 * n
        val dOff = IntArray(n)
        for (k in 0 until n) {
            dOff[k] = baseD + deltas.size
            for (d in order[k].deltas) {
                deltas.f32(d[0])
                deltas.f32(d[1])
                deltas.f32(d[2])
            }
        }
        val baseI = baseD + deltas.size
        val idxBlob = ByteBuf()
        val idxOff = HashMap<String, Int>()
        for (k in 0 until n) {
            val m = order[k]
            val key = m.indices.contentToString()
            val io = idxOff.getOrPut(key) {
                val at = baseI + idxBlob.size
                for (i in m.indices) idxBlob.u16(i)
                at
            }
            table.u16(m.id)
            table.u16(m.indices.size)
            table.u32(io.toLong())
            table.u32(dOff[k].toLong())
        }
        val blob = ByteBuf()
        blob.put(table.toArray())
        blob.put(deltas.toArray())
        blob.put(idxBlob.toArray())
        blob.align(4)
        return blob.toArray()
    }

    fun write(): ByteArray {
        val out = ByteBuf()
        out.zeros(0x48)

        val h6 = out.size
        var prev: Map<Int, Int> = emptyMap()
        for (p in parts) {
            for (tag in STATE_ORDER) {
                val v = p.state[tag]
                if (v != null && v != prev[tag]) {
                    out.u16(tag)
                    out.u16(v)
                }
            }
            for ((key, value) in p.state) {
                if (key !in STATE_ORDER && value != prev[key]) {
                    out.u16(key)
                    out.u16(value)
                }
            }
            prev = HashMap(p.state)
            out.u16(DRAW_TAG)
            out.u16(p.indices.size)
            out.u16(p.vstart)
            out.u16(p.vcount)
            out.u32(p.ntris.toLong())
        }
        out.zeros(4)

        val h1 = out.size
        out.u32(0)
        val h2 = out.size
        out.u32(groups.size.toLong())
        for (g in groups) out.put(g)
        val h3 = out.size
        out.u16(bones.size)
        for (b in bones) out.u16(b)
        out.align(4)

        val h4 = out.size
        val nv = vertices.size
        val moff = if (morphs.isEmpty()) 0L else 8L + nv.toLong() * VERTEX_STRIDE
        out.u32(1)
        out.u32(8)
        out.u16(nv)
        out.u16(VERTEX_STRIDE)
        out.u32(moff)
        for (v in vertices) for (x in v) out.f32(x)
        if (morphs.isNotEmpty()) out.put(morphBlock())

        val h5 = out.size
        val total = parts.sumOf { it.indices.size }
        if (total > 0xFFFF) throw ConversionException("Demasiados indices ($total)")
        out.u16(total)
        for (p in parts) for (i in p.indices) out.u16(i)
        out.align(4)

        val h7 = out.size
        out.u32(morphs.size.toLong())
        val h8 = out.size
        out.u32(2)
        out.zeros(24)
        val h9 = out.size
        out.u32(1)
        out.u32(0)
        out.align(16)

        val h = longArrayOf(MAGIC, h1.toLong(), h2.toLong(), h3.toLong(), h4.toLong(), h5.toLong(),
            h6.toLong(), h7.toLong(), h8.toLong(), h9.toLong())
        for (i in h.indices) out.setU32(4 * i, h[i])
        return out.toArray()
    }

    companion object {
        const val MAGIC = 0x20040520L
        const val VERTEX_STRIDE = 32
        val STATE_ORDER = intArrayOf(3, 1, 2, 0x0D, 4, 0x0A)
        const val DRAW_TAG = 0x0507

        // ------------------------------------------------------------ tiras de triángulos

        fun stripTriangles(seq: IntArray): List<IntArray> {
            val tris = ArrayList<IntArray>()
            var i = 0
            while (i + 2 < seq.size) {
                val a = seq[i]
                val b = seq[i + 1]
                val c = seq[i + 2]
                if (!(a == b || b == c || a == c)) {
                    tris.add(if (i % 2 == 0) intArrayOf(a, b, c) else intArrayOf(b, a, c))
                }
                i++
            }
            return tris
        }

        /** Rotación canónica de un triángulo orientado. */
        fun canonical(t: IntArray): IntArray {
            val r = arrayOf(intArrayOf(t[0], t[1], t[2]), intArrayOf(t[1], t[2], t[0]), intArrayOf(t[2], t[0], t[1]))
            var best = r[0]
            for (x in r) if (compare(x, best) < 0) best = x
            return best
        }

        private fun compare(a: IntArray, b: IntArray): Int {
            for (i in 0 until 3) {
                if (a[i] != b[i]) return a[i].compareTo(b[i])
            }
            return 0
        }

        private fun sameTri(x: Int, y: Int, z: Int, u: IntArray): Boolean {
            val a = u[0]
            val b = u[1]
            val c = u[2]
            return (x == a && y == b && z == c) || (x == b && y == c && z == a) || (x == c && y == a && z == b)
        }

        /** Convierte triángulos orientados en una tira con degenerados. */
        fun buildStrip(tris: List<IntArray>): IntArray {
            val seq = ArrayList<Int>()
            for (t in tris) {
                if (seq.size >= 2) {
                    val x = seq[seq.size - 2]
                    val y = seq[seq.size - 1]
                    val par = (seq.size - 2) % 2
                    var done = false
                    for (c in t) {
                        val same = if (par == 0) sameTri(x, y, c, t) else sameTri(y, x, c, t)
                        if (same && x != y && y != c && x != c) {
                            seq.add(c)
                            done = true
                            break
                        }
                    }
                    if (done) continue
                    val last = seq[seq.size - 1]
                    seq.add(last)
                    seq.add(t[0])
                    seq.add(t[0])
                    if ((seq.size - 1) % 2 == 1) seq.add(t[0])
                    seq.add(t[1])
                    seq.add(t[2])
                } else {
                    for (v in t) seq.add(v)
                }
            }
            return seq.toIntArray()
        }
    }
}
