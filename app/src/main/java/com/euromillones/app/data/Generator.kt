package com.euromillones.app.data

import kotlin.random.Random

/**
 * Máscara elegida para generar. Cada parte es opcional (null = libre):
 *  numMask  "HM LL MH HH HM"   · starMask "LH"   (decena/unidad)
 *  tempNum  "C T F F C"        · tempStar "CF"   (caliente/templado/frío)
 */
data class GenTarget(
    val numMask: String?,
    val starMask: String?,
    val tempNum: String? = null,
    val tempStar: String? = null,
) {
    fun encode(): String = listOf(numMask, starMask, tempNum, tempStar).joinToString("|") { it.orEmpty() }

    companion object {
        fun fromScope(scope: MaskScope, mask: String): GenTarget = when (scope) {
            MaskScope.NUMEROS -> GenTarget(mask, null)
            MaskScope.ESTRELLAS -> GenTarget(null, mask)
            MaskScope.COMPLETA -> GenTarget(mask.substringBeforeLast(" "), mask.substringAfterLast(" "))
        }

        fun decode(s: String): GenTarget {
            val p = s.split("|")
            fun at(i: Int) = p.getOrNull(i)?.ifBlank { null }
            return GenTarget(at(0), at(1), at(2), at(3))
        }
    }
}

/** 5 números (1-50) empaquetados en un Int (6 bits cada uno). */
fun pack(nums: List<Int>): Int = nums.sorted().fold(0) { acc, n -> acc * 64 + n }

fun unpack(p: Int): List<Int> {
    val out = IntArray(5)
    var x = p
    for (i in 4 downTo 0) {
        out[i] = x % 64
        x /= 64
    }
    return out.toList()
}

const val ALL_NUMBER_COMBOS = 2_118_760L
const val ALL_STAR_COMBOS = 66L

/** Combinaciones que cumplen los filtros. numbers = null → cualquier combinación de números. */
class MaskPool(val numbers: IntArray?, val stars: List<Pair<Int, Int>>) {
    val numberCount: Long get() = numbers?.size?.toLong() ?: ALL_NUMBER_COMBOS
    val total: Long get() = numberCount * stars.size

    /** Probabilidad de que el próximo sorteo caiga dentro de este grupo. */
    val probability: Double get() = total.toDouble() / (ALL_NUMBER_COMBOS * ALL_STAR_COMBOS)
}

/**
 * [tempNum] y [tempStar]: filtro frío/caliente por posición (números ordenados de menor a mayor);
 * null en una posición = cualquiera.
 */
fun buildPool(
    numMask: String?,
    starMask: String?,
    hc: HotCold,
    tempNum: List<Temp?>,
    tempStar: List<Temp?>,
    tempCounts: Set<TempCount>? = null,
    starPairs: Set<String>? = null,
): MaskPool {
    val lmh = numMask?.split(" ")?.map { tok ->
        Level.valueOf(tok[0].toString()).ordinal * 3 + Level.valueOf(tok[1].toString()).ordinal
    }
    val anyTemp = tempNum.any { it != null }
    // Código de temperatura de cada número: 0 = C, 1 = T, 2 = F
    val tcode = IntArray(51) { n -> if (n == 0) 1 else hc.temp(n).ordinal }
    val isHot = IntArray(51) { if (tcode[it] == 0) 1 else 0 }
    val isCold = IntArray(51) { if (tcode[it] == 2) 1 else 0 }
    // allowedCount[c][f] = el reparto con c calientes y f fríos está permitido
    val allowedCount = tempCounts?.let { set ->
        Array(6) { c -> BooleanArray(6) { f -> set.any { it.c == c && it.f == f } } }
    }
    val numbers: IntArray? = if (lmh == null && !anyTemp && allowedCount == null) null else {
        // allowed[pos][n]: el número n puede ir en la posición pos
        val allowed = Array(5) { pos ->
            BooleanArray(51) { n ->
                n != 0 &&
                    (lmh == null || Mask.dec(n).ordinal * 3 + Mask.uni(n).ordinal == lmh[pos]) &&
                    (tempNum.getOrNull(pos) == null || hc.temp(n) == tempNum[pos])
            }
        }
        val a0 = allowed[0]; val a1 = allowed[1]; val a2 = allowed[2]; val a3 = allowed[3]; val a4 = allowed[4]
        val out = ArrayList<Int>()
        for (a in 1..46) {
            if (!a0[a]) continue
            for (b in a + 1..47) {
                if (!a1[b]) continue
                for (c in b + 1..48) {
                    if (!a2[c]) continue
                    for (d in c + 1..49) {
                        if (!a3[d]) continue
                        for (e in d + 1..50) {
                            if (!a4[e]) continue
                            if (allowedCount != null) {
                                val hot = isHot[a] + isHot[b] + isHot[c] + isHot[d] + isHot[e]
                                val cold = isCold[a] + isCold[b] + isCold[c] + isCold[d] + isCold[e]
                                if (!allowedCount[hot][cold]) continue
                            }
                            out += (((a * 64 + b) * 64 + c) * 64 + d) * 64 + e
                        }
                    }
                }
            }
        }
        out.toIntArray()
    }
    val stars = buildList {
        for (i in 1..11) for (j in i + 1..12) {
            if (starMask != null && Mask.star(i).name + Mask.star(j).name != starMask) continue
            if (tempStar.getOrNull(0) != null && hc.tempStar(i) != tempStar[0]) continue
            if (tempStar.getOrNull(1) != null && hc.tempStar(j) != tempStar[1]) continue
            if (starPairs != null && starPairKey(hc.tempStar(i), hc.tempStar(j)) !in starPairs) continue
            add(i to j)
        }
    }
    return MaskPool(numbers, stars)
}

data class GeneratedCombo(val numbers: List<Int>, val stars: List<Int>)

/** Genera [n] combinaciones distintas al azar dentro del grupo. [exclude] = combinaciones de 5 números ya premiadas. */
fun generate(pool: MaskPool, n: Int, exclude: Set<Int>, rnd: Random = Random.Default): List<GeneratedCombo> {
    if (pool.stars.isEmpty()) return emptyList()
    val chosen: List<Int> = if (pool.numbers != null) {
        pool.numbers.filter { it !in exclude }.shuffled(rnd).take(n)
    } else {
        val set = LinkedHashSet<Int>()
        var guard = 0
        while (set.size < n && guard++ < n * 100) {
            val p = pack((1..50).shuffled(rnd).take(5))
            if (p !in exclude) set += p
        }
        set.toList()
    }
    return chosen.map { p ->
        val (s1, s2) = pool.stars.random(rnd)
        GeneratedCombo(unpack(p), listOf(s1, s2))
    }
}

/** Reparto de calientes / templados / fríos entre los 5 números. */
data class TempCount(val c: Int, val t: Int, val f: Int) {
    val label: String get() = "${c}C ${t}T ${f}F"
    fun encode(): String = "$c,$t,$f"

    /** Probabilidad teórica (16 calientes, 18 templados, 16 fríos). */
    val probability: Double
        get() = comb(16, c) * comb(18, t) * comb(16, f) / comb(50, 5)

    companion object {
        fun decode(s: String): TempCount? = s.split(",").mapNotNull { it.toIntOrNull() }
            .takeIf { it.size == 3 }?.let { TempCount(it[0], it[1], it[2]) }

        /** Los 21 repartos posibles, del más probable al menos. */
        val all: List<TempCount> by lazy {
            buildList { for (c in 0..5) for (t in 0..5 - c) add(TempCount(c, t, 5 - c - t)) }
                .sortedByDescending { it.probability }
        }

        fun ofMask(numMask: String): TempCount {
            val t = numMask.split(" ")
            return TempCount(t.count { it == "C" }, t.count { it == "T" }, t.count { it == "F" })
        }
    }
}

/** Pareja de temperaturas de las estrellas, sin orden: "CT", "CC", "TF"… */
fun starPairKey(a: Temp, b: Temp): String = listOf(a.name, b.name).sorted().joinToString("")

/** Las 6 parejas posibles con su probabilidad teórica (bombo de 12: 4 C, 4 T, 4 F). */
val STAR_PAIRS: List<Pair<String, Double>> = listOf(
    "CT" to 16.0 / 66, "CF" to 16.0 / 66, "FT" to 16.0 / 66,
    "CC" to 6.0 / 66, "TT" to 6.0 / 66, "FF" to 6.0 / 66,
)

internal fun comb(n: Int, k: Int): Double {
    if (k < 0 || k > n) return 0.0
    var r = 1.0
    for (i in 1..k) r = r * (n - k + i) / i
    return r
}
