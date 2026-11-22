package com.geoloteria.app

import android.content.Context
import java.security.MessageDigest
import kotlin.random.Random

/** Un evento histórico de la Wikipedia en español. */
data class HistEvent(
    val index: Int,
    val date: String,     // aaaa-mm-dd (años antes de Cristo con signo: -0044-03-15)
    val type: Char,       // A = acontecimiento, N = nacimiento, M = fallecimiento
    val text: String,
    val title: String,    // artículo de Wikipedia
) {
    val url: String get() = "https://es.wikipedia.org/wiki/" + title.replace(' ', '_')
    val year: Int get() = Events.yearOf(date)
}

/**
 * EVENTOS HISTÓRICOS
 * Todos los del feed "Un día como hoy" de la Wikipedia en español (acontecimientos, nacimientos y
 * fallecimientos de los 366 días del año). Se guardan en memoria como bytes + posiciones de cada línea
 * (más de 100.000 eventos sin gastar mucha memoria); el texto se lee solo cuando hace falta.
 */
object Events {
    private var data = ByteArray(0)
    private var starts = IntArray(0)
    /** Año, mes y tipo de cada evento (para la estadística). */
    var years = IntArray(0); private set
    var months = ByteArray(0); private set
    var types = CharArray(0); private set
    /** SHA-256 del fichero de eventos (huella del conjunto de datos). */
    var hash: String = ""; private set

    val size: Int get() = starts.size

    @Synchronized
    fun load(ctx: Context) {
        if (starts.isNotEmpty()) return
        val bytes = runCatching { ctx.assets.open("eventos.tsv").use { it.readBytes() } }.getOrNull() ?: return
        hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val st = ArrayList<Int>(150_000)
        var i = 0
        while (i < bytes.size) {
            st += i
            while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
            i++
        }
        if (st.isNotEmpty() && st.last() >= bytes.size) st.removeAt(st.lastIndex)
        data = bytes
        starts = st.toIntArray()
        val y = IntArray(starts.size)
        val m = ByteArray(starts.size)
        val t = CharArray(starts.size)
        for (k in starts.indices) {
            val (date, type) = head(k)
            y[k] = yearOf(date)
            m[k] = monthOf(date).toByte()
            t[k] = type
        }
        years = y; months = m; types = t
    }

    private fun lineEnd(k: Int): Int {
        var e = starts[k]
        while (e < data.size && data[e] != '\n'.code.toByte()) e++
        return e
    }

    /** Solo fecha y tipo (rápido, sin leer el texto). */
    private fun head(k: Int): Pair<String, Char> {
        val s = starts[k]
        var e = s
        while (e < data.size && data[e] != '\t'.code.toByte()) e++
        val date = String(data, s, e - s, Charsets.UTF_8)
        val type = if (e + 1 < data.size) data[e + 1].toInt().toChar() else 'A'
        return date to type
    }

    fun get(k: Int): HistEvent? {
        if (k !in starts.indices) return null
        val s = starts[k]
        val p = String(data, s, lineEnd(k) - s, Charsets.UTF_8).split('\t')
        if (p.size < 4) return null
        return HistEvent(k, p[0], p[1].firstOrNull() ?: 'A', p[2], p[3])
    }

    fun random(rnd: Random): Int = if (size == 0) -1 else rnd.nextInt(size)

    fun dateOf(k: Int): String = if (k in starts.indices) head(k).first else ""

    fun yearOf(date: String): Int = date.dropLast(6).toIntOrNull() ?: 0
    fun monthOf(date: String): Int = date.takeLast(5).take(2).toIntOrNull() ?: 0

    /** "1967-03-14" → "14/03/1967"; "-0044-03-15" → "15/03/44 a. C." */
    fun showDate(date: String): String {
        if (date.length < 10) return date
        val y = yearOf(date)
        val md = date.takeLast(5)
        val dm = md.takeLast(2) + "/" + md.take(2)
        return if (y < 0) "$dm/${-y} a. C." else "$dm/$y"
    }

    fun typeName(t: Char) = when (t) {
        'N' -> "Nacimientos"
        'M' -> "Fallecimientos"
        else -> "Acontecimientos"
    }

    /** Siglo en números romanos: 1967 → "Siglo XX"; -44 → "Siglo I a. C." */
    fun century(y: Int): String {
        val c = if (y > 0) (y - 1) / 100 + 1 else (-y) / 100 + 1
        return "Siglo " + roman(c) + if (y <= 0) " a. C." else ""
    }

    private fun roman(n: Int): String {
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }
}
