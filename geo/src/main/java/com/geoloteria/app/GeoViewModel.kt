package com.geoloteria.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

data class Hit(val draw: Draw, val time: LocalTime)

data class GeoState(
    val ready: Boolean = false,
    val nucleos: Int = 0,
    val last: Draw? = null,
    val recent: List<Draw> = emptyList(),
    val hits: List<Hit> = emptyList(),
    val total: Long = 0,
    val hitCount: Long = 0,
    val running: Boolean = false,
    val speed: Int = 5,
    val stopOnHit: Boolean = false,
    val notify: Boolean = true,
    val radius: RadiusConfig = RadiusConfig(),
    val expectedRate: Double? = null,
    val historySize: Int = 0,   // números guardados para exportar
    val message: String? = null,
)

const val HISTORY_MAX = 20_000

val SPEEDS = listOf(1, 5, 20, 100)

class GeoViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(GeoState())
    val state: StateFlow<GeoState> = _state.asStateFlow()

    private var geo: Geo? = null
    private var job: Job? = null
    private var nextId = 1L
    private var lastAlertAt = 0L

    private var estimateJob: Job? = null

    /** Todo lo generado o importado (el más reciente primero), para exportar. */
    private val history = ArrayDeque<Draw>()

    private fun addHistory(items: List<Draw>): Int = synchronized(history) {
        items.forEach { history.addFirst(it) }
        while (history.size > HISTORY_MAX) history.removeLast()
        history.size
    }

    init {
        createChannel()
        _state.update { it.copy(radius = RadiusConfig.load(app)) }
        viewModelScope.launch(Dispatchers.Default) {
            val g = Geo(app)
            geo = g
            _state.update { it.copy(ready = true, nucleos = g.nucleos.size) }
            estimate()
        }
    }

    fun generateOne() {
        val g = geo ?: return
        viewModelScope.launch(Dispatchers.Default) { record(g.draw(nextId++, _state.value.radius)) }
    }

    // ---------- Radio de acción ----------

    fun setRadius(tier: Int, km: Double) {
        val c = _state.value.radius.with(tier, km)
        _state.update { it.copy(radius = c, expectedRate = null) }
        RadiusConfig.save(app, c)
        estimate()
    }

    fun resetRadius() {
        val c = RadiusConfig()
        _state.update { it.copy(radius = c, expectedRate = null) }
        RadiusConfig.save(app, c)
        estimate()
    }

    /** Tasa de aciertos esperada con la configuración actual (2.000 puntos de prueba). */
    private fun estimate() {
        val g = geo ?: return
        estimateJob?.cancel()
        estimateJob = viewModelScope.launch(Dispatchers.Default) {
            delay(300)
            val cfg = _state.value.radius
            val rate = g.estimateRate(cfg)
            if (_state.value.radius == cfg) _state.update { it.copy(expectedRate = rate) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---------- Excel ----------

    fun exportExcel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _state.value
            val header = listOf("Número", "Latitud", "Longitud", "En núcleo", "Ciudad", "Comunidad",
                "Habitantes", "Distancia (km)", "Radio (km)")
            fun row(d: Draw): List<Any?> = listOf(
                d.number, d.lat, d.lon, if (d.isHit) "Sí" else "No", d.nearest.name, d.nearest.region,
                d.nearest.population, round2(d.distanceKm), round2(d.radiusKm),
            )
            val config = listOf(listOf("Habitantes", "Radio (km)")) +
                RadiusConfig.TIER_LABEL.mapIndexed { i, l -> listOf(l, s.radius.km[i]) }
            val result = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    Xlsx.write(
                        out,
                        listOf(
                            "Aciertos" to listOf(header + "Hora") + s.hits.map { h -> row(h.draw) + h.time.format(TIME) },
                            "Generados" to listOf(header) + synchronized(history) { history.toList() }.map { row(it) },
                            "Radios" to config,
                        ),
                    )
                } ?: error("no se pudo abrir el fichero")
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        { "Exportados ${s.hits.size} aciertos y ${s.historySize} números a Excel." },
                        { e -> "Error al exportar: ${e.message}" },
                    ),
                )
            }
        }
    }

    /**
     * Importa un Excel con columnas Número, Latitud y Longitud (en cualquier hoja y orden).
     * Cada punto se vuelve a comprobar con los radios actuales.
     */
    fun importExcel(uri: Uri) {
        val g = geo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val sheets = app.contentResolver.openInputStream(uri)?.use { Xlsx.read(it) }
                    ?: error("no se pudo abrir el fichero")
                val cfg = _state.value.radius
                val seen = HashSet<String>()
                val draws = ArrayList<Draw>()
                for (rows in sheets) {
                    val hi = rows.indexOfFirst { r -> r.any { norm(it) == "numero" } && r.any { norm(it) == "latitud" } }
                    if (hi < 0) continue
                    val h = rows[hi].map { norm(it) }
                    val cn = h.indexOf("numero")
                    val cla = h.indexOf("latitud")
                    val clo = h.indexOf("longitud")
                    if (cla < 0 || clo < 0) continue
                    for (r in rows.drop(hi + 1)) {
                        val num = r.getOrNull(cn)?.trim()?.substringBefore(".")?.filter { it.isDigit() } ?: continue
                        val lat = r.getOrNull(cla)?.replace(",", ".")?.trim()?.toDoubleOrNull() ?: continue
                        val lon = r.getOrNull(clo)?.replace(",", ".")?.trim()?.toDoubleOrNull() ?: continue
                        if (num.isEmpty() || num.length > 5) continue
                        val key = "$num|$lat|$lon"
                        if (!seen.add(key)) continue
                        draws += g.evaluate(nextId++, num.padStart(5, '0'), lat, lon, cfg)
                    }
                }
                draws
            }
            result.onSuccess { draws ->
                val now = LocalTime.now()
                val newHits = draws.filter { it.isHit }
                val size = addHistory(draws)
                _state.update { s ->
                    s.copy(
                        historySize = size,
                        hits = (newHits.reversed().map { Hit(it, now) } + s.hits).take(500),
                        hitCount = s.hitCount + newHits.size,
                        total = s.total + draws.size,
                        message = if (draws.isEmpty()) "No encontré filas con Número, Latitud y Longitud."
                        else "Importados ${draws.size} números: ${newHits.size} en núcleo urbano (con los radios actuales).",
                    )
                }
            }.onFailure { e -> _state.update { it.copy(message = "Error al importar: ${e.message}") } }
        }
    }

    fun toggleAuto() {
        if (_state.value.running) stop() else start()
    }

    private fun start() {
        val g = geo ?: return
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && _state.value.running) {
                val speed = _state.value.speed
                val tickMs = if (speed >= 10) 100L else 1000L / speed
                val perTick = if (speed >= 10) speed / 10 else 1
                repeat(perTick) {
                    if (_state.value.running) record(g.draw(nextId++, _state.value.radius))
                }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        _state.update { it.copy(running = false) }
        job?.cancel()
    }

    fun setSpeed(s: Int) = _state.update { it.copy(speed = s) }
    fun setStopOnHit(v: Boolean) = _state.update { it.copy(stopOnHit = v) }
    fun setNotify(v: Boolean) = _state.update { it.copy(notify = v) }
    fun clear() {
        synchronized(history) { history.clear() }
        _state.update {
            it.copy(last = null, recent = emptyList(), hits = emptyList(), historySize = 0, total = 0, hitCount = 0)
        }
    }

    private fun record(d: Draw) {
        val size = addHistory(listOf(d))
        _state.update { s ->
            s.copy(
                last = d,
                recent = (listOf(d) + s.recent).take(50),
                historySize = size,
                hits = if (d.isHit) (listOf(Hit(d, LocalTime.now())) + s.hits).take(500) else s.hits,
                total = s.total + 1,
                hitCount = s.hitCount + if (d.isHit) 1 else 0,
            )
        }
        if (d.isHit) {
            alert(d)
            if (_state.value.stopOnHit) stop()
        }
    }

    // ---------- Avisos ----------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Aciertos en núcleo urbano", NotificationManager.IMPORTANCE_HIGH)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun alert(d: Draw) {
        // Como mucho un aviso cada 2 segundos para no saturar a alta velocidad
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < 2000) return
        lastAlertAt = now

        @Suppress("DEPRECATION")
        app.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

        if (!_state.value.notify) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("¡${d.number} en ${d.nearest.name}!")
            .setContentText("A ${"%.2f".format(d.distanceKm)} km del centro (${d.nearest.region})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(app).notify(d.id.toInt(), n)
    }

    companion object {
        const val CHANNEL = "aciertos"
        private val TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

private fun round2(x: Double) = Math.round(x * 100) / 100.0

/** Minúsculas y sin tildes, para reconocer cabeceras ("Número" = "numero"). */
internal fun norm(x: String): String =
    java.text.Normalizer.normalize(x.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
