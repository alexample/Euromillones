package com.euromillones.app.data

import java.time.LocalDate

/**
 * MÁSCARAS (criterio equilibrado)
 *
 * Decenas  → por la cifra de la decena: L 0-1 · M 2-3 · H 4-5 (19/20/11 números)
 * Unidades → por último dígito:     L 0-2  · M 3-6   · H 7-9     (15/20/15 números)
 * Estrellas→ por valor:             L 1-4  · M 5-8   · H 9-12    (4/4/4)
 *
 * Como los grupos no tienen exactamente el mismo tamaño, cada máscara se compara
 * con su probabilidad teórica exacta (combinatoria), que es lo que la hace justa.
 */
enum class Level { L, M, H }

enum class MaskKind(
    val title: String,
    val rule: String,
    private val sizes: IntArray,
    private val total: Int,
    val picks: Int,
) {
    DECENAS("Decenas", "L decena 0-1 (1-19) · M 2-3 (20-39) · H 4-5 (40-50)", intArrayOf(19, 20, 11), 50, 5),
    UNIDADES("Unidades", "L 0-2 · M 3-6 · H 7-9 (último dígito)", intArrayOf(15, 20, 15), 50, 5),
    ESTRELLAS("Estrellas", "L 1-4 · M 5-8 · H 9-12", intArrayOf(4, 4, 4), 12, 2);

    fun level(n: Int): Level = when (this) {
        DECENAS -> when (n / 10) {
            0, 1 -> Level.L
            2, 3 -> Level.M
            else -> Level.H
        }
        UNIDADES -> when (n % 10) {
            in 0..2 -> Level.L
            in 3..6 -> Level.M
            else -> Level.H
        }
        ESTRELLAS -> if (n <= 4) Level.L else if (n <= 8) Level.M else Level.H
    }

    fun values(d: Draw): List<Int> = if (this == ESTRELLAS) d.stars else d.numbers

    fun maskOf(d: Draw): MaskKey {
        val lv = values(d).map(::level)
        return MaskKey(lv.count { it == Level.L }, lv.count { it == Level.M }, lv.count { it == Level.H })
    }

    /** Todas las máscaras posibles (l + m + h = picks). */
    fun allKeys(): List<MaskKey> = buildList {
        for (l in picks downTo 0) for (m in (picks - l) downTo 0) add(MaskKey(l, m, picks - l - m))
    }

    /**
     * Probabilidad exacta de que un sorteo concreto tenga esta máscara.
     * En estrellas depende de la fecha: hubo 9 hasta mayo de 2011, 11 hasta septiembre de 2016 y 12 desde entonces.
     */
    fun expected(k: MaskKey, d: Draw? = null): Double {
        var s = sizes
        var t = total
        if (this == ESTRELLAS && d != null) {
            t = starPool(d.date)
            s = intArrayOf(minOf(4, t), (t - 4).coerceIn(0, 4), (t - 8).coerceAtLeast(0))
        }
        return comb(s[0], k.l) * comb(s[1], k.m) * comb(s[2], k.h) / comb(t, picks)
    }
}

fun starPool(date: LocalDate): Int = when {
    date >= LocalDate.of(2016, 9, 24) -> 12
    date >= LocalDate.of(2011, 5, 10) -> 11
    else -> 9
}

data class MaskKey(val l: Int, val m: Int, val h: Int) {
    val label: String get() = "${l}L ${m}M ${h}H"
}

data class MaskStat(
    val key: MaskKey,
    val count: Int,
    val observed: Double,  // 0..1
    val expected: Double,  // 0..1
    val last: LocalDate?,
)

fun MaskKind.stats(draws: List<Draw>): List<MaskStat> {
    val byKey = draws.groupBy { maskOf(it) }
    val n = draws.size.coerceAtLeast(1)
    return allKeys().map { k ->
        val list = byKey[k].orEmpty()
        MaskStat(
            key = k,
            count = list.size,
            observed = list.size.toDouble() / n,
            // media de la probabilidad de cada sorteo (tiene en cuenta los cambios de reglas)
            expected = if (draws.isEmpty()) expected(k) else draws.sumOf { expected(k, it) } / draws.size,
            last = list.maxOfOrNull { it.date },
        )
    }.sortedWith(compareByDescending<MaskStat> { it.count }.thenByDescending { it.expected })
}

private fun comb(n: Int, k: Int): Double {
    if (k < 0 || k > n) return 0.0
    var r = 1.0
    for (i in 1..k) r = r * (n - k + i) / i
    return r
}
