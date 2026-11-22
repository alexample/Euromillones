package com.geoloteria.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** Resultado de cruzar una combinación con el histórico. */
data class EuroMatch(val hits: Int, val numbers: Int, val stars: Int, val draws: List<Pair<Int, String>>)

/**
 * HISTÓRICO DE EUROMILLONES (el mismo de la app de Euromillones: data/draws.json del repositorio).
 * Viene incluido en la app y se actualiza desde el repositorio al abrirla y cada 12 h.
 * El cruce usa máscaras de bits: 5 números = bits de un Long, 2 estrellas = bits de un Int.
 */
object EuroHistory {
    private const val URL_DATA = "https://raw.githubusercontent.com/alexample/Euromillones/main/data/draws.json"
    /** Solo se anuncia el cruce si hay más de cuatro aciertos (números + estrellas). */
    const val MIN_HITS = 5

    private class D(val id: Int, val date: String, val nums: Long, val stars: Int)

    @Volatile private var draws: Array<D> = emptyArray()
    val size: Int get() = draws.size
    val lastDate: String get() = draws.maxByOrNull { it.date }?.date ?: ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val cache = File(ctx.filesDir, "euro_draws.json")
        runCatching { parse(if (cache.exists()) cache.readText() else ctx.assets.open("euro_draws.json").bufferedReader().readText()) }
        scope.launch {
            while (isActive) {
                runCatching {
                    val c = (URL("$URL_DATA?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection)
                        .apply { connectTimeout = 15_000; readTimeout = 30_000 }
                    try {
                        if (c.responseCode == 200) {
                            val body = c.inputStream.bufferedReader().readText()
                            parse(body)
                            cache.writeText(body)
                        }
                    } finally { c.disconnect() }
                }
                delay(12 * 60 * 60 * 1000L)
            }
        }
    }

    private fun parse(text: String) {
        val arr = JSONArray(text)
        val out = ArrayList<D>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optJSONArray("numbers") ?: continue
            val s = o.optJSONArray("stars") ?: continue
            var nm = 0L
            for (k in 0 until n.length()) nm = nm or (1L shl n.getInt(k))
            var sm = 0
            for (k in 0 until s.length()) sm = sm or (1 shl s.getInt(k))
            out += D(o.optInt("draw_id"), o.optString("date"), nm, sm)
        }
        if (out.isNotEmpty()) draws = out.sortedByDescending { it.date }.toTypedArray()
    }

    /** Combinación al azar: 5 números distintos (1-50) y 2 estrellas (1-12), ordenados. */
    fun randomCombo(rnd: Random): Pair<List<Int>, List<Int>> {
        val nums = (1..50).toMutableList()
        for (i in 0 until 5) { val j = i + rnd.nextInt(50 - i); val t = nums[i]; nums[i] = nums[j]; nums[j] = t }
        val st = (1..12).toMutableList()
        for (i in 0 until 2) { val j = i + rnd.nextInt(12 - i); val t = st[i]; st[i] = st[j]; st[j] = t }
        return nums.take(5).sorted() to st.take(2).sorted()
    }

    fun format(nums: List<Int>, stars: List<Int>) =
        nums.joinToString(" ") { "%02d".format(it) } + " + " + stars.joinToString(" ") { "%02d".format(it) }

    /** Mejor resultado en el histórico (máximo de aciertos y en qué sorteos, del más reciente al más antiguo). */
    fun best(nums: List<Int>, stars: List<Int>): EuroMatch? {
        val ds = draws
        if (ds.isEmpty()) return null
        var nm = 0L
        nums.forEach { nm = nm or (1L shl it) }
        var sm = 0
        stars.forEach { sm = sm or (1 shl it) }
        var best = -1
        var bn = 0
        var bs = 0
        val where = ArrayList<Pair<Int, String>>()
        for (d in ds) {
            val n = java.lang.Long.bitCount(nm and d.nums)
            val s = Integer.bitCount(sm and d.stars)
            val h = n + s
            if (h > best) { best = h; bn = n; bs = s; where.clear() }
            if (h == best && where.size < 5) where += d.id to d.date
        }
        return EuroMatch(best, bn, bs, where)
    }
}
