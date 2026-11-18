package com.euromillones.app.data

import java.time.LocalDate

/**
 * Estadísticas de máscaras.
 *
 * - probability: probabilidad REAL de que la máscara salga en el próximo sorteo
 *   (combinatoria exacta con las reglas actuales: 5 de 50 y 2 de 12).
 * - count / expectedCount: veces que ha salido en el histórico frente a las esperadas.
 * - drawsSince: sorteos transcurridos desde su última aparición (retraso).
 *
 * Cada sorteo es independiente: el retraso no cambia la probabilidad. Se muestra como seguimiento.
 */
enum class StatKind(val title: String) {
    COMBINADA("Dec + Uni"),
    DECENAS("Decenas"),
    UNIDADES("Unidades"),
    ESTRELLAS("Estrellas"),
}

enum class StatSort(val title: String) { PROBABLES("Más probables"), RETRASADAS("Más retrasadas") }

data class PatternStat(
    val parts: List<Pair<String, MaskKey>>, // p.ej. [("Dec", 2L2M1H), ("Uni", 1L2M2H)]
    val probability: Double,
    val count: Int,
    val expectedCount: Double,
    val last: LocalDate?,
    val drawsSince: Int?, // null = nunca ha salido
) {
    val id: String get() = parts.joinToString("|") { it.first + it.second.label }

    /** Cada cuántos sorteos sale de media. */
    val averageGap: Double get() = if (probability > 0) 1 / probability else Double.POSITIVE_INFINITY

    /** Retraso actual / retraso medio (1 = lo normal). */
    fun delayRatio(totalDraws: Int): Double = (drawsSince ?: totalDraws).toDouble() / averageGap
}

/** draws debe venir ordenado del más reciente al más antiguo. */
fun computeStats(kind: StatKind, draws: List<Draw>): List<PatternStat> = when (kind) {
    StatKind.DECENAS -> simple(MaskKind.DECENAS, "Dec", draws)
    StatKind.UNIDADES -> simple(MaskKind.UNIDADES, "Uni", draws)
    StatKind.ESTRELLAS -> simple(MaskKind.ESTRELLAS, "Est", draws)
    StatKind.COMBINADA -> combined(draws)
}

private fun simple(mk: MaskKind, tag: String, draws: List<Draw>): List<PatternStat> {
    val masks = draws.map { mk.maskOf(it) }
    return mk.allKeys().map { k ->
        val first = masks.indexOf(k)
        PatternStat(
            parts = listOf(tag to k),
            probability = mk.expected(k), // reglas actuales
            count = masks.count { it == k },
            expectedCount = draws.sumOf { mk.expected(k, it) },
            last = if (first >= 0) draws[first].date else null,
            drawsSince = if (first >= 0) first else null,
        )
    }.filter { it.probability > 0 }
}

/** Máscara de decenas y unidades a la vez (no son independientes: se calcula la conjunta exacta). */
private fun combined(draws: List<Draw>): List<PatternStat> {
    val probs = combinedProbabilities()
    val masks = draws.map { MaskKind.DECENAS.maskOf(it) to MaskKind.UNIDADES.maskOf(it) }
    val countBy = masks.groupingBy { it }.eachCount()
    val firstBy = HashMap<Pair<MaskKey, MaskKey>, Int>()
    masks.forEachIndexed { i, m -> if (m !in firstBy) firstBy[m] = i }
    return probs.map { (key, p) ->
        val first = firstBy[key]
        PatternStat(
            parts = listOf("Dec" to key.first, "Uni" to key.second),
            probability = p,
            count = countBy[key] ?: 0,
            expectedCount = p * draws.size,
            last = first?.let { draws[it].date },
            drawsSince = first,
        )
    }
}

private var cachedCombined: Map<Pair<MaskKey, MaskKey>, Double>? = null

fun combinedProbabilities(): Map<Pair<MaskKey, MaskKey>, Double> {
    cachedCombined?.let { return it }
    // 9 casillas: (nivel decena, nivel unidad) con cuántos números del 1 al 50 caen en cada una
    val size = IntArray(9)
    for (n in 1..50) size[MaskKind.DECENAS.level(n).ordinal * 3 + MaskKind.UNIDADES.level(n).ordinal]++
    val total = comb(50, 5)
    val out = HashMap<Pair<MaskKey, MaskKey>, Double>()
    val c = IntArray(9)

    fun rec(idx: Int, left: Int) {
        if (idx == 9) {
            if (left != 0) return
            var ways = 1.0
            for (i in 0 until 9) ways *= comb(size[i], c[i])
            if (ways == 0.0) return
            val dec = MaskKey(c[0] + c[1] + c[2], c[3] + c[4] + c[5], c[6] + c[7] + c[8])
            val uni = MaskKey(c[0] + c[3] + c[6], c[1] + c[4] + c[7], c[2] + c[5] + c[8])
            val key = dec to uni
            out[key] = (out[key] ?: 0.0) + ways / total
            return
        }
        for (x in 0..left) {
            c[idx] = x
            rec(idx + 1, left - x)
        }
        c[idx] = 0
    }
    rec(0, 5)
    cachedCombined = out
    return out
}

fun List<PatternStat>.sortedFor(sort: StatSort, totalDraws: Int): List<PatternStat> = when (sort) {
    StatSort.PROBABLES -> sortedByDescending { it.probability }
    StatSort.RETRASADAS -> sortedByDescending { it.delayRatio(totalDraws) }
}

internal fun comb(n: Int, k: Int): Double {
    if (k < 0 || k > n) return 0.0
    var r = 1.0
    for (i in 1..k) r = r * (n - k + i) / i
    return r
}
