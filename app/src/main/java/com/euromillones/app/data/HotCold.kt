package com.euromillones.app.data

import java.time.LocalDate

/**
 * NÚMEROS CALIENTES Y FRÍOS
 * Con los últimos [window] sorteos se ordenan los números por veces que han salido:
 *   C (calientes) = los 16 que más · T (templados) = los 18 del medio · F (fríos) = los 16 que menos
 * Estrellas: tercios del bombo (con 12 estrellas: 4 C, 4 T, 4 F). Empates: gana el número menor.
 */
enum class Temp { C, T, F }

class HotCold(
    val hot: Set<Int>,
    val cold: Set<Int>,
    val hotStars: Set<Int>,
    val coldStars: Set<Int>,
) {
    fun temp(n: Int): Temp = if (n in hot) Temp.C else if (n in cold) Temp.F else Temp.T
    fun tempStar(s: Int): Temp = if (s in hotStars) Temp.C else if (s in coldStars) Temp.F else Temp.T
    fun numbersMask(nums: List<Int>): String = nums.sorted().joinToString(" ") { temp(it).name }
    fun starsMask(st: List<Int>): String = st.sorted().joinToString("") { tempStar(it).name }

    companion object {
        fun from(recent: List<Draw>, starPool: Int = 12): HotCold {
            val cn = IntArray(51)
            val cs = IntArray(13)
            recent.forEach { d ->
                d.numbers.forEach { if (it in 1..50) cn[it]++ }
                d.stars.forEach { if (it in 1..12) cs[it]++ }
            }
            val order = (1..50).sortedWith(compareByDescending<Int> { cn[it] }.thenBy { it })
            val sOrder = (1..starPool).sortedWith(compareByDescending<Int> { cs[it] }.thenBy { it })
            val third = starPool / 3
            return HotCold(
                order.take(16).toSet(),
                order.takeLast(16).toSet(),
                sOrder.take(third).toSet(),
                sOrder.takeLast(third).toSet(),
            )
        }
    }
}

val HOT_WINDOWS = listOf(20, 50, 100)
const val DEFAULT_HOT_WINDOW = 50

/** Clasificación de cara al próximo sorteo. draws: el más reciente primero. */
fun currentHotCold(draws: List<Draw>, window: Int): HotCold = HotCold.from(draws.take(window), 12)

/**
 * Máscara frío/caliente que tenía cada sorteo, calculada con los [window] sorteos anteriores a él.
 * draws: el más reciente primero. Los primeros sorteos del histórico (sin ventana completa) no tienen.
 */
fun historicTempMasks(draws: List<Draw>, window: Int): Map<LocalDate, Pair<String, String>> {
    val out = HashMap<LocalDate, Pair<String, String>>()
    for (i in draws.indices) {
        val end = i + 1 + window
        if (end > draws.size) break
        val d = draws[i]
        val hc = HotCold.from(draws.subList(i + 1, end), Mask.starPool(d.date))
        out[d.date] = hc.numbersMask(d.numbers) to hc.starsMask(d.stars)
    }
    return out
}
