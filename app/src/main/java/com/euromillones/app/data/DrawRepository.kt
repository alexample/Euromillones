package com.euromillones.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Obtiene los sorteos de la API pública (todos los resultados desde 2004)
 * y los guarda en el móvil para poder consultarlos sin conexión.
 *
 * La primera vez descarga todos los años; después solo el año en curso,
 * así cada actualización es rápida.
 */
class DrawRepository(context: Context) {

    companion object {
        const val BASE_URL = "https://euromillions.api.pedromealha.dev"
        const val FIRST_YEAR = 2004
    }

    private val cacheFile = File(context.filesDir, "draws.json")

    suspend fun loadCache(): List<Draw> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        runCatching { parseDraws(cacheFile.readText()) }.getOrDefault(emptyList())
            .sortedByDescending { it.date }
    }

    suspend fun refresh(
        current: List<Draw>,
        onProgress: (String) -> Unit,
    ): List<Draw> = withContext(Dispatchers.IO) {
        val thisYear = LocalDate.now().year
        val startYear = if (current.isEmpty()) FIRST_YEAR else current.maxOf { it.date }.year
        val byDate = current.associateBy { it.date }.toMutableMap()

        for (year in startYear..thisYear) {
            onProgress("Descargando $year…")
            val body = httpGet("$BASE_URL/v1/draws?year=$year") ?: continue
            parseDraws(body).forEach { byDate[it.date] = it }
            save(byDate.values) // se guarda año a año por si se corta la conexión
        }
        byDate.values.sortedByDescending { it.date }
    }

    private fun save(draws: Collection<Draw>) {
        val arr = JSONArray()
        draws.forEach { d ->
            arr.put(
                JSONObject()
                    .put("draw_id", d.id)
                    .put("date", d.date.toString())
                    .put("numbers", JSONArray(d.numbers))
                    .put("stars", JSONArray(d.stars))
                    .apply { d.hasWinner?.let { put("has_winner", it) } }
            )
        }
        cacheFile.writeText(arr.toString())
    }

    /** Devuelve null si el año no tiene sorteos (404). */
    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("El servidor respondió $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** Lee tanto la respuesta de la API como el fichero de caché (mismo formato). */
internal fun parseDraws(text: String): List<Draw> {
    val t = text.trim()
    val arr: JSONArray = if (t.startsWith("[")) {
        JSONArray(t)
    } else {
        val o = JSONObject(t)
        o.optJSONArray("draws") ?: o.optJSONArray("data") ?: JSONArray()
    }
    val out = ArrayList<Draw>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val date = runCatching { LocalDate.parse(o.optString("date").take(10)) }.getOrNull() ?: continue
        val numbers = o.optJSONArray("numbers").toInts()
        val stars = o.optJSONArray("stars").toInts()
        if (numbers.size != 5 || stars.size != 2) continue
        out += Draw(
            id = o.optInt("draw_id", o.optInt("id", 0)),
            date = date,
            numbers = numbers,
            stars = stars,
            hasWinner = if (o.has("has_winner")) o.optBoolean("has_winner") else null,
        )
    }
    return out
}

private fun JSONArray?.toInts(): List<Int> {
    if (this == null) return emptyList()
    val list = ArrayList<Int>(length())
    for (i in 0 until length()) {
        get(i).toString().trim().toIntOrNull()?.let { list += it }
    }
    return list.sorted()
}
