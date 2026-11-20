package com.geoloteria.app

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Extras de cada número: una palabra del diccionario español (53.300 palabras, Hunspell)
 * y una fecha entre el 01/01/1940 y el 31/12/2026, ambas sacadas del mismo azar solar.
 */
object Words {
    private var words: Array<String> = emptyArray()
    private val FIRST_DAY = LocalDate.of(1940, 1, 1).toEpochDay()
    private val LAST_DAY = LocalDate.of(2026, 12, 31).toEpochDay()
    private val SHOW = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    val size: Int get() = words.size

    @Synchronized
    fun load(ctx: Context) {
        if (words.isNotEmpty()) return
        words = ctx.assets.open("palabras.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
            l.map { it.trim() }.filter { it.isNotEmpty() }.toList().toTypedArray()
        }
    }

    fun word(rnd: Random): String = if (words.isEmpty()) "" else words[rnd.nextInt(words.size)]

    /** Fecha al azar (todos los días con la misma probabilidad), en formato ISO aaaa-mm-dd. */
    fun date(rnd: Random): String = LocalDate.ofEpochDay(rnd.nextLong(FIRST_DAY, LAST_DAY + 1)).toString()

    /** Añade palabra y fecha a un número recién generado. */
    fun decorate(d: Draw, rnd: Random): Draw = d.copy(word = word(rnd), date = date(rnd))

    /** "1967-03-14" → "14/03/1967" */
    fun show(iso: String): String = runCatching { LocalDate.parse(iso).format(SHOW) }.getOrDefault(iso)
}
