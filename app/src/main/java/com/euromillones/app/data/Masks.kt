package com.euromillones.app.data

import java.time.LocalDate

/**
 * MÁSCARAS
 *
 * Cada número se convierte en dos letras: decena + unidad.
 *   Decena (primera cifra): L 0-1 (1-19) · M 2-3 (20-39) · H 4-5 (40-50)
 *   Unidad (última cifra):  L 0-2 · M 3-6 · H 7-9
 * Cada estrella en una letra: L 1-4 · M 5-8 · H 9-12
 *
 * Ejemplo: 7 23 35 44 48 + ★2 ★11  →  LH ML MM HM HH LH
 * (los números van ordenados de menor a mayor; las estrellas al final)
 */
enum class Level { L, M, H }

object Mask {
    const val RULES =
        "Decena: L 0-1 (1-19) · M 2-3 (20-39) · H 4-5 (40-50)\n" +
            "Unidad: L 0-2 · M 3-6 · H 7-9\n" +
            "Estrellas: L 1-4 · M 5-8 · H 9-12"

    fun dec(n: Int): Level = when (n / 10) {
        0, 1 -> Level.L
        2, 3 -> Level.M
        else -> Level.H
    }

    fun uni(n: Int): Level = when (n % 10) {
        in 0..2 -> Level.L
        in 3..6 -> Level.M
        else -> Level.H
    }

    fun star(s: Int): Level = if (s <= 4) Level.L else if (s <= 8) Level.M else Level.H

    fun token(n: Int): String = dec(n).name + uni(n).name

    fun numbers(d: Draw): String = d.numbers.sorted().joinToString(" ") { token(it) }
    fun stars(d: Draw): String = d.stars.sorted().joinToString("") { star(it).name }
    fun full(d: Draw): String = numbers(d) + " " + stars(d)

    /** Estrellas que había en el bombo según la fecha (9 hasta mayo 2011, 11 hasta sept 2016, luego 12). */
    fun starPool(date: LocalDate): Int = when {
        date >= LocalDate.of(2016, 9, 24) -> 12
        date >= LocalDate.of(2011, 5, 10) -> 11
        else -> 9
    }

    /**
     * Probabilidad exacta de cada máscara de números.
     * Se recorren las 2.118.760 combinaciones posibles (5 de 50) una sola vez.
     */
    val numberProbs: Map<String, Double> by lazy {
        val code = IntArray(51) { n -> if (n == 0) 0 else dec(n).ordinal * 3 + uni(n).ordinal }
        val counts = IntArray(59049) // 9^5
        for (a in 1..46) for (b in a + 1..47) for (c in b + 1..48) for (d in c + 1..49) for (e in d + 1..50) {
            counts[(((code[a] * 9 + code[b]) * 9 + code[c]) * 9 + code[d]) * 9 + code[e]]++
        }
        val total = 2_118_760.0
        val out = HashMap<String, Double>(4096)
        for (k in counts.indices) if (counts[k] > 0) out[decode(k)] = counts[k] / total
        out
    }

    private fun decode(k: Int): String {
        val toks = ArrayList<String>(5)
        var x = k
        repeat(5) {
            val c = x % 9
            toks += Level.entries[c / 3].name + Level.entries[c % 3].name
            x /= 9
        }
        return toks.reversed().joinToString(" ")
    }

    private val starCache = HashMap<Int, Map<String, Double>>()

    /** Probabilidad de cada máscara de estrellas con un bombo de [pool] estrellas. */
    fun starProbs(pool: Int = 12): Map<String, Double> = synchronized(starCache) {
        starCache.getOrPut(pool) {
            val m = HashMap<String, Int>()
            var total = 0
            for (i in 1 until pool) for (j in i + 1..pool) {
                val k = star(i).name + star(j).name
                m[k] = (m[k] ?: 0) + 1
                total++
            }
            m.mapValues { it.value.toDouble() / total }
        }
    }
}
