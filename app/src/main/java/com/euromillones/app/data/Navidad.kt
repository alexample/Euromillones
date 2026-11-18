package com.euromillones.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * LOTERÍA DE NAVIDAD
 * Números de 5 cifras (00000-99999 desde 2011; antes la emisión era menor).
 * Máscara por cifra: L 0-2 · M 3-6 · H 7-9.
 * Frío/caliente por posición: con los últimos N Gordos, en cada cifra (D1…D5) los 3 dígitos
 * que más han salido son C, los 4 del medio T y los 3 que menos F.
 */
data class Gordo(val year: Int, val number: String)

const val NAVIDAD_TOTAL = 100_000L
val NAVIDAD_WINDOWS = listOf(10, 20, 50)
const val DEFAULT_NAVIDAD_WINDOW = 20

object Navidad {
    const val DATA_URL = "https://raw.githubusercontent.com/alexample/Euromillones/main/data/navidad.json"

    fun level(d: Int): Level = when (d) {
        in 0..2 -> Level.L
        in 3..6 -> Level.M
        else -> Level.H
    }

    fun lmh(number: String): String = number.map { level(it.digitToInt()).name }.joinToString("")

    fun parse(text: String): List<Gordo> {
        val arr = JSONArray(text)
        val out = ArrayList<Gordo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("number").filter { it.isDigit() }
            if (n.isEmpty() || n.length > 5) continue
            out += Gordo(o.optInt("year"), n.padStart(5, '0'))
        }
        return out.sortedWith(compareByDescending<Gordo> { it.year }.thenBy { it.number })
    }
}

/** temps[pos][dígito] = 'C', 'T' o 'F'. */
class DigitHotCold(val temps: Array<CharArray>) {
    fun temp(pos: Int, digit: Int): Char = temps[pos][digit]
    fun ctf(number: String): String = number.mapIndexed { i, c -> temp(i, c.digitToInt()) }.joinToString("")
    fun digitsWith(pos: Int, t: Char): List<Int> = (0..9).filter { temps[pos][it] == t }

    companion object {
        fun from(prev: List<Gordo>): DigitHotCold = DigitHotCold(Array(5) { pos ->
            val c = IntArray(10)
            prev.forEach { c[it.number[pos].digitToInt()]++ }
            val order = (0..9).sortedWith(compareByDescending<Int> { c[it] }.thenBy { it })
            CharArray(10).also { arr ->
                order.forEachIndexed { rank, d -> arr[d] = if (rank < 3) 'C' else if (rank >= 7) 'F' else 'T' }
            }
        })
    }
}

/** Clasificación actual: los [window] Gordos más recientes. gordos: el más reciente primero. */
fun currentDigitHotCold(gordos: List<Gordo>, window: Int) = DigitHotCold.from(gordos.take(window))

/** Clasificación justo antes de cada Gordo (índice en la lista → clasificación); null si no hay ventana completa. */
fun historicDigitHotCold(gordos: List<Gordo>, window: Int): List<DigitHotCold?> =
    gordos.indices.map { i -> if (i + 1 + window > gordos.size) null else DigitHotCold.from(gordos.subList(i + 1, i + 1 + window)) }

/**
 * Filtro de un número de Navidad: en cada posición, letra L/M/H o '-' y letra C/T/F o '-'.
 * allowed[pos][dígito] = el dígito cumple los dos.
 */
fun navidadAllowed(lmh: String, ctf: String, hc: DigitHotCold): Array<BooleanArray> = Array(5) { pos ->
    BooleanArray(10) { d ->
        val l = lmh.getOrElse(pos) { '-' }
        val t = ctf.getOrElse(pos) { '-' }
        (l == '-' || Navidad.level(d).name[0] == l) && (t == '-' || hc.temp(pos, d) == t)
    }
}

/** Cuántos números cumplen el filtro: cada cifra es independiente, así que se multiplican. */
fun navidadCount(allowed: Array<BooleanArray>): Long = allowed.fold(1L) { acc, a -> acc * a.count { it } }

fun navidadGenerate(allowed: Array<BooleanArray>, n: Int, rnd: Random = Random.Default): List<String> {
    val total = navidadCount(allowed)
    if (total == 0L) return emptyList()
    val digits = allowed.map { a -> (0..9).filter { a[it] } }
    val out = LinkedHashSet<String>()
    val target = minOf(n.toLong(), total).toInt()
    var guard = 0
    while (out.size < target && guard++ < target * 200) {
        out += digits.joinToString("") { it.random(rnd).toString() }
    }
    return out.toList()
}

/** Carga los Gordos: caché del móvil → copia incluida en la app; y se actualiza desde el repositorio. */
class NavidadRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "navidad.json")

    suspend fun load(): List<Gordo> = withContext(Dispatchers.IO) {
        runCatching { if (cacheFile.exists()) Navidad.parse(cacheFile.readText()) else null }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                Navidad.parse(context.assets.open("navidad.json").bufferedReader().use { it.readText() })
            }.getOrDefault(emptyList())
    }

    suspend fun refresh(): List<Gordo> = withContext(Dispatchers.IO) {
        val conn = (URL("${Navidad.DATA_URL}?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val list = Navidad.parse(body)
            if (list.isEmpty()) throw IOException("vacío")
            cacheFile.writeText(body)
            list
        } finally {
            conn.disconnect()
        }
    }
}
