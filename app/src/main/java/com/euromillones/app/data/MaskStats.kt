package com.euromillones.app.data

import java.time.LocalDate

/**
 * Estadísticas de máscaras.
 *  - probability: probabilidad REAL de salir en el próximo sorteo (reglas actuales).
 *  - count / expectedCount: veces que ha salido frente a las esperadas en el histórico
 *    (en estrellas se tiene en cuenta que antes había 9 u 11).
 *  - drawsSince: sorteos desde la última vez que salió.
 * Cada sorteo es independiente: el retraso no cambia la probabilidad.
 */
enum class MaskScope(val title: String) {
    NUMEROS("Números"),
    ESTRELLAS("Estrellas"),
    COMPLETA("Completa"),
}

enum class StatSort(val title: String) {
    PROBABLES("Más probables"),
    SALIDAS("Más salidas"),
    RETRASADAS("Más retrasadas"),
}

data class PatternStat(
    val mask: String,
    val probability: Double,
    val count: Int,
    val expectedCount: Double,
    val last: LocalDate?,
    val drawsSince: Int?, // null = nunca ha salido
) {
    val averageGap: Double get() = if (probability > 0) 1 / probability else Double.POSITIVE_INFINITY
    fun delayRatio(totalDraws: Int): Double = (drawsSince ?: totalDraws).toDouble() / averageGap
}

fun maskOf(scope: MaskScope, d: Draw): String = when (scope) {
    MaskScope.NUMEROS -> Mask.numbers(d)
    MaskScope.ESTRELLAS -> Mask.stars(d)
    MaskScope.COMPLETA -> Mask.full(d)
}

/** Suma, sorteo a sorteo, la probabilidad de cada máscara de estrellas con el bombo que había. */
private fun starExpectedSums(draws: List<Draw>): Map<String, Double> {
    val byPool = draws.groupingBy { Mask.starPool(it.date) }.eachCount()
    val out = HashMap<String, Double>()
    byPool.forEach { (pool, n) ->
        Mask.starProbs(pool).forEach { (k, p) -> out[k] = (out[k] ?: 0.0) + p * n }
    }
    return out
}

/** draws ordenado del más reciente al más antiguo. */
fun computeStats(scope: MaskScope, draws: List<Draw>): List<PatternStat> {
    val masks = draws.map { maskOf(scope, it) }
    val count = masks.groupingBy { it }.eachCount()
    val first = HashMap<String, Int>()
    masks.forEachIndexed { i, m -> first.putIfAbsent(m, i) }
    val starSum = starExpectedSums(draws)
    val stars = Mask.starProbs(12)
    val n = draws.size

    fun stat(mask: String, p: Double, expected: Double): PatternStat {
        val f = first[mask]
        return PatternStat(mask, p, count[mask] ?: 0, expected, f?.let { draws[it].date }, f)
    }

    return when (scope) {
        MaskScope.NUMEROS -> Mask.numberProbs.map { (m, p) -> stat(m, p, p * n) }
        MaskScope.ESTRELLAS -> stars.map { (m, p) -> stat(m, p, starSum[m] ?: 0.0) }
        MaskScope.COMPLETA -> {
            val out = ArrayList<PatternStat>(Mask.numberProbs.size * stars.size)
            Mask.numberProbs.forEach { (m, p) ->
                stars.forEach { (s, ps) -> out += stat("$m $s", p * ps, p * (starSum[s] ?: 0.0)) }
            }
            out
        }
    }
}

/** Estadística de una sola máscara (para cuando se toca un sorteo). */
fun statOf(scope: MaskScope, mask: String, draws: List<Draw>): PatternStat {
    val idx = draws.indexOfFirst { maskOf(scope, it) == mask }
    val count = draws.count { maskOf(scope, it) == mask }
    val starSum = starExpectedSums(draws)
    val stars = Mask.starProbs(12)
    val (p, expected) = when (scope) {
        MaskScope.NUMEROS -> {
            val pn = Mask.numberProbs[mask] ?: 0.0
            pn to pn * draws.size
        }
        MaskScope.ESTRELLAS -> (stars[mask] ?: 0.0) to (starSum[mask] ?: 0.0)
        MaskScope.COMPLETA -> {
            val num = mask.substringBeforeLast(" ")
            val st = mask.substringAfterLast(" ")
            val pn = Mask.numberProbs[num] ?: 0.0
            (pn * (stars[st] ?: 0.0)) to (pn * (starSum[st] ?: 0.0))
        }
    }
    return PatternStat(mask, p, count, expected, if (idx >= 0) draws[idx].date else null, if (idx >= 0) idx else null)
}

fun List<PatternStat>.sortedFor(sort: StatSort, totalDraws: Int): List<PatternStat> = when (sort) {
    StatSort.PROBABLES -> sortedWith(compareByDescending<PatternStat> { it.probability }.thenByDescending { it.count })
    StatSort.SALIDAS -> sortedWith(compareByDescending<PatternStat> { it.count }.thenByDescending { it.probability })
    StatSort.RETRASADAS -> sortedByDescending { it.delayRatio(totalDraws) }
}
