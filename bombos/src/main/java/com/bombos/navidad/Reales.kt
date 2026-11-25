package com.bombos.navidad

import android.content.Context
import org.json.JSONArray

/**
 * Premios reales para comparar: Navidad (1º, 2º, 3º y 4º desde 2007; Gordos desde 1920) y Niño (1º-3º desde 2008),
 * datos oficiales de Loterías y Apuestas del Estado.
 */
object Reales {
    private var mapa: Map<Int, List<String>> = emptyMap()

    @Synchronized
    fun load(ctx: Context) {
        if (mapa.isNotEmpty()) return
        val arr = runCatching { JSONArray(ctx.assets.open("reales.json").bufferedReader().readText()) }.getOrNull() ?: return
        val m = HashMap<Int, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.getString("sorteo"); val y = o.getInt("anyo"); val p = o.getJSONObject("premios")
            for (k in p.keys()) {
                val a = p.getJSONArray(k)
                for (j in 0 until a.length()) {
                    val n = a.getString(j).toIntOrNull() ?: continue
                    val nombre = if (k == "1") (if (s == "Niño") "1º" else "Gordo") else "${k}º"
                    m.getOrPut(n) { ArrayList() } += "$nombre real de${if (s == "Niño") "l Niño" else " Navidad"} $y"
                }
            }
        }
        mapa = m
    }

    fun de(n: Int): List<String> = mapa[n].orEmpty()
}
