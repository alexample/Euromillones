package com.geoloteria.app

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Extras de cada número: una de las 10.000 palabras más comunes del español (que existen en el diccionario)
 * y una fecha entre el 01/01/1940 y el 31/12/2026, ambas sacadas del mismo azar solar.
 */
object Words {
    private var words: Array<String> = emptyArray()
    val FIRST_DAY = LocalDate.of(1940, 1, 1).toEpochDay()
    val LAST_DAY = LocalDate.of(2026, 12, 31).toEpochDay()
    val DAYS: Int get() = (LAST_DAY - FIRST_DAY + 1).toInt()
    private var indexOf: Map<String, Int> = emptyMap()

    /** Posición de una palabra en el diccionario (-1 si no está). */
    fun index(w: String): Int = indexOf[w] ?: -1
    fun at(i: Int): String = words.getOrElse(i) { "" }

    /** Día (0 = 01/01/1940) de una fecha aaaa-mm-dd; -1 si no es válida o está fuera de rango. */
    fun dayIndex(iso: String): Int = runCatching {
        val d = LocalDate.parse(iso).toEpochDay()
        if (d in FIRST_DAY..LAST_DAY) (d - FIRST_DAY).toInt() else -1
    }.getOrDefault(-1)

    fun dayAt(i: Int): LocalDate = LocalDate.ofEpochDay(FIRST_DAY + i)
    private val SHOW = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    val size: Int get() = words.size

    @Synchronized
    fun load(ctx: Context) {
        if (words.isNotEmpty()) return
        words = ctx.assets.open("palabras.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
            l.map { it.trim() }.filter { it.isNotEmpty() }.toList().toTypedArray()
        }
        indexOf = HashMap<String, Int>(words.size * 2).also { m -> words.forEachIndexed { i, w -> m[w] = i } }
    }

    fun word(rnd: Random): String = if (words.isEmpty()) "" else words[rnd.nextInt(words.size)]

    /** Fecha al azar (todos los días con la misma probabilidad), en formato ISO aaaa-mm-dd. */
    fun date(rnd: Random): String = LocalDate.ofEpochDay(rnd.nextLong(FIRST_DAY, LAST_DAY + 1)).toString()

    /** Añade palabra y evento histórico (con su fecha) a un número recién generado. */
    fun decorate(d: Draw, rnd: Random): Draw {
        val w = word(rnd)
        val e = Events.random(rnd)
        // Combinación de Euromillones con el mismo azar, cruzada con todos los sorteos reales
        val (nums, stars) = EuroHistory.randomCombo(rnd)
        val m = EuroHistory.best(nums, stars)
        val info = if (m != null && m.hits >= EuroHistory.MIN_HITS) {
            "${m.hits} aciertos (${m.numbers} números + ${m.stars} estrellas) en " +
                m.draws.joinToString(", ") { (id, date) -> "el sorteo nº $id del ${Events.showDate(date)}" }
        } else ""
        return d.copy(
            word = w, event = e, date = Events.dateOf(e),
            euro = EuroHistory.format(nums, stars), euroHits = m?.hits ?: -1, euroInfo = info,
        )
    }

    /** "1967-03-14" → "14/03/1967" (años antes de Cristo: "15/03/44 a. C.") */
    fun show(iso: String): String = Events.showDate(iso)
}
