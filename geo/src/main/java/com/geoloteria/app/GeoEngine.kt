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

data class Hit(val draw: Draw, val time: LocalDateTime?)

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
    val historySize: Long = 0,   // números guardados (para exportar)
    val message: String? = null,
    // Avisos con filtro
    val watchNumbers: List<String> = emptyList(),   // 5 cifras = número exacto; menos = terminación
    val watchTowns: List<String> = emptyList(),     // nombre de la población
    val alerts: List<Alert> = emptyList(),
    val stopOnAlert: Boolean = false,
)

enum class RankKind(val title: String) { CIUDADES("Ciudades"), NUMEROS("Números"), COMUNIDADES("Comunidades") }

/** Una fila del ranking: nombre, detalle, veces y el texto con el que buscarla. */
data class RankRow(val label: String, val detail: String, val count: Long, val key: String)

data class Ranking(val total: Long, val distinct: Long, val rows: List<RankRow>)

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

    /** Base de datos con TODO lo generado o importado (sin límite). */
    private lateinit var db: DrawDb

    /** Números aún no guardados en la base de datos (se guardan en lotes cada segundo). */
    private val pending = ArrayList<Pair<Draw, Long>>()

    private fun queue(items: List<Draw>) {
        val now = System.currentTimeMillis()
        synchronized(pending) { items.forEach { pending += it to now } }
    }

    /** Pasa a la base de datos lo pendiente. */
    private fun flush() {
        val g = geo ?: return
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        db.insertAll(batch, g.index)
    }

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
        db = DrawDb(app)
        scope.launch {
            val g = Geo(app)
            geo = g
            load(g)
            _state.update { it.copy(ready = true, nucleos = g.nucleos.size) }
            estimate()
            // Cada segundo se guardan los números nuevos; cada 15 s los avisos
            var tick = 0
            while (isActive) {
                delay(1_000)
                runCatching { flush() }
                if (++tick % 15 == 0 && dirty) save()
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
        synchronized(pending) { pending.clear() }
        scope.launch { db.clear() }
        _state.update {
            it.copy(last = null, recent = emptyList(), hits = emptyList(), alerts = emptyList(), historySize = 0, total = 0, hitCount = 0)
        }
        dirty = true
        saveAsync()
    }

    private fun record(d: Draw) {
        queue(listOf(d))
        _state.update { s ->
            s.copy(
                last = d,
                recent = (listOf(d) + s.recent).take(50),
                hits = if (d.isHit) (listOf(Hit(d, LocalDateTime.now())) + s.hits).take(HITS_MAX) else s.hits,
                total = s.total + 1,
                hitCount = s.hitCount + if (d.isHit) 1 else 0,
                historySize = s.total + 1,
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
        val town = d.nearest.key
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
        val k = norm(raw)
        val match = g.nucleos.firstOrNull { it.key == k } ?: return "No encuentro esa población"
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
        return g.nucleos.asSequence().filter { it.key.contains(q) }
            .sortedWith(compareByDescending<Nucleo> { it.key.startsWith(q) }.thenByDescending { it.population })
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

    /**
     * Busca en TODO lo guardado (sin límite). Devuelve cuántos coinciden en total
     * y la primera página de [limit] resultados (del más reciente al más antiguo).
     */
    fun search(query: String, limit: Int = 500, hitsOnly: Boolean = false): Pair<Long, List<Draw>> {
        val g = geo ?: return 0L to emptyList()
        runCatching { flush() }
        val count = db.searchCount(query, hitsOnly)
        val page = db.search(query, g.nucleos, hitsOnly, limit).map { it.first }
        return count to page
    }

    // ---------------- Ranking ----------------

    /**
     * Lo que más ha salido en TODO lo guardado.
     * [kind]: ciudades, números o comunidades; [hitsOnly]: solo los que cayeron en núcleo urbano.
     */
    fun ranking(kind: RankKind, hitsOnly: Boolean, limit: Int): Ranking {
        val g = geo ?: return Ranking(0, 0, emptyList())
        runCatching { flush() }
        val total = db.count(hitsOnly)
        val col = when (kind) {
            RankKind.CIUDADES -> "nuc"
            RankKind.NUMEROS -> "number"
            RankKind.COMUNIDADES -> "region_key"
        }
        val regionName = g.nucleos.associate { it.regionKey to it.region }
        val rows = db.top(col, hitsOnly, limit).map { (v, c) ->
            when (kind) {
                RankKind.CIUDADES -> {
                    val n = g.nucleos.getOrNull(v.toIntOrNull() ?: -1)
                    RankRow(n?.name ?: "?", n?.let { "${it.region} · ${"%,d".format(Locale.forLanguageTag("es-ES"), it.population)} hab." } ?: "", c, v)
                }
                RankKind.NUMEROS -> RankRow(v, "", c, v)
                RankKind.COMUNIDADES -> RankRow(regionName[v] ?: v, "", c, v)
            }
        }
        return Ranking(total, db.distinct(col, hitsOnly), rows)
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
        runCatching { flush() }
        dirty = false
        val s = _state.value
        val idx = g.index
        val sb = StringBuilder()
        sb.append("v2;").append(nextId).append('\n')
        // Los números van en la base de datos; aquí solo los avisos recibidos
        s.alerts.forEach { a ->
            val d = a.draw
            sb.append("A;").append(d.number).append(';').append(d.lat).append(';').append(d.lon).append(';')
                .append(idx[d.nearest] ?: 0).append(';').append(d.distanceKm).append(';').append(d.radiusKm)
                .append(';').append(d.id)
                .append(';').append(a.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .append(';').append(a.reason.replace(";", ",").replace("\n", " ")).append('\n')
        }
        val tmp = File(app.filesDir, "geoloteria.tmp")
        tmp.writeText(sb.toString())
        tmp.renameTo(dataFile)
    }

    private fun toTime(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

    private fun load(g: Geo) {
        val alerts = ArrayList<Alert>()
        val migrate = ArrayList<Pair<Draw, Long>>()
        val f = dataFile
        if (f.exists()) runCatching {
            f.forEachLine { l ->
                val p = l.split(";")
                when (p.firstOrNull()) {
                    "v1", "v2" -> nextId = maxOf(nextId, p.getOrNull(if (p[0] == "v1") 3 else 1)?.toLongOrNull() ?: 1)
                    "H", "D", "A" -> {
                        if (p.size < 8) return@forEachLine
                        val n = g.nucleos.getOrNull(p[4].toIntOrNull() ?: -1) ?: return@forEachLine
                        val d = Draw(p[7].toLong(), p[1], p[2].toDouble(), p[3].toDouble(), n, p[5].toDouble(), p[6].toDouble())
                        val t = p.getOrNull(8)?.toLongOrNull()
                        when (p[0]) {
                            "A" -> alerts += Alert(d, p.getOrNull(9) ?: "Aviso", t?.let(::toTime) ?: LocalDateTime.now())
                            "D" -> migrate += d to System.currentTimeMillis()   // versión anterior: pasa a la base de datos
                        }
                    }
                }
            }
        }
        if (migrate.isNotEmpty() && db.count() == 0L) db.insertAll(migrate.reversed(), g.index)

        val total = db.count()
        nextId = maxOf(nextId, db.maxId() + 1)
        val recent = db.search("", g.nucleos, limit = 50).map { it.first }
        val hits = db.search("", g.nucleos, hitsOnly = true, limit = HITS_MAX).map { (d, ts) -> Hit(d, toTime(ts)) }
        _state.update {
            it.copy(
                hits = hits,
                alerts = alerts,
                recent = recent,
                last = recent.firstOrNull(),
                total = total,
                hitCount = db.count(hitsOnly = true),
                historySize = total,
            )
        }
        if (migrate.isNotEmpty()) save()
    }

    // ---------------- Excel ----------------

    private val TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-ES"))

    fun exportExcel(uri: Uri) {
        val g = geo ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { flush() }
            val s = _state.value
            val header = listOf("Número", "Latitud", "Longitud", "En núcleo", "Ciudad", "Comunidad",
                "Habitantes", "Distancia (km)", "Radio (km)", "Fecha y hora")
            fun row(d: Draw, ts: Long): List<Any?> = listOf(
                d.number, d.lat, d.lon, if (d.isHit) "Sí" else "No", d.nearest.name, d.nearest.region,
                d.nearest.population, round2(d.distanceKm), round2(d.radiusKm), toTime(ts).format(TIME),
            )
            val max = Xlsx.MAX_ROWS - 1
            var nHits = 0
            var nAll = 0
            val result = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    Xlsx.write(
                        out,
                        listOf(
                            Xlsx.Sheet("Aciertos") { emit ->
                                emit(header)
                                db.forEach(g.nucleos, hitsOnly = true, max = max) { d, ts -> nHits++; emit(row(d, ts)) }
                            },
                            Xlsx.Sheet("Generados") { emit ->
                                emit(header)
                                db.forEach(g.nucleos, hitsOnly = false, max = max) { d, ts -> nAll++; emit(row(d, ts)) }
                            },
                            Xlsx.sheet(
                                "Avisos",
                                listOf(listOf<Any?>("Motivo") + header) + s.alerts.map { a ->
                                    listOf<Any?>(a.reason) + row(a.draw, a.time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                                },
                            ),
                            Xlsx.sheet(
                                "Radios",
                                listOf(listOf<Any?>("Habitantes", "Radio (km)")) +
                                    RadiusConfig.TIER_LABEL.mapIndexed { i, l -> listOf<Any?>(l, s.radius.km[i]) },
                            ),
                        ),
                    )
                } ?: error("no se pudo abrir el fichero")
            }
            _state.update {
                it.copy(
                    message = result.fold(
                        {
                            "Exportados $nHits aciertos y $nAll números a Excel." +
                                if (nAll >= max) " (Excel admite como mucho ${max} filas: se exportaron los más recientes.)" else ""
                        },
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
                queue(draws)
                runCatching { flush() }
                _state.update { s ->
                    s.copy(
                        historySize = s.total + draws.size,
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

/**
 * Búsqueda preparada una sola vez: solo dígitos → busca en el número; si no → en la ciudad o la comunidad
 * (sin tildes ni mayúsculas, usando los nombres ya normalizados de cada núcleo).
 */
class SearchQuery(raw: String) {
    private val q = raw.trim()
    private val digits = q.isNotEmpty() && q.all { it.isDigit() }
    private val nq = if (digits) "" else norm(q)
    val isEmpty: Boolean get() = q.isEmpty()

    fun matches(d: Draw): Boolean = when {
        q.isEmpty() -> true
        digits -> d.number.contains(q)
        else -> d.nearest.key.contains(nq) || d.nearest.regionKey.contains(nq)
    }
}
