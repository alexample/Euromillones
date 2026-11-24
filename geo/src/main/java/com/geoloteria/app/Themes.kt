package com.geoloteria.app

import android.content.Context

/**
 * Palabras del AZAR (suerte, fortuna, sorteo, ganar…) y del DINERO (dinero, banco, herencia, millonario…),
 * sacadas de las 10.000 palabras que acompañan a cada número (assets/temas.txt).
 */
object Themes {
    const val AZAR = "azar"
    const val DINERO = "dinero"
    private var map: Map<String, String> = emptyMap()

    @Synchronized
    fun load(ctx: Context) {
        if (map.isNotEmpty()) return
        map = ctx.assets.open("temas.txt").bufferedReader(Charsets.UTF_8).useLines { l ->
            l.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { x -> x.split(";").takeIf { it.size == 2 }?.let { it[1].trim() to it[0].trim() } }
                .toMap()
        }
    }

    /** "azar", "dinero" o null. */
    fun of(word: String): String? = map[word]

    fun words(theme: String?): List<String> = map.filter { theme == null || it.value == theme }.keys.toList()
    fun count(theme: String): Int = map.count { it.value == theme }

    fun icon(theme: String?): String = when (theme) { AZAR -> "🍀"; DINERO -> "💰"; else -> "" }
    fun label(theme: String?): String = when (theme) { AZAR -> "azar y suerte"; DINERO -> "dinero"; else -> "" }
}
