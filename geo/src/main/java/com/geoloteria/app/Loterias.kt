package com.geoloteria.app

import android.content.Context
import org.json.JSONArray

/**
 * Números premiados en el Sorteo de Navidad (1º, 2º, 3º y los dos 4º) y en el del Niño (1º, 2º y 3º;
 * el Niño no tiene cuarto premio). Datos oficiales de Loterías y Apuestas del Estado desde 2007/2008;
 * de antes solo se conocen aquí los Gordos de Navidad (desde 1920).
 */
object Loterias {
    private var prizes: Map<String, List<String>> = emptyMap()
    var desdeCompleto = ""
        private set

    private val NOMBRE = mapOf("1" to "1º premio (Gordo)", "2" to "2º premio", "3" to "3º premio", "4" to "4º premio")

    @Synchronized
    fun load(ctx: Context) {
        if (prizes.isNotEmpty()) return
        val arr = JSONArray(ctx.assets.open("loterias.json").bufferedReader(Charsets.UTF_8).readText())
        val m = HashMap<String, MutableList<String>>()
        var minFull = 9999
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sorteo = o.getString("sorteo")
            val y = o.getInt("anyo")
            val p = o.getJSONObject("premios")
            if (p.has("2")) minFull = minOf(minFull, y)
            for (k in p.keys()) {
                val a = p.getJSONArray(k)
                for (j in 0 until a.length()) {
                    val n = a.getString(j).padStart(5, '0')
                    val nombre = if (k == "1" && sorteo == "Niño") "1º premio" else NOMBRE[k] ?: "$k º premio"
                    m.getOrPut(n) { ArrayList() } += "$nombre de${if (sorteo == "Niño") "l Niño" else " Navidad"} $y"
                }
            }
        }
        prizes = m
        desdeCompleto = minFull.toString()
    }

    /** "2º premio de Navidad 2019" (o varios separados por « · ») si el número fue premiado; "" si no. */
    fun info(number: String): String = prizes[number]?.joinToString(" · ").orEmpty()
    fun isPrized(number: String): Boolean = prizes.containsKey(number)

    /** Números distintos premiados (para lo esperado: distintos / 100.000 por número). */
    val distinct: Int get() = prizes.size
    val numbers: Set<String> get() = prizes.keys
}
