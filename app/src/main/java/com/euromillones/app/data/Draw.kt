package com.euromillones.app.data

import java.time.LocalDate

/** Un sorteo de Euromillones: 5 números (1-50) y 2 estrellas (1-12). */
data class Draw(
    val id: Int,
    val date: LocalDate,
    val numbers: List<Int>,
    val stars: List<Int>,
    val hasWinner: Boolean?,
    val jackpot: String? = null,
)

enum class MatchMode { ANY, ALL }

/** Filtros de búsqueda. Todo es opcional; los filtros vacíos no restringen. */
data class SearchQuery(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val numbers: Set<Int> = emptySet(),
    val stars: Set<Int> = emptySet(),
    val mode: MatchMode = MatchMode.ANY,
) {
    val isEmpty: Boolean
        get() = from == null && to == null && numbers.isEmpty() && stars.isEmpty()
}

fun Draw.hits(q: SearchQuery): Int =
    numbers.count { it in q.numbers } + stars.count { it in q.stars }

fun Draw.matches(q: SearchQuery): Boolean {
    if (q.from != null && date.isBefore(q.from)) return false
    if (q.to != null && date.isAfter(q.to)) return false
    if (q.numbers.isEmpty() && q.stars.isEmpty()) return true
    return when (q.mode) {
        // "Alguno": basta con que salga al menos uno de los elegidos
        MatchMode.ANY -> numbers.any { it in q.numbers } || stars.any { it in q.stars }
        // "Todos": tienen que salir todos los elegidos en el mismo sorteo
        MatchMode.ALL -> numbers.containsAll(q.numbers) && stars.containsAll(q.stars)
    }
}

fun List<Draw>.search(q: SearchQuery): List<Draw> = filter { it.matches(q) }
