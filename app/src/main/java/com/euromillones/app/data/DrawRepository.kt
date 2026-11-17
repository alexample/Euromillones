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
 * Descarga todos los sorteos desde la base de datos del propio repositorio
 * (data/draws.json, que una tarea de GitHub actualiza tras cada sorteo)
 * y los guarda en el móvil para poder consultarlos sin conexión.
 */
class DrawRepository(context: Context) {

    companion object {
        const val DATA_URL =
            "https://raw.githubusercontent.com/alexample/Euromillones/main/data/draws.json"
    }

    private val cacheFile = File(context.filesDir, "draws.json")

    suspend fun loadCache(): List<Draw> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        runCatching { parseDraws(cacheFile.readText()) }.getOrDefault(emptyList())
            .sortedByDescending { it.date }
    }

    suspend fun refresh(onProgress: (String) -> Unit): List<Draw> = withContext(Dispatchers.IO) {
        onProgress("Descargando resultados…")
        val body = httpGet("$DATA_URL?t=${System.currentTimeMillis() / 60_000}")
        val draws = parseDraws(body)
        if (draws.isEmpty()) throw IOException("los datos recibidos están vacíos")
        cacheFile.writeText(body)
        draws.sortedByDescending { it.date }
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("el servidor respondió $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
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
            jackpot = o.optString("jackpot").takeIf { it.isNotBlank() && it != "null" },
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
