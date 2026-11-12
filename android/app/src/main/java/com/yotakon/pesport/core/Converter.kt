package com.yotakon.pesport.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Conversión de caras y pelos de PES 2010 PS2 a PES 5 Xbox.
 *
 * Port exacto de pesport/convert.py (la versión validada en el juego). Las pruebas comparan
 * la salida byte a byte con la de Python.
 */
object Converter {
    enum class Kind { FACE, HAIR }

    /** Resultado de una conversión. */
    class Result(
        val kind: Kind,
        val data: ByteArray,
        /** Textura principal convertida (cara o pelo teñido), para vista previa. */
        val previewTexture: ByteArray,
    )

    /** Información de un .bin antes de convertir (para la interfaz). */
    class Info(val kind: Kind, val needsColor: Boolean)

    internal const val POS_SCALE = 1.0 / 16
    internal const val UV_SCALE = 1.0 / 4096
    internal const val NORMAL_SCALE = -8.0 / 32767

    private val GROUP_RIGID = group(1, 0x00, 0.0f)
    private val GROUP_SKIN_A = group(2, 0x7F, 2.0f)
    private val GROUP_SKIN_B = group(2, 0x7F, 8.0f)

    private val FACE_RIM: Array<DoubleArray> = run {
        val half = arrayOf(
            doubleArrayOf(4.158400058746338, -19.040000915527344, 27.360000610351562),
            doubleArrayOf(4.958399772644043, -46.400001525878906, 34.400001525878906),
            doubleArrayOf(6.078400135040283, -61.599998474121094, 32.79999923706055),
            doubleArrayOf(7.358399868011475, -36.79999923706055, 32.31999969482422),
            doubleArrayOf(10.399999618530273, 5.599999904632568, 20.0),
            doubleArrayOf(10.55840015411377, -28.799999237060547, 30.559999465942383),
            doubleArrayOf(10.55840015411377, -13.600000381469727, 28.15999984741211),
            doubleArrayOf(18.878400802612305, -80.80000305175781, 10.399999618530273),
            doubleArrayOf(18.878400802612305, -73.5999984741211, 23.68000030517578),
            doubleArrayOf(26.47711944580078, 13.583999633789062, 7.199999809265137),
        )
        Array(20) { i -> if (i < 10) half[i] else doubleArrayOf(half[i - 10][0], half[i - 10][1], -half[i - 10][2]) }
    }
    private const val RIM_TOLERANCE = 1.0

    private const val EYE_BONE = 102
    private val EYE_U = doubleArrayOf(0.7343800067901611, 0.7617200016975403, 0.8164100050926208,
        0.8710899949073792, 0.8984400033950806)
    private val EYE_V = doubleArrayOf(0.78125, 0.875, 0.9921900033950806)
    private const val EYE_UV_TOLERANCE = 2.0 / 256

    /** Huesos de PES 2010 que no existen en PES 5 (null = descartar). */
    private val BONE_MAP: Map<Int, Int?> = mapOf(103 to null, 111 to 114)

    private fun group(n: Int, x: Int, f: Float): ByteArray {
        val b = ByteBuf()
        b.u16(n)
        b.u16(x)
        b.put(ByteArray(12) { 0xFF.toByte() })
        b.f32(f.toDouble())
        b.u32(0)
        return b.toArray()
    }

    private fun mapBone(b: Int): Int? = if (BONE_MAP.containsKey(b)) BONE_MAP[b] else b

    private fun xboxBones(ps2: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        for (b in ps2) {
            val m = mapBone(b)
            if (m != null && m !in out) out.add(m)
        }
        return out
    }

    private fun hairGroup(params: DoubleArray): ByteArray = if (params[0] < 700) GROUP_SKIN_A else GROUP_SKIN_B

    private class PlanEntry(val group: ByteArray) {
        val extra = LinkedHashMap<Int, Int>()
        val chunks = ArrayList<Ps2Model.Chunk>()
    }

    private fun xbone(m: Ps2Model, c: Ps2Model.Chunk, xbones: List<Int>): Int? {
        if (c.bone < 0 || c.bone >= m.bones.size) {
            throw ConversionException("Indice de hueso invalido: ${c.bone}")
        }
        val b = mapBone(m.bones[c.bone]) ?: return null
        return xbones.indexOf(b)
    }

    private fun plan(m: Ps2Model, kind: Kind, xbones: List<Int>): List<PlanEntry> {
        val chunks = m.chunks.filter { xbone(m, it, xbones) != null }
        val plan = ArrayList<PlanEntry>()
        if (kind == Kind.FACE) {
            for (c in chunks) {
                if (c.kind == 2) {
                    val e = PlanEntry(GROUP_RIGID)
                    e.extra[2] = xbone(m, c, xbones)!!
                    e.chunks.add(c)
                    plan.add(e)
                }
            }
            val skin = LinkedHashMap<Int, PlanEntry>()
            for (c in chunks) {
                if (c.kind != 2) {
                    val b = xbone(m, c, xbones)!!
                    val e = skin.getOrPut(b) { PlanEntry(GROUP_SKIN_A).also { it.extra[2] = b } }
                    e.chunks.add(c)
                }
            }
            plan.addAll(skin.values)
        } else {
            for (c in chunks) {
                val g = hairGroup(c.params)
                val b = xbone(m, c, xbones)!!
                var found = plan.firstOrNull { it.group === g && it.extra[2] == b }
                if (found == null) {
                    found = PlanEntry(g)
                    found.extra[2] = b
                    found.extra[0x0A] = 1
                    plan.add(found)
                }
                found.chunks.add(c)
            }
        }
        return plan
    }

    /** Clave de vértice con la igualdad de Python (0.0 == -0.0). */
    private fun key(v: DoubleArray): List<Double> = v.map { it + 0.0 }

    private fun snapRim(v: DoubleArray): DoubleArray {
        var best: DoubleArray? = null
        var dist = RIM_TOLERANCE
        for (r in FACE_RIM) {
            val dx = v[0] - r[0]
            val dy = v[1] - r[1]
            val dz = v[2] - r[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < dist) {
                best = r
                dist = d
            }
        }
        if (best == null) return v
        val o = v.clone()
        o[0] = best[0]
        o[1] = best[1]
        o[2] = best[2]
        return o
    }

    private fun nearest(x: Double, table: DoubleArray): Double {
        var best = table[0]
        for (t in table) if (abs(t - x) < abs(best - x)) best = t
        if (abs(best - x) > EYE_UV_TOLERANCE) {
            throw ConversionException(String.format(Locale.ROOT, "UV de ojo fuera de la plantilla de PES 5 (%.4f)", x))
        }
        return best
    }

    private fun sameTriangles(a: List<IntArray>, b: List<IntArray>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!XboxModel.canonical(a[i]).contentEquals(XboxModel.canonical(b[i]))) return false
        }
        return true
    }

    internal fun convertModel(ps2Bytes: ByteArray, kind: Kind): ByteArray {
        val model = Ps2Model.read(ps2Bytes)
        val bones = xboxBones(model.bones)
        val out = XboxModel()
        out.bones = bones
        val baseToXbox = HashMap<Int, Int>()

        for (pe in plan(model, kind, bones)) {
            if (pe.group !in out.groups) out.groups.add(pe.group)
            val vstart = out.vertices.size
            val lookup = HashMap<List<Double>, Int>()
            val tris = ArrayList<IntArray>()
            for (c in pe.chunks) {
                val n = c.positions.size
                if (c.normals.size != n || c.uvs.size != n) {
                    throw ConversionException("Chunk con datos de vertices incompletos")
                }
                val local = IntArray(n)
                for (i in 0 until n) {
                    val p = c.positions[i]
                    val nr = c.normals[i]
                    val uv = c.uvs[i]
                    val v = doubleArrayOf(p[0] * POS_SCALE, p[1] * POS_SCALE, p[2] * POS_SCALE,
                        nr[0] * NORMAL_SCALE, nr[1] * NORMAL_SCALE, nr[2] * NORMAL_SCALE,
                        uv[0] * UV_SCALE, uv[1] * UV_SCALE)
                    local[i] = lookup.getOrPut(key(v)) {
                        out.vertices.add(v)
                        out.vertices.size - 1
                    }
                }
                c.morphRange?.let { (bs, first, cnt) ->
                    if (first < 0 || first + cnt > n) throw ConversionException("Rango de morph fuera del chunk")
                    for (j in 0 until cnt) baseToXbox.putIfAbsent(bs + j, local[first + j])
                }
                for (t in c.triangles()) {
                    for (x in t) {
                        if (x < 0 || x >= n) throw ConversionException("Indice de vertice fuera de rango en la tira")
                    }
                    tris.add(intArrayOf(local[t[0]], local[t[1]], local[t[2]]))
                }
            }
            val strip = XboxModel.buildStrip(tris)
            if (!sameTriangles(XboxModel.stripTriangles(strip), tris)) {
                throw ConversionException("La tira generada no reproduce los triangulos")
            }
            val state = LinkedHashMap<Int, Int>()
            state[3] = 0
            state[0x0D] = 7
            state[4] = 0
            state[1] = out.groups.indexOf(pe.group)
            state.putAll(pe.extra)
            out.parts.add(XboxModel.Part(state, strip, vstart, out.vertices.size - vstart, tris.size))
        }

        if (out.vertices.size > 0xFFFF) throw ConversionException("Demasiados vertices (${out.vertices.size})")

        if (kind == Kind.FACE) {
            for (i in out.vertices.indices) out.vertices[i] = snapRim(out.vertices[i])
            for (p in out.parts) {
                if (bones[p.state[2]!!] == EYE_BONE) {
                    for (i in p.vstart until p.vstart + p.vcount) {
                        val v = out.vertices[i].clone()
                        v[6] = nearest(v[6], EYE_U)
                        v[7] = nearest(v[7], EYE_V)
                        out.vertices[i] = v
                    }
                }
            }
        }

        for (mid in model.morphs.indices) {
            val acc = LinkedHashMap<Int, DoubleArray>()
            for (e in model.morphs[mid]) {
                val base = e[3]
                if (base >= model.morphBaseCount) {
                    throw ConversionException("Morph $mid apunta a un vertice inexistente ($base)")
                }
                val xi = baseToXbox[base] ?: continue   // vértice de un chunk descartado
                acc.putIfAbsent(xi, doubleArrayOf(e[0] * POS_SCALE, e[1] * POS_SCALE, e[2] * POS_SCALE))
            }
            val idx = acc.keys.toIntArray().also { it.sort() }
            val d = Array(idx.size) { acc[idx[it]]!! }
            out.morphs.add(XboxModel.Morph(mid, idx, d))
        }
        return out.write()
    }

    private fun detectKind(sections: List<ByteArray>): Kind {
        if (sections.size != 3) throw ConversionException("Se esperaban 3 secciones, hay ${sections.size}")
        return if (Texture.isTexture(sections[1])) Kind.HAIR else Kind.FACE
    }

    private fun ps2Sections(bin: ByteArray): List<ByteArray> {
        val s = Container.split(Container.unpack(bin))
        detectKind(s)
        val magic = Bytes.u32(s[0], 0)
        if (magic != Ps2Model.MAGIC) {
            if (magic == XboxModel.MAGIC) throw ConversionException("El archivo ya es de PES 5 Xbox")
            throw ConversionException("El archivo no es un modelo de PES 2010 PS2")
        }
        return s
    }

    private fun tint(t: ByteArray, rgb: Int, brightness: Double): ByteArray =
        Texture.tint(t, (rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF, brightness)

    /**
     * Textura que tendrá el archivo convertido (cara, o pelo teñido con el color indicado),
     * sin convertir los modelos. Para la vista previa del selector de color.
     */
    fun previewTexture(ps2Bin: ByteArray, hairRgb: Int?, hairBrightness: Double): ByteArray {
        val s = ps2Sections(ps2Bin)
        if (detectKind(s) == Kind.FACE) return s[2]
        val t = s[1]
        return if (hairRgb != null && Texture.isGrayscale(t)) tint(t, hairRgb, hairBrightness) else t
    }

    /** Analiza un .bin de PES 2010 PS2 sin convertirlo. */
    fun inspect(ps2Bin: ByteArray): Info {
        val s = ps2Sections(ps2Bin)
        val kind = detectKind(s)
        val gray = kind == Kind.HAIR && (Texture.isGrayscale(s[1]) || Texture.isGrayscale(s[2]))
        return Info(kind, gray)
    }

    /**
     * Convierte un .bin de PES 2010 PS2 en un .bin de PES 5 Xbox.
     *
     * @param hairRgb color 0xRRGGBB para teñir el pelo, o null. Obligatorio si el pelo está en gris.
     */
    fun convert(ps2Bin: ByteArray, hairRgb: Int?, hairBrightness: Double): Result {
        val s = ps2Sections(ps2Bin)
        val kind = detectKind(s)
        val out = ArrayList<ByteArray>()
        val preview: ByteArray
        if (kind == Kind.FACE) {
            out.add(convertModel(s[0], Kind.FACE))
            out.add(convertModel(s[1], Kind.FACE))
            out.add(s[2])
            preview = s[2]
        } else {
            var t1 = s[1]
            var t2 = s[2]
            if (Texture.isGrayscale(t1) || Texture.isGrayscale(t2)) {
                if (hairRgb == null) throw ConversionException("El HAIR esta en escala de grises: indica un color")
                t1 = tint(t1, hairRgb, hairBrightness)
                t2 = tint(t2, hairRgb, hairBrightness)
            }
            out.add(convertModel(s[0], Kind.HAIR))
            out.add(t1)
            out.add(t2)
            preview = t1
        }
        return Result(kind, Container.pack(Container.join(out)), preview)
    }
}
