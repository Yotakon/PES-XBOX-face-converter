package com.yotakon.pesport.core

/** Reglas de los buscadores de las listas. */
object Search {
    private fun digits(q: String): Boolean = q.isNotEmpty() && q.all { it.isDigit() }

    /** Número sin ceros a la izquierda: "01701" y "1701" buscan lo mismo. */
    private fun number(q: String): String = q.trimStart('0').ifEmpty { "0" }

    /** ¿El archivo [index] (llamado [name]) coincide con [q]? Un número busca por índice; si no, por nombre. */
    fun matches(index: Int, name: String, q: String): Boolean =
        if (digits(q)) index.toString().contains(number(q)) else name.contains(q, ignoreCase = true)

    /** 0 = es justo ese número, 1 = empieza por él, 2 = el resto: lo exacto sale primero. */
    fun rank(index: Int, q: String): Int {
        if (!digits(q)) return 2
        val n = number(q)
        val i = index.toString()
        return when {
            i == n -> 0
            i.startsWith(n) -> 1
            else -> 2
        }
    }

    /** ¿El texto de una fila contiene [q] (sin distinguir mayúsculas)? */
    fun containsText(text: String, q: String): Boolean = text.contains(q, ignoreCase = true)
}
