package com.geoloteria.app

import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Hit(val draw: Draw, val time: LocalDateTime)

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
    val vibrate: Boolean = true,
    val radius: RadiusConfig = RadiusConfig(),
    val expectedRate: Double? = null,
    val historySize: Int = 0,   // números guardados (para exportar)
    val message: String? = null,
    // Avisos con filtro
    val watchNumbers: List<String> = emptyList(),   // 5 cifras = número exacto; menos = terminación
    val watchTowns: List<String> = emptyList(),     // nombre de la población
    val alerts: List<Alert> = emptyList(),
    val stopOnAlert: Boolean = false,
)

/** Un aviso que ha saltado: el número, el motivo y cuándo. */
data class Alert(val draw: Draw, val reason: String, val time: LocalDateTime)

const val ALERTS_MAX = 300

const val HISTORY_MAX = 20_000
private const val ALERT_CHANNEL = "avisos"
const val HITS_MAX = 500
val SPEEDS = listOf(1, 5, 20, 100)

/**
 * Motor de GeoLotería: vive mientras viva el proceso, no la pantalla.
 * Así el modo automático sigue en segundo plano (con [GeneratorService]) y todo se guarda en el móvil.
 */
object GeoEngine {

    private val _state = MutableStateFlow(GeoState())
    val state: StateFlow<GeoState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context
    private var geo: Geo? = null
    private var loop: Job? = null
    private var estimateJob: Job? = null
    private var nextId = 1L
    private var lastVibration = 0L
    private var dirty = false

    /** Todo lo generado o importado (el más reciente primero). */
    private val history = ArrayDeque<Draw>()

    private val dataFile get() = File(app.filesDir, "geoloteria.dat")

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences("geoloteria", Context.MODE_PRIVATE)
        _state.update {
            it.copy(
                radius = RadiusConfig.load(app),
                watchNumbers = prefs.getString("avisos_numeros", "").orEmpty().split("|").filter { x -> x.isNotBlank() },
                watchTowns = prefs.getString("avisos_poblaciones", "").orEmpty().split("|").filter { x -> x.isNotBlank() },
                stopOnAlert = prefs.getBoolean("avisos_parar", false),
            )
        }
        val ch = android.app.NotificationChannel(ALERT_CHANNEL, "Avisos de número o población",
            android.app.NotificationManager.IMPORTANCE_HIGH)
        app.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        scope.launch {
            val g = Geo(app)
            geo = g
            load(g)
            _state.update { it.copy(ready = true, nucleos = g.nucleos.size) }
            estimate()
            // Guardado automático cada 15 s si hay cambios
            while (isActive) {
                delay(15_000)
                if (dirty) save()
            }
        }
    }

    // ---------------- Generación ----------------

    fun generateOne() {
        val g = geo ?: return
        scope.launch { record(g.draw(nextId++, _state.value.radius)) }
    }

    fun toggleAuto() = if (_state.value.running) stop() else start()

    fun start() {
        val g = geo ?: return
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        GeneratorService.start(app)
        loop = scope.launch {
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
        loop?.cancel()
        GeneratorService.stop(app)
        saveAsync()
    }

    fun setSpeed(s: Int) = _state.update { it.copy(speed = s) }
    fun setStopOnHit(v: Boolean) = _state.update { it.copy(stopOnHit = v) }
    fun setVibrate(v: Boolean) = _state.update { it.copy(vibrate = v) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun clear() {
        synchronized(history) { history.clear() }
        _state.update {
            it.copy(last = null, recent = emptyList(), hits = emptyList(), alerts = emptyList(), historySize = 0, total = 0, hitCount = 0)
        }
        dirty = true
        saveAsync()
    }

    private fun addHistory(items: List<Draw>): Int = synchronized(history) {
        items.forEach { history.addFirst(it) }
        while (history.size > HISTORY_MAX) history.removeLast()
        history.size
    }

    private fun record(d: Draw) {
        val size = addHistory(listOf(d))
        _state.update { s ->
            s.copy(
                last = d,
                recent = (listOf(d) + s.recent).take(50),
                hits = if (d.isHit) (listOf(Hit(d, LocalDateTime.now())) + s.hits).take(HITS_MAX) else s.hits,
                total = s.total + 1,
                hitCount = s.hitCount + if (d.isHit) 1 else 0,
                historySize = size,
            )
        }
        dirty = true
        if (d.isHit) {
            vibrate()
            if (_state.value.stopOnHit) stop()
        }
        checkAlerts(d)
    }

    // ---------------- Avisos con filtro ----------------

    /** Motivo del aviso si el número o la población coinciden con algún filtro; null si no. */
    private fun alertReason(d: Draw): String? {
        val s = _state.value
        s.watchNumbers.firstOrNull { w -> if (w.length == 5) d.number == w else d.number.endsWith(w) }?.let { w ->
            return if (w.length == 5) "Ha salido el número $w" else "Número terminado en $w"
        }
        val town = norm(d.nearest.name)
        s.watchTowns.firstOrNull { norm(it) == town }?.let { t ->
            return "Ha salido en $t" + if (d.isHit) " (en el núcleo urbano)" else " (fuera del núcleo)"
        }
        return null
    }

    private fun checkAlerts(d: Draw) {
        val reason = alertReason(d) ?: return
        _state.update { it.copy(alerts = (listOf(Alert(d, reason, LocalDateTime.now())) + it.alerts).take(ALERTS_MAX)) }
        notifyAlert(d, reason)
        if (_state.value.stopOnAlert) stop()
    }

    private fun notifyAlert(d: Draw, reason: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val pi = android.app.PendingIntent.getActivity(
            app, 0,
            android.content.Intent(app, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val n = androidx.core.app.NotificationCompat.Builder(app, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$reason · ${d.number}")
            .setContentText(
                "${d.nearest.name} (${d.nearest.region}) a ${"%.2f".format(Locale.forLanguageTag("es-ES"), d.distanceKm)} km" +
                    if (d.isHit) " · en núcleo urbano" else "",
            )
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        androidx.core.app.NotificationManagerCompat.from(app).notify((d.id % Int.MAX_VALUE).toInt() + 1000, n)
    }

    private fun saveWatch() {
        val s = _state.value
        app.getSharedPreferences("geoloteria", Context.MODE_PRIVATE).edit()
            .putString("avisos_numeros", s.watchNumbers.joinToString("|"))
            .putString("avisos_poblaciones", s.watchTowns.joinToString("|"))
            .putBoolean("avisos_parar", s.stopOnAlert)
            .apply()
    }

    /** Añade un número (1 a 5 cifras). Devuelve un mensaje de error o null si se añadió. */
    fun addWatchNumber(raw: String): String? {
        val n = raw.trim()
        if (n.isEmpty() || n.length > 5 || !n.all { it.isDigit() }) return "Escribe entre 1 y 5 cifras"
        if (n in _state.value.watchNumbers) return null
        _state.update { it.copy(watchNumbers = it.watchNumbers + n) }
        saveWatch()
        return null
    }

    /** Añade una población; se acepta solo si existe en la lista de núcleos. */
    fun addWatchTown(raw: String): String? {
        val g = geo ?: return "Cargando datos…"
        val match = g.nucleos.firstOrNull { norm(it.name) == norm(raw) } ?: return "No encuentro esa población"
        if (_state.value.watchTowns.any { norm(it) == norm(match.name) }) return null
        _state.update { it.copy(watchTowns = it.watchTowns + match.name) }
        saveWatch()
        return null
    }

    fun removeWatchNumber(n: String) { _state.update { it.copy(watchNumbers = it.watchNumbers - n) }; saveWatch() }
    fun removeWatchTown(t: String) { _state.update { it.copy(watchTowns = it.watchTowns - t) }; saveWatch() }
    fun setStopOnAlert(v: Boolean) { _state.update { it.copy(stopOnAlert = v) }; saveWatch() }
    fun clearAlerts() { _state.update { it.copy(alerts = emptyList()) }; dirty = true }

    /** Sugerencias de poblaciones mientras se escribe (por población, de mayor a menor). */
    fun suggestTowns(query: String, limit: Int = 6): List<Nucleo> {
        val g = geo ?: return emptyList()
        val q = norm(query)
        if (q.length < 2) return emptyList()
        return g.nucleos.asSequence().filter { norm(it.name).contains(q) }
            .sortedWith(compareByDescending<Nucleo> { norm(it.name).startsWith(q) }.thenByDescending { it.population })
            .take(limit).toList()
    }

    /** Sin notificaciones: solo una vibración corta (como mucho una cada 2 s). */
    private fun vibrate() {
        if (!_state.value.vibrate) return
        val now = System.currentTimeMillis()
        if (now - lastVibration < 2000) return
        lastVibration = now
        @Suppress("DEPRECATION")
        ContextCompat.getSystemService(app, Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Busca en TODO lo generado (no solo en los últimos 50). Devuelve como mucho [limit] resultados. */
    fun search(query: String, limit: Int = 300): Pair<Int, List<Draw>> {
        if (query.isBlank()) return 0 to emptyList()
        val all = synchronized(history) { history.toList() }
        val found = all.filter { matchesQuery(it, query) }
        return found.size to found.take(limit)
    }

    // ---------------- Radio de acción ----------------

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

    private fun estimate() {
        val g = geo ?: return
        estimateJob?.cancel()
        estimateJob = scope.launch {
            delay(300)
            val cfg = _state.value.radius
            val rate = g.estimateRate(cfg)
            if (_state.value.radius == cfg) _state.update { it.copy(expectedRate = rate) }
        }
    }

    // ---------------- Guardar / cargar (se conserva al cerrar la app) ----------------

    fun saveAsync() {
        if (!::app.isInitialized) return
        scope.launch { save() }
    }

    @Synchronized
    private fun save() {
        val g = geo ?: return
        dirty = false
        val s = _state.value
        val idx = g.index
        val sb = StringBuilder()
        sb.append("v1;").append(s.total).append(';').append(s.hitCount).append(';').append(nextId).append('\n')
        fun line(tag: Char, d: Draw) {
            sb.append(tag).append(';').append(d.number).append(';').append(d.lat).append(';').append(d.lon).append(';')
                .append(idx[d.nearest] ?: 0).append(';').append(d.distanceKm).append(';').append(d.radiusKm)
                .append(';').append(d.id)
        }
        s.hits.forEach { h ->
            line('H', h.draw)
            sb.append(';').append(h.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()).append('\n')
        }
        s.alerts.forEach { a ->
            line('A', a.draw)
            sb.append(';').append(a.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .append(';').append(a.reason.replace(";", ",").replace("\n", " ")).append('\n')
        }
        synchronized(history) { history.toList() }.forEach { line('D', it); sb.append('\n') }
        val tmp = File(app.filesDir, "geoloteria.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(dataFile)
    }

    private fun load(g: Geo) {
        val f = dataFile
        if (!f.exists()) return
        runCatching {
            val hits = ArrayList<Hit>()
            val alerts = ArrayList<Alert>()
            val hist = ArrayList<Draw>()
            var total = 0L
            var hitCount = 0L
            f.forEachLine { l ->
                val p = l.split(";")
                when (p.firstOrNull()) {
                    "v1" -> {
                        total = p.getOrNull(1)?.toLongOrNull() ?: 0
                        hitCount = p.getOrNull(2)?.toLongOrNull() ?: 0
                        nextId = p.getOrNull(3)?.toLongOrNull() ?: 1
                    }
                    "H", "D", "A" -> {
                        if (p.size < 8) return@forEachLine
                        val n = g.nucleos.getOrNull(p[4].toInt()) ?: return@forEachLine
                        val d = Draw(p[7].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), n, p[5].toDouble(), p[6].toDouble())
                        if (p[0] == "A") {
                            val t = p.getOrNull(8)?.toLongOrNull()?.let {
                                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                            } ?: LocalDateTime.now()
                            alerts += Alert(d, p.getOrNull(9) ?: "Aviso", t)
                        } else if (p[0] == "H") {
                            val t = p.getOrNull(8)?.toLongOrNull()?.let {
                                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                            } ?: LocalDateTime.now()
                            hits += Hit(d, t)
                        } else {
                            hist += d
                        }
                    }
                }
            }
            synchronized(history) {
                history.clear()
                history.addAll(hist)
            }
            _state.update {
                it.copy(
                    hits = hits,
                    alerts = alerts,
                    recent = hist.take(50),
                    last = hist.firstOrNull(),
                    total = total,
                    hitCount = hitCount,
                    historySize = hist.size,
                )
            }
        }
    }

    // ---------------- Excel ----------------

    private val TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-ES"))

    fun exportExcel(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val s = _state.value
            val header = listOf("Número", "Latitud", "Longitud", "En núcleo", "Ciudad", "Comunidad",
                "Habitantes", "Distancia (km)", "Radio (km)")
            fun row(d: Draw): List<Any?> = listOf(
                d.number, d.lat, d.lon, if (d.isHit) "Sí" else "No", d.nearest.name, d.nearest.region,
                d.nearest.population, round2(d.distanceKm), round2(d.radiusKm),
            )
            val config = listOf(listOf("Habitantes", "Radio (km)")) +
                RadiusConfig.TIER_LABEL.mapIndexed { i, l -> listOf(l, s.radius.km[i]) }
            val hist = synchronized(history) { history.toList() }
            val result = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    Xlsx.write(
                        out,
                        listOf(
                            "Aciertos" to listOf(header + "Fecha y hora") + s.hits.map { h -> row(h.draw) + h.time.format(TIME) },
                            "Generados" to listOf(header) + hist.map { row(it) },
                            "Avisos" to listOf(listOf("Motivo") + header + "Fecha y hora") +
                                s.alerts.map { a -> listOf<Any?>(a.reason) + row(a.draw) + a.time.format(TIME) },
                            "Radios" to config,
                        ),
                    )
                } ?: error("no se pudo abrir el fichero")
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        { "Exportados ${s.hits.size} aciertos y ${hist.size} números a Excel." },
                        { e -> "Error al exportar: ${e.message}" },
                    ),
                )
            }
        }
    }

    /** Importa un Excel con columnas Número, Latitud y Longitud; cada punto se comprueba con los radios actuales. */
    fun importExcel(uri: Uri) {
        val g = geo ?: return
        scope.launch(Dispatchers.IO) {
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
                        if (!seen.add("$num|$lat|$lon")) continue
                        draws += g.evaluate(nextId++, num.padStart(5, '0'), lat, lon, cfg)
                    }
                }
                draws
            }
            result.onSuccess { draws ->
                val now = LocalDateTime.now()
                val newHits = draws.filter { it.isHit }
                val size = addHistory(draws)
                _state.update { s ->
                    s.copy(
                        historySize = size,
                        recent = (draws.reversed() + s.recent).take(50),
                        hits = (newHits.reversed().map { Hit(it, now) } + s.hits).take(HITS_MAX),
                        hitCount = s.hitCount + newHits.size,
                        total = s.total + draws.size,
                        message = if (draws.isEmpty()) "No encontré filas con Número, Latitud y Longitud."
                        else "Importados ${draws.size} números: ${newHits.size} en núcleo urbano (con los radios actuales).",
                    )
                }
                dirty = true
                save()
            }.onFailure { e -> _state.update { it.copy(message = "Error al importar: ${e.message}") } }
        }
    }
}

private fun round2(x: Double) = Math.round(x * 100) / 100.0

/** Minúsculas y sin tildes, para reconocer cabeceras ("Número" = "numero"). */
internal fun norm(x: String): String =
    java.text.Normalizer.normalize(x.trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

/** Solo dígitos → busca en el número; si no → en la ciudad (núcleo) o la comunidad. Sin tildes ni mayúsculas. */
internal fun matchesQuery(d: Draw, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (q.all { it.isDigit() }) return d.number.contains(q)
    val nq = norm(q)
    return norm(d.nearest.name).contains(nq) || norm(d.nearest.region).contains(nq)
}
