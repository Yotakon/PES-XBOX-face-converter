package com.yotakon.pesport.core

import java.util.TreeMap
import java.util.TreeSet

/**
 * Parejas FACE → HAIR por número de archivo dentro del 0_text.afs, nombres de jugador de cada
 * FACE y nombres de los .bin extraídos. Las parejas salen de la base de datos de jugadores
 * ([PlayerDb]); el usuario puede cambiar cualquiera a mano.
 */
class Pairs {
    private val map = TreeMap<Int, Int>()
    private val names = TreeMap<Int, MutableList<String>>()
    /** FACES que aparecen en la base de datos de jugadores. */
    private val inDb = TreeSet<Int>()

    /** Número de HAIR de un FACE, o [NONE]. */
    fun hairFor(face: Int): Int = map[face] ?: NONE

    /** Primer FACE emparejado con este HAIR, o [NONE]. */
    fun faceFor(hair: Int): Int = map.entries.firstOrNull { it.value == hair }?.key ?: NONE

    fun set(face: Int, hair: Int) {
        if (hair == NONE) map.remove(face) else map[face] = hair
    }

    fun has(face: Int): Boolean = map.containsKey(face)

    fun size(): Int = map.size

    fun isHairUsed(hair: Int): Boolean = map.containsValue(hair)

    /** true si la FACE aparece en la base de datos de jugadores. */
    fun isInDb(face: Int): Boolean = face in inDb

    /** Cuántas FACES aparecen en la base de datos. */
    fun dbCount(): Int = inDb.size

    fun addName(face: Int, name: String?) {
        if (name.isNullOrEmpty()) return
        val l = names.getOrPut(face) { ArrayList() }
        if (name !in l) l.add(name)
    }

    /** Jugadores que usan esta FACE (normalmente uno). */
    fun names(face: Int): List<String> = names[face] ?: emptyList()

    /**
     * Jugadores separados por " / "; [NOT_IMPLEMENTED] si la FACE no está en la base de
     * datos; "" si no se cargó ninguna base de datos.
     */
    fun label(face: Int): String {
        val s = names(face).joinToString(" / ")
        return if (s.isEmpty() && inDb.isNotEmpty() && face !in inDb) NOT_IMPLEMENTED else s
    }

    /** Copia con las mismas parejas, nombres y orígenes. */
    fun copy(): Pairs {
        val p = Pairs()
        p.map.putAll(map)
        p.inDb.addAll(inDb)
        for ((k, v) in names) p.names[k] = ArrayList(v)
        return p
    }

    // ------------------------------------------------------------ cambios a mano

    /** Aplica los cambios del usuario ([NONE] = "Sin HAIR"). */
    fun apply(overrides: Map<Int, Int>) {
        for ((face, hair) in overrides) setManual(face, hair)
    }

    /** Cambio a mano. */
    fun setManual(face: Int, hair: Int) = set(face, hair)

    /** Lo que se deduce del nombre de un .bin. Los campos que no aparecen valen [NONE]. */
    class Name(
        /** FACE al que pertenece (por nombre "N FACE…" o "N HAIR (slot M)…"). */
        val face: Int,
        /** Número de HAIR en el AFS ("N HAIR (slot M)…" o "HAIR M (sin FACE).bin"). */
        val hair: Int,
        /** Primer número del nombre, para nombres de otras herramientas (p. ej. unnamed_1701.bin). */
        val slot: Int,
        /** true si el nombre sigue el formato de esta app. */
        val ours: Boolean,
        /** Jugador que aparece en el nombre ("" si no hay). */
        val player: String,
    )

    companion object {
        const val NONE = -1

        /** Nombre que se muestra para una FACE que no está en la base de datos. */
        const val NOT_IMPLEMENTED = "No Implementado"

        private val FACE_NAME = Regex("^(\\d+) FACE( - .*)?\\.bin$", RegexOption.IGNORE_CASE)
        private val HAIR_NAME = Regex("^(\\d+) HAIR \\(slot (\\d+)\\)( - .*)?\\.bin$", RegexOption.IGNORE_CASE)
        private val LONE_HAIR_NAME = Regex("^HAIR (\\d+) \\(sin FACE\\)\\.bin$", RegexOption.IGNORE_CASE)
        private val NUMBER = Regex("(\\d+)")

        /**
         * Parejas de la base de datos: el ID de FACE de cada jugador con su ID de HAIR, sin deducir
         * nada por el orden de los archivos. Jugador con peinado genérico = FACE sin HAIR.
         *
         * @param groupStart inicio de cada grupo de piel ([PlayerDb.groupStarts])
         */
        fun fromDb(
            players: List<PlayerDb.Player>, firstFace: Int, firstHair: Int, groupStart: IntArray,
            faces: Set<Int>, hairs: Set<Int>,
        ): Pairs {
            val p = Pairs()
            for (pl in players) {
                val face = pl.faceSlot(firstFace, groupStart)
                if (face == NONE || face !in faces) continue
                p.addName(face, pl.name)
                p.inDb.add(face)
                val hair = pl.hairSlot(firstHair)
                if (hair != NONE && hair in hairs && !p.has(face)) p.set(face, hair)
            }
            return p
        }

        /** Una línea "FACE=HAIR" por cambio; HAIR = -1 es "Sin HAIR". */
        fun serializeOverrides(overrides: Map<Int, Int>): String =
            overrides.entries.joinToString("") { "${it.key}=${it.value}\n" }

        fun parseOverrides(text: String?): TreeMap<Int, Int> {
            val out = TreeMap<Int, Int>()
            if (text == null) return out
            for (line in text.split("\n")) {
                val t = line.trim()
                val eq = t.indexOf('=')
                if (t.isEmpty() || t.startsWith("#") || eq < 0) continue
                val face = t.substring(0, eq).trim().toIntOrNull() ?: continue
                val hair = t.substring(eq + 1).trim().toIntOrNull() ?: continue
                out[face] = if (hair < 0) NONE else hair
            }
            return out
        }

        // ------------------------------------------------------------ nombres de archivo

        private fun suffix(player: String?): String {
            val s = if (player == null) "" else PlayerDb.safeName(player)
            return if (s.isEmpty()) "" else " - $s"
        }

        /** "1701 FACE - RONALDINHO.bin" (sin nombre de jugador: "1701 FACE.bin"). */
        fun faceName(face: Int, player: String?): String = "$face FACE${suffix(player)}.bin"

        /** El HAIR se nombra con el número de su FACE para que queden juntos al ordenar. */
        fun hairName(face: Int, hair: Int, player: String?): String = "$face HAIR (slot $hair)${suffix(player)}.bin"

        fun loneHairName(hair: Int): String = "HAIR $hair (sin FACE).bin"

        private fun playerOf(group: String?): String = if (group.isNullOrEmpty()) "" else group.substring(3).trim()

        private fun toInt(s: String): Int = s.toIntOrNull() ?: NONE

        fun parseName(name: String): Name {
            FACE_NAME.matchEntire(name)?.let { m ->
                val f = toInt(m.groupValues[1])
                return Name(f, NONE, f, true, playerOf(m.groupValues[2]))
            }
            HAIR_NAME.matchEntire(name)?.let { m ->
                val h = toInt(m.groupValues[2])
                return Name(toInt(m.groupValues[1]), h, h, true, playerOf(m.groupValues[3]))
            }
            LONE_HAIR_NAME.matchEntire(name)?.let { m ->
                val h = toInt(m.groupValues[1])
                return Name(NONE, h, h, true, "")
            }
            val m = NUMBER.find(name)
            return Name(NONE, NONE, if (m != null) toInt(m.groupValues[1]) else NONE, false, "")
        }
    }
}
