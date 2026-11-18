package com.euromillones.app.data

import kotlin.random.Random

/** Máscara elegida para generar: parte de números (5 bloques) y/o de estrellas (1 bloque). Null = libre. */
data class GenTarget(val numMask: String?, val starMask: String?) {
    val scope: MaskScope
        get() = when {
            numMask != null && starMask != null -> MaskScope.COMPLETA
            numMask != null -> MaskScope.NUMEROS
            else -> MaskScope.ESTRELLAS
        }
    val mask: String get() = listOfNotNull(numMask, starMask).joinToString(" ")

    fun encode(): String = "${numMask.orEmpty()}|${starMask.orEmpty()}"

    companion object {
        fun fromScope(scope: MaskScope, mask: String): GenTarget = when (scope) {
            MaskScope.NUMEROS -> GenTarget(mask, null)
            MaskScope.ESTRELLAS -> GenTarget(null, mask)
            MaskScope.COMPLETA -> GenTarget(mask.substringBeforeLast(" "), mask.substringAfterLast(" "))
        }

        fun decode(s: String): GenTarget {
            val (n, st) = s.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return GenTarget(n.ifBlank { null }, st.ifBlank { null })
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

/** Todas las combinaciones que cumplen la máscara. numbers = null → cualquier combinación de números. */
class MaskPool(val numbers: IntArray?, val stars: List<Pair<Int, Int>>) {
    val numberCount: Long get() = numbers?.size?.toLong() ?: 2_118_760L
    val total: Long get() = numberCount * stars.size
}

fun buildPool(t: GenTarget): MaskPool {
    val numbers = t.numMask?.let { m ->
        val target = m.split(" ").map { tok ->
            Level.valueOf(tok[0].toString()).ordinal * 3 + Level.valueOf(tok[1].toString()).ordinal
        }
        val code = IntArray(51) { n -> if (n == 0) -1 else Mask.dec(n).ordinal * 3 + Mask.uni(n).ordinal }
        val out = ArrayList<Int>()
        for (a in 1..46) {
            if (code[a] != target[0]) continue
            for (b in a + 1..47) {
                if (code[b] != target[1]) continue
                for (c in b + 1..48) {
                    if (code[c] != target[2]) continue
                    for (d in c + 1..49) {
                        if (code[d] != target[3]) continue
                        for (e in d + 1..50) {
                            if (code[e] == target[4]) out += (((a * 64 + b) * 64 + c) * 64 + d) * 64 + e
                        }
                    }
                }
            }
        }
        out.toIntArray()
    }
    val stars = buildList {
        for (i in 1..11) for (j in i + 1..12) {
            if (t.starMask == null || Mask.star(i).name + Mask.star(j).name == t.starMask) add(i to j)
        }
    }
    return MaskPool(numbers, stars)
}

data class GeneratedCombo(val numbers: List<Int>, val stars: List<Int>)

/**
 * Genera [n] combinaciones distintas al azar dentro de la máscara.
 * [exclude] = combinaciones de 5 números (empaquetadas) que no se quieren repetir (las ya premiadas).
 */
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
