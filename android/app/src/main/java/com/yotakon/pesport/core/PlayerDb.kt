package com.yotakon.pesport.core

import java.util.Locale
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Base de datos de jugadores de PES 2010 PS2 dentro del 0_text.afs (archivos 32 y 33, iguales en
 * caras y pelos). Da el nombre, la FACE y el HAIR de cada jugador.
 *
 * Formato del archivo: contenedor `00 06 00 00`; en 0x20 la tabla de secciones
 * (u32 número, u32 8, u32 offsets relativos a 0x20). La sección 0 es `00 00 01 00`,
 * u32 tamaño comprimido, u32 tamaño descomprimido y zlib en +0x20. Descomprimida son registros de
 * 124 bytes: nombre en japonés (UTF-16, 32 bytes), nombre (ASCII, 16 bytes) y datos desde +48.
 *
 * Campos (como en los editores de option file de PES 2007-2010):
 * - +88, bits 6-7: color de piel (0-3).
 * - +92, 12 bits: peinado. Desde 1044 es un HAIR especial: archivo = primer HAIR + (id - 1044).
 * - +100, bits 5-15: número de FACE dentro de su grupo de piel.
 * - +102, bits 0-1: tipo de cara; 2 = FACE especial (tiene archivo en el AFS).
 *
 * Las FACES del AFS están agrupadas por color de piel en el orden 3, 2, 0, 1; el tamaño de cada
 * grupo está en el archivo 505 ([groupStarts]).
 */
object PlayerDb {
    const val RECORD = 124
    /** Archivos del 0_text.afs donde está la base de datos. */
    val AFS_ENTRIES = intArrayOf(32, 33)
    /** Primer peinado que es un HAIR especial (el primer archivo de HAIR del AFS). */
    const val FIRST_SPECIAL_HAIR = 1044
    /** Archivo del 0_text.afs con el número de FACES de cada grupo de piel (sección 0, u16 x4). */
    const val GROUP_TABLE_ENTRY = 505
    /** Orden de los grupos de piel en el bloque de FACES del AFS. */
    internal val GROUP_ORDER = intArrayOf(3, 2, 0, 1)
    /**
     * Inicio de cada grupo (índice = color de piel), contado desde el primer archivo de FACE.
     * Valores del 0_text.afs de PES 2010 PS2 (grupos de 800, 341, 126 y 164 FACES); se usan si
     * no se puede leer el archivo [GROUP_TABLE_ENTRY].
     */
    val DEFAULT_GROUP_START = intArrayOf(290, 1090, 164, 0)

    /**
     * Inicio de cada grupo de piel leído del archivo 505, o null si no tiene el formato esperado
     * o los grupos no suman [faceCount].
     */
    fun groupStarts(entry505: ByteArray, faceCount: Int): IntArray? = try {
        val s0 = Container.split(Container.unpack(entry505))[0]
        val size = IntArray(4) { Bytes.u16(s0, 2 * it) }
        if (size.sum() != faceCount) {
            null
        } else {
            val start = IntArray(4)
            var at = 0
            for (g in GROUP_ORDER) {
                start[g] = at
                at += size[g]
            }
            start
        }
    } catch (e: RuntimeException) {
        null
    }

    class Player(
        val index: Int,
        val name: String,
        val skin: Int,
        val specialFace: Boolean,
        val faceId: Int,
        val hairId: Int,
    ) {
        /** Archivo de FACE en el AFS, o [Pairs.NONE] si el jugador no tiene cara especial. */
        fun faceSlot(firstFace: Int, groupStart: IntArray): Int =
            if (specialFace) firstFace + groupStart[skin] + faceId else Pairs.NONE

        /** Archivo de HAIR en el AFS, o [Pairs.NONE] si usa un peinado genérico. */
        fun hairSlot(firstHair: Int): Int =
            if (hairId >= FIRST_SPECIAL_HAIR) firstHair + hairId - FIRST_SPECIAL_HAIR else Pairs.NONE
    }

    /** Lee los jugadores de un archivo del AFS; lista vacía si no es la base de datos. */
    fun read(entry: ByteArray): List<Player> {
        val raw = unpack(entry) ?: return emptyList()
        val out = ArrayList<Player>()
        var i = 0
        while (i + RECORD <= raw.size) {
            val skin = (Bytes.u16(raw, i + 88) shr 6) and 3
            val hair = Bytes.u16(raw, i + 92) and 0xFFF
            val face = (Bytes.u16(raw, i + 100) shr 5) and 0x7FF
            val special = (Bytes.u16(raw, i + 102) and 3) == 2
            out.add(Player(i / RECORD, name(raw, i + 32), skin, special, face, hair))
            i += RECORD
        }
        return out
    }

    private fun name(raw: ByteArray, off: Int): String {
        val sb = StringBuilder()
        var k = 0
        while (k < 16 && raw[off + k].toInt() != 0) {
            sb.append((raw[off + k].toInt() and 0xFF).toChar())
            k++
        }
        return sb.toString().trim()
    }

    /** Sección 0 descomprimida, o null si el archivo no tiene el formato de la base de datos. */
    private fun unpack(e: ByteArray): ByteArray? {
        try {
            if (e.size < 0x40 || e[0].toInt() != 0 || e[1].toInt() != 6 || e[2].toInt() != 0 || e[3].toInt() != 0) return null
            val n = Bytes.u32i(e, 0x20)
            if (n < 1 || n > 64 || Bytes.u32(e, 0x24) != 8L) return null
            val sec = 0x20 + Bytes.u32i(e, 0x28)
            if (Bytes.u32(e, sec) != 0x00010000L) return null
            val comp = Bytes.u32i(e, sec + 4)
            val raw = Bytes.u32i(e, sec + 8)
            if (raw % RECORD != 0 || raw == 0 || raw > 4 * 1024 * 1024 || sec + 0x20 + comp > e.size) return null
            val inf = Inflater()
            try {
                inf.setInput(e, sec + 0x20, comp)
                val out = ByteArray(raw)
                var got = 0
                while (got < raw && !inf.finished()) {
                    val r = inf.inflate(out, got, raw - got)
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    got += r
                }
                return if (got == raw) out else null
            } finally {
                inf.end()
            }
        } catch (ex: ConversionException) {
            return null
        } catch (ex: DataFormatException) {
            return null
        }
    }

    /** Nombre apto para archivo: letras, números, espacio, punto, guion y apóstrofo. */
    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 .'\\-]"), "").replace(Regex("\\s+"), " ").trim().uppercase(Locale.ROOT)
}
