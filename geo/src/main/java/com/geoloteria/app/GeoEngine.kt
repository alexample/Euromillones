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
    val mode: GenMode = GenMode.CIUDADES,
    val pick: CityPick = CityPick.POBLACION,
    // Bloques de 400.000
    val block: BlockProgress? = null,
    val blocks: List<BlockReport> = emptyList(),
)

/** Progreso del bloque en curso. */
data class BlockProgress(val done: Int, val total: Int, val startedAt: Long, val label: String = "Generando")

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
                mode = runCatching { GenMode.valueOf(prefs.getString("modo", null) ?: "") }.getOrDefault(GenMode.CIUDADES),
                pick = runCatching { CityPick.valueOf(prefs.getString("eleccion", null) ?: "") }.getOrDefault(CityPick.POBLACION),
            )
        }
        val ch = android.app.NotificationChannel(ALERT_CHANNEL, "Avisos de número o población",
            android.app.NotificationManager.IMPORTANCE_HIGH)
        app.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        db = DrawDb(app)
        SolarEntropy.start()
        scope.launch {
            val g = Geo(app)
            geo = g
            load(g)
            _state.update { it.copy(ready = true, nucleos = g.nucleos.size, blocks = BlockStore.list(app)) }
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

    /** Un número nuevo según el modo elegido. */
    private fun next(g: Geo): Draw? {
        val s = _state.value
        return when (s.mode) {
            GenMode.CIUDADES -> g.drawInCity(nextId++, s.radius, s.pick, SolarEntropy.random())
            GenMode.ESPANA -> g.draw(nextId++, s.radius, SolarEntropy.random())
        }
    }

    fun generateOne() {
        val g = geo ?: return
        scope.launch {
            next(g)?.let(::record)
                ?: _state.update { it.copy(message = "No hay ciudades con radio mayor que 0: revisa los radios.") }
        }
    }

    fun setMode(m: GenMode) {
        _state.update { it.copy(mode = m) }
        app.getSharedPreferences("geoloteria", Context.MODE_PRIVATE).edit().putString("modo", m.name).apply()
    }

    fun setPick(p: CityPick) {
        _state.update { it.copy(pick = p) }
        app.getSharedPreferences("geoloteria", Context.MODE_PRIVATE).edit().putString("eleccion", p.name).apply()
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
                    if (_state.value.running) {
                        val d = next(g)
                        if (d != null) record(d) else stop()
                    }
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

    // ---------------- Bloques de 400.000 ----------------

    private var blockJob: Job? = null

    fun refreshBlocks() {
        scope.launch(Dispatchers.IO) { _state.update { it.copy(blocks = BlockStore.list(app)) } }
    }

    /** Genera un bloque completo lo más rápido posible, guardándolo en el móvil mientras se crea. */
    fun startBlock(size: Int = BLOCK_SIZE) {
        val g = geo ?: return
        if (_state.value.block != null) return
        val s0 = _state.value
        val cfg = s0.radius
        val mode = s0.mode
        val pick = s0.pick
        val started = System.currentTimeMillis()
        val id = "bloque_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(java.util.Date(started))
        _state.update { it.copy(block = BlockProgress(0, size, started)) }
        GeneratorService.start(app)
        blockJob = scope.launch(Dispatchers.Default) {
            val file = BlockStore.dataFile(app, id)
            val result = runCatching {
                val counter = BlockCounter(g.nucleos)
                BlockStore.openWriter(file).use { w ->
                    w.write(BlockStore.metaLine(mode, pick, cfg)); w.newLine()
                    w.write(BlockStore.header()); w.newLine()
                    val sb = StringBuilder(160)
                    for (i in 0 until size) {
                        if (!isActive) throw kotlinx.coroutines.CancellationException()
                        val seed = SolarEntropy.status.value.seed
                        val rnd = SolarEntropy.random()
                        val d = when (mode) {
                            GenMode.CIUDADES -> g.drawInCity(0, cfg, pick, rnd)
                            GenMode.ESPANA -> g.draw(0, cfg, rnd)
                        } ?: error("No hay ciudades con radio mayor que 0")
                        val hex = seed?.hex ?: "azar-local"
                        val ni = g.index[d.nearest] ?: -1
                        counter.add(d.number, ni, hex, seed?.dataTime)
                        sb.setLength(0)
                        sb.append(d.number).append(';').append(r5(d.lat)).append(';').append(r5(d.lon)).append(';')
                            .append(d.nearest.name.replace(";", ",")).append(';').append(d.nearest.region).append(';')
                            .append(d.nearest.population).append(';').append(r3(d.distanceKm)).append(';')
                            .append(r3(d.radiusKm)).append(';').append(if (d.isHit) "1" else "0").append(';').append(hex)
                        w.write(sb.toString()); w.newLine()
                        if (i % 2000 == 0) _state.update { it.copy(block = it.block?.copy(done = i)) }
                    }
                }
                val rep = counter.report(id, started, System.currentTimeMillis() - started, mode, pick, cfg, imported = false)
                BlockStore.saveReport(app, rep)
                rep
            }
            result.onFailure { file.delete() }
            _state.update {
                it.copy(
                    block = null,
                    blocks = BlockStore.list(app),
                    message = result.fold(
                        { r -> "Bloque terminado: ${r.distinct} números distintos de 100.000 · ${r.anomalies} anomalías." },
                        { e -> if (e is kotlinx.coroutines.CancellationException) "Bloque cancelado." else "Error en el bloque: ${e.message}" },
                    ),
                )
            }
            if (!_state.value.running) GeneratorService.stop(app)
        }
    }

    fun cancelBlock() {
        blockJob?.cancel()
    }

    fun deleteBlock(id: String) {
        scope.launch(Dispatchers.IO) {
            BlockStore.delete(app, id)
            _state.update { it.copy(blocks = BlockStore.list(app)) }
        }
    }

    /** Copia el CSV comprimido del bloque (se abre con 7-Zip/WinRAR y luego Excel). */
    fun exportBlockCsv(id: String, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val r = runCatching { app.contentResolver.openOutputStream(uri)?.use { BlockStore.copyData(app, id, it) } ?: error("no se pudo abrir") }
            _state.update { it.copy(message = r.fold({ "Bloque guardado como CSV comprimido." }, { e -> "Error: ${e.message}" })) }
        }
    }

    /** Excel del bloque: hoja con los 400.000 números + hojas del informe estadístico. */
    fun exportBlockExcel(id: String, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            _state.update { it.copy(block = BlockProgress(0, 1, System.currentTimeMillis(), "Exportando a Excel")) }
            val rep = BlockStore.list(app).firstOrNull { it.id == id }
            val r = runCatching {
                app.contentResolver.openOutputStream(uri)?.use { out ->
                    val sheets = ArrayList<Xlsx.Sheet>()
                    sheets += Xlsx.Sheet("Numeros") { emit ->
                        BlockStore.openReader(BlockStore.dataFile(app, id).inputStream()).use { rd ->
                            rd.lineSequence().filter { !it.startsWith("#") }.forEachIndexed { i, line ->
                                val p = line.split(";")
                                if (i == 0) emit(p)
                                else emit(listOf<Any?>(p[0], p.getOrNull(1)?.toDoubleOrNull(), p.getOrNull(2)?.toDoubleOrNull(),
                                    p.getOrNull(3), p.getOrNull(4), p.getOrNull(5)?.toIntOrNull(), p.getOrNull(6)?.toDoubleOrNull(),
                                    p.getOrNull(7)?.toDoubleOrNull(), p.getOrNull(8), p.getOrNull(9)))
                            }
                        }
                    }
                    if (rep != null) sheets += reportSheets(rep)
                    Xlsx.write(out, sheets)
                } ?: error("no se pudo abrir")
            }
            _state.update {
                it.copy(block = null, message = r.fold({ "Bloque exportado a Excel." }, { e -> "Error al exportar: ${e.message}" }))
            }
        }
    }

    private fun reportSheets(r: BlockReport): List<Xlsx.Sheet> {
        fun rows(list: List<StatRow>) = listOf(listOf<Any?>("Valor", "Detalle", "Veces", "Esperado", "z", "Anómalo")) +
            list.map { listOf<Any?>(it.label, it.detail, it.count, if (it.expected.isNaN()) null else round2(it.expected), round2(it.z), if (it.anomalous) "SÍ" else "") }
        val resumen = listOf(
            listOf<Any?>("Bloque", r.id), listOf<Any?>("Números", r.n),
            listOf<Any?>("Modo", r.mode + " " + r.pick), listOf<Any?>("Radios (km)", r.radius),
            listOf<Any?>("Distintos", r.distinct), listOf<Any?>("Distintos esperados", round2(r.expectedDistinct)),
            listOf<Any?>("z de los que no salieron", round2(r.zerosZ)),
            listOf<Any?>("Chi² uniformidad", round2(r.chi2)), listOf<Any?>("z del chi²", round2(r.chi2Z)),
            listOf<Any?>("Esperado por número", round2(r.expectedPerNumber)), listOf<Any?>("Máximo de veces", r.maxCount),
            listOf<Any?>("Umbral de anomalía (veces)", r.highThreshold), listOf<Any?>("Anomalías totales", r.anomalies),
        ) + listOf(listOf<Any?>("")) + listOf(listOf<Any?>("Hash solar", "Hora dato UTC", "Números")) +
            r.seeds.map { listOf<Any?>(it.hex, it.dataTime, it.count) }
        return listOf(
            Xlsx.sheet("Informe", resumen),
            Xlsx.sheet("Mas repetidos", rows(r.anomalousNumbers + r.topNumbers.filter { t -> r.anomalousNumbers.none { it.label == t.label } })),
            Xlsx.sheet("Cifras", rows(r.digitRows)),
            Xlsx.sheet("Ciudades", rows(r.cityRows)),
            Xlsx.sheet("Comunidades", rows(r.regionRows)),
        )
    }

    /** Importa un bloque (CSV o CSV comprimido exportado por la app) y recalcula su estadística. */
    fun importBlock(uri: Uri) {
        val g = geo ?: return
        if (_state.value.block != null) return
        _state.update { it.copy(block = BlockProgress(0, BLOCK_SIZE, System.currentTimeMillis(), "Importando")) }
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val id = "importado_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(java.util.Date(started))
            val file = BlockStore.dataFile(app, id)
            val byName = g.nucleos.withIndex().associate { (i, n) -> (n.key + "|" + n.regionKey) to i }
            val byTown = g.nucleos.withIndex().groupBy({ it.value.key }, { it.index })
            val result = runCatching {
                var mode: GenMode? = null
                var pick: CityPick? = null
                var cfg = _state.value.radius
                val counter = BlockCounter(g.nucleos)
                val input = app.contentResolver.openInputStream(uri) ?: error("no se pudo abrir el fichero")
                BlockStore.openReader(input).use { rd ->
                    BlockStore.openWriter(file).use { w ->
                        var header: List<String>? = null
                        var cols = IntArray(0)
                        var delim: String? = null
                        rd.lineSequence().forEach { line ->
                            if (line.startsWith("#geoloteria")) {
                                line.split(";").forEach { kv ->
                                    val (k, v) = kv.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                                    when (k) {
                                        "modo" -> mode = runCatching { GenMode.valueOf(v) }.getOrNull()
                                        "eleccion" -> pick = runCatching { CityPick.valueOf(v) }.getOrNull()
                                        "radios" -> cfg = RadiusConfig.decode(v)
                                    }
                                }
                                w.write(line); w.newLine()
                                return@forEach
                            }
                            if (delim == null && "numero" in norm(line)) {
                                delim = when {
                                    ';' in line -> ";"
                                    '\t' in line -> "\t"
                                    else -> ","
                                }
                            }
                            val p = line.split(delim ?: ";").map { it.trim().trim('"') }
                            if (header == null) {
                                val h = p.map { norm(it) }
                                if ("numero" !in h) return@forEach
                                header = h
                                cols = intArrayOf(h.indexOf("numero"), h.indexOf("latitud"), h.indexOf("longitud"),
                                    h.indexOf("ciudad"), h.indexOf("comunidad"), h.indexOf("semilla"))
                                w.write(BlockStore.header()); w.newLine()
                                return@forEach
                            }
                            val num = p.getOrNull(cols[0])?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() && it.length <= 5 }
                                ?.padStart(5, '0') ?: return@forEach
                            val lat = p.getOrNull(cols[1])?.toDoubleOrNull()
                            val lon = p.getOrNull(cols[2])?.toDoubleOrNull()
                            val town = p.getOrNull(cols[3]).orEmpty()
                            val region = p.getOrNull(cols[4]).orEmpty()
                            var ni = byName[norm(town) + "|" + norm(region)] ?: byTown[norm(town)]?.firstOrNull() ?: -1
                            if (ni < 0 && lat != null && lon != null) ni = g.index[g.evaluate(0, num, lat, lon, cfg).nearest] ?: -1
                            val hex = p.getOrNull(cols[5])?.ifBlank { null } ?: "importado"
                            counter.add(num, ni, hex, null)
                            val n = g.nucleos.getOrNull(ni)
                            w.write("$num;${lat ?: ""};${lon ?: ""};${n?.name ?: town};${n?.region ?: region};${n?.population ?: ""};;;;$hex")
                            w.newLine()
                            if (counter.n % 5000 == 0) _state.update { it.copy(block = it.block?.copy(done = counter.n)) }
                        }
                    }
                }
                if (counter.n == 0) error("no encontré filas con la columna «numero»")
                val rep = counter.report(id, started, System.currentTimeMillis() - started, mode, pick, cfg, imported = true)
                BlockStore.saveReport(app, rep)
                rep
            }
            result.onFailure { file.delete() }
            _state.update {
                it.copy(
                    block = null,
                    blocks = BlockStore.list(app),
                    message = result.fold(
                        { r -> "Bloque importado: ${r.n} números · ${r.anomalies} anomalías." },
                        { e -> "Error al importar el bloque: ${e.message}" },
                    ),
                )
            }
        }
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

private fun r5(x: Double) = Math.round(x * 100000) / 100000.0
private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
