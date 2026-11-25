package com.quinielas.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Todo lo que la app sabe: histórico de quinielas, ligas, equipos y próximas jornadas. */
class Base(
    val quinielas: List<Quiniela>, val ligas: List<Liga>, val proximas: List<Prox>, val actualizado: String,
    mapa: Map<String, String>, val plantillas: Plantillas,
) {
    val estadistica = Estadistica(quinielas)
    val tendencias = Tendencias(ligas)
    val equipos = Equipos(mapa, tendencias.equipos)
}

/**
 * Lee los datos de Internet (los recoge un robot en GitHub dos veces al día de la web de Loterías y Apuestas
 * del Estado y de football-data.co.uk) y, si no hay conexión, usa la última copia o la que viene en la app.
 */
object Datos {
    private const val URL_BASE = "https://raw.githubusercontent.com/alexample/Euromillones/main/data/"
    private val FICHEROS = listOf("quinielas.json", "ligas.json", "equipos.json", "jornada.json", "plantillas.json")

    private val _base = MutableStateFlow<Base?>(null)
    val base: StateFlow<Base?> = _base.asStateFlow()
    private val _estado = MutableStateFlow("Cargando…")
    val estado: StateFlow<String> = _estado.asStateFlow()

    suspend fun cargar(ctx: Context, red: Boolean) = withContext(Dispatchers.IO) {
        if (red) {
            _estado.value = "Descargando resultados…"
            var ok = 0
            for (f in FICHEROS) {
                runCatching {
                    val c = URL("$URL_BASE$f?t=${System.currentTimeMillis() / 60_000}").openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    val txt = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    if (txt.length > 10) { File(ctx.filesDir, f).writeText(txt); ok++ }
                }
            }
            _estado.value = if (ok == FICHEROS.size) "Datos al día" else "Sin conexión: uso la última copia"
        }
        fun leer(f: String): String {
            val local = File(ctx.filesDir, f)
            return if (local.exists() && local.length() > 10) local.readText() else ctx.assets.open(f).bufferedReader().readText()
        }
        val b = runCatching {
            val q = JSONArray(leer("quinielas.json")).let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val e = o.optJSONArray("e")
                    val partidos = if (e == null) emptyList() else List(e.length()) { k -> val p = e.getJSONArray(k); p.optString(0) to p.optString(1) }
                    Quiniela(o.getString("f"), o.optInt("n"), o.optString("t").ifEmpty { null }, o.getString("s"), o.optString("p15"), partidos)
                }
            }
            val l = JSONArray(leer("ligas.json")).let { a ->
                List(a.length()) { i -> val m = a.getJSONArray(i); Liga(m.getString(0), m.getInt(1), m.getString(2), m.getString(3), m.getInt(4), m.getInt(5)) }
            }
            val eq = JSONObject(leer("equipos.json")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val j = JSONObject(leer("jornada.json"))
            val prox = j.getJSONArray("proximas").let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val ps = o.getJSONArray("partidos")
                    Prox(o.getString("f"), o.optInt("n"), if (o.isNull("j")) null else o.optInt("j"),
                        List(ps.length()) { k ->
                            val p = ps.getJSONObject(k)
                            PartidoProx(p.optInt("pos"), p.optString("local"), p.optString("visitante"), p.optString("signo").trim(),
                                p.optString("marcador").trim(), p.optString("fecha"))
                        })
                }
            }
            val pl = runCatching { JSONObject(leer("plantillas.json")) }.getOrNull()?.let { o ->
                o.keys().asSequence().map { k ->
                    val t = o.getJSONObject(k); val js = t.getJSONArray("jugadores")
                    Plantilla(k, t.optInt("liga"), List(js.length()) { i ->
                        val x = js.getJSONObject(i)
                        Jugador(x.optString("n"), java.time.LocalDate.parse(x.getString("f")), x.optInt("t"), x.optInt("a"), x.optString("p"))
                    })
                }.toList()
            }.orEmpty()
            Base(q, l, prox, j.optString("actualizado"), eq, Plantillas(pl))
        }
        b.onSuccess { _base.value = it }.onFailure { _estado.value = "Error leyendo los datos: ${it.message}" }
    }
}
