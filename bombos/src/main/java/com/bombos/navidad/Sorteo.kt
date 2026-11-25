package com.bombos.navidad

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Velocidades del sorteo (MAX = lo que dé el móvil). */
object Velocidad {
    const val MAX = -1f
    val LISTA = listOf(1f, 2f, 5f, 10f, 30f, 100f, 1000f, MAX)
    /** Hasta esta velocidad la bola de premios la decide la física. */
    const val FISICA_HASTA = 2f
    fun texto(v: Float) = if (v == MAX) "Máx" else "${v.toInt()}×"
}

/** El sorteo en marcha (uno a la vez), con su bucle de tiempo real. */
object Sorteo {
    private lateinit var app: Context
    lateinit var db: SorteoDb
        private set
    private lateinit var core: SorteoCore
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _velocidad = MutableStateFlow(1f)
    val velocidad: StateFlow<Float> = _velocidad.asStateFlow()
    private val _cantos = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val cantos: SharedFlow<String> = _cantos.asSharedFlow()
    private val _mensaje = MutableStateFlow<String?>(null)
    val mensaje: StateFlow<String?> = _mensaje.asStateFlow()
    /** Cambia cada vez que se guarda algo (para refrescar búsquedas y tablas). */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Volatile var frame: Frame? = null
        private set

    /** Física completa 3D (las 100.000 bolas) para el próximo sorteo nuevo. */
    private val _modo3d = MutableStateFlow(false)
    val modo3d: StateFlow<Boolean> = _modo3d.asStateFlow()
    private val _prueba = MutableStateFlow<String?>(null)
    val prueba: StateFlow<String?> = _prueba.asStateFlow()
    private val _probando = MutableStateFlow(false)
    val probando: StateFlow<Boolean> = _probando.asStateFlow()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        db = SorteoDb(app)
        SolarEntropy.start()
        core = SorteoCore(db) { SolarEntropy.random() }
        core.cantos = { s -> _cantos.tryEmit(s) }
        val prefs = app.getSharedPreferences("bombos", Context.MODE_PRIVATE)
        _velocidad.value = prefs.getFloat("velocidad", 1f)
        _modo3d.value = prefs.getBoolean("modo3d", false)
        _prueba.value = prefs.getString("prueba", null)
        synchronized(lock) {
            val c = db.enCurso()
            if (c != null) {
                val f = ficheroFisica()
                val inp = if (c.third && f.exists()) runCatching { f.inputStream() }.getOrNull() else null
                try { core.retomar(c.first, c.second, c.third, inp) } finally { inp?.close() }
            } else core.nuevo(semilla(), _modo3d.value)
            _estado.value = core.publicar()
            frame = core.frame()
        }
        startLoop()
    }

    private fun ficheroFisica() = java.io.File(app.filesDir, "fisica3d.bin")

    /** Guarda dónde está cada bola del modo 3D (se llama al pausar y al terminar cada alambre). */
    private fun guardarFisica() {
        val bytes = synchronized(lock) {
            val b = java.io.ByteArrayOutputStream(1 shl 21)
            if (core.guardarFisica(b)) b.toByteArray() else null
        } ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = java.io.File(app.filesDir, "fisica3d.tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(ficheroFisica())
            }
        }
    }

    private fun semilla() = SolarEntropy.status.value.seed?.hex ?: "azar-local"

    private fun startLoop() {
        if (loop != null) return
        loop = scope.launch {
            var last = System.nanoTime()
            var pub = 0L
            while (isActive) {
                val now = System.nanoTime()
                val real = ((now - last) / 1e9).coerceIn(0.0, 0.1)
                last = now
                if (_running.value) {
                    var fin = false
                    synchronized(lock) {
                        val v = _velocidad.value
                        if (v == Velocidad.MAX) {
                            core.fisica = false
                            val t0 = System.nanoTime()
                            while (System.nanoTime() - t0 < 14_000_000L) { if (!core.tick(120.0)) break }
                            core.tick(0.0, real.toFloat())
                        } else {
                            core.fisica = v <= Velocidad.FISICA_HASTA
                            val fdt = if (core.fisica) (real * v).toFloat() else real.toFloat()
                            core.tick(real * v, fdt)
                        }
                        if (core.estado.fase == Fase.TERMINADO) fin = true
                    }
                    if (fin) pausar()
                }
                val f = synchronized(lock) { core.frame() }
                frame = f
                if (now - pub > 100_000_000L || !_running.value) {
                    pub = now
                    val e = synchronized(lock) { core.publicar() }
                    if (e.extraidas != _estado.value.extraidas || e.fase != _estado.value.fase) _version.value++
                    if (e.modo3d && e.alambre != _estado.value.alambre) guardarFisica()
                    _estado.value = e
                }
                delay(16)
            }
        }
    }

    fun empezar() {
        synchronized(lock) { core.empezar() }
        _running.value = true
        SorteoService.start(app)
    }

    fun pausar() {
        _running.value = false
        synchronized(lock) { core.flush() }
        guardarFisica()
        SorteoService.stop(app)
    }

    fun alternar() { if (_running.value) pausar() else empezar() }

    fun setVelocidad(v: Float) {
        _velocidad.value = v
        app.getSharedPreferences("bombos", Context.MODE_PRIVATE).edit().putFloat("velocidad", v).apply()
    }

    fun saltar() {
        synchronized(lock) { if (core.estado.fase == Fase.LISTO) core.empezar(); core.saltar() }
        _version.value++
    }

    fun nuevo() {
        pausar()
        synchronized(lock) { core.flush(); core.nuevo(semilla(), _modo3d.value) }
        _estado.value = core.publicar()
        _version.value++
        _mensaje.value = "Sorteo nuevo preparado: pulsa ▶"
    }

    fun limpiarMensaje() { _mensaje.value = null }

    fun setModo3d(v: Boolean) {
        _modo3d.value = v
        app.getSharedPreferences("bombos", Context.MODE_PRIVATE).edit().putBoolean("modo3d", v).apply()
        _mensaje.value = if (v) "Física completa 3D: se usará en el próximo sorteo nuevo" else "Corte 2D: se usará en el próximo sorteo nuevo"
    }

    /**
     * PRUEBA DE RENDIMIENTO: mueve el bombo grande con sus 100.000 bolas girando a 8 rpm y mide
     * cuántos segundos de giro simula este móvil por cada segundo real.
     */
    fun probar() {
        if (_probando.value) return
        pausar()
        _probando.value = true
        _prueba.value = "Midiendo… (unos 30 segundos)"
        scope.launch {
            val r = runCatching {
                val d = Drum3D(0.79f, 100_064)
                val rnd = kotlin.random.Random(1)
                d.fillSettled(IntArray(100_000) { it }, rnd)
                d.stepFor(0.15f, Long.MAX_VALUE)                 // se asientan
                d.omega = Drum.omegaFromRpm(8f); d.targetOmega = d.omega
                d.stepFor(0.1f, Long.MAX_VALUE)                  // calentamiento
                val t0 = System.nanoTime()
                var sim = 0f
                while (System.nanoTime() - t0 < 20_000_000_000L && sim < 4f) sim += d.stepFor(0.25f, Long.MAX_VALUE)
                val real = (System.nanoTime() - t0) / 1e9
                d.close()
                val f = sim / real
                val nucleos = Runtime.getRuntime().availableProcessors()
                // En un sorteo los bombos se mueven unos 29 minutos en total (giro inicial, 91 mezclas
                // de alambre y la salida de 1.807 parejas); el resto del tiempo las bolas están quietas.
                val activo = 1_750.0
                val extra = activo * (1.0 / f - 1.0).coerceAtLeast(0.0)
                val total = 3.0 * 3600 + 45 * 60 + extra
                "Núcleos: $nucleos · 1 s de giro con 100.000 bolas tarda ${"%.1f".format(1 / f)} s " +
                    "(${"%.2f".format(f)}× del tiempo real). Un sorteo completo en 3D duraría unas " +
                    "${(total / 3600).toInt()} h ${((total % 3600) / 60).toInt()} min (el real, unas 3 h 45 min)."
            }
            val txt = r.getOrElse { "Error en la prueba: ${it.message}" }
            _prueba.value = txt
            app.getSharedPreferences("bombos", Context.MODE_PRIVATE).edit().putString("prueba", txt).apply()
            _probando.value = false
        }
    }

    fun borrar(id: Long) {
        scope.launch(Dispatchers.IO) {
            if (id == _estado.value.sorteo) return@launch
            db.borrar(id)
            _version.value++
        }
    }

    // ---------------- Excel ----------------

    fun exportar(id: Long, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val r = runCatching {
                val filas = db.extracciones(id)
                val m = db.mayores(id)
                fun hora(t: Long) = java.time.LocalDateTime.ofEpochSecond(t, 0, java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                val bolas = Xlsx.Sheet("Bolas") { emit ->
                    emit(listOf("Orden", "Tabla", "Alambre", "Posición", "Número", "Premio", "Por billete (€)", "Por décimo (€)", "Hora"))
                    filas.forEach { e ->
                        emit(listOf(e.orden, e.tabla, e.alambre, e.pos, Canto.n5(e.numero), e.cat.nombre, e.cat.importe, e.cat.importe / 10, hora(e.t)))
                    }
                }
                val mayores = Xlsx.sheet("Premios mayores",
                    listOf(listOf<Any?>("Premio", "Número", "Tabla", "Alambre", "Posición", "Hora")) +
                        filas.filter { it.cat.grande }.sortedBy { it.cat.ordinal }.map {
                            listOf<Any?>(it.cat.nombre, Canto.n5(it.numero), it.tabla, it.alambre, it.pos, hora(it.t))
                        })
                val derivados = Xlsx.Sheet("Premios derivados") { emit ->
                    emit(listOf("Premio", "Número", "Por billete (€)", "Por décimo (€)"))
                    Derivado.entries.forEach { d -> m.numeros(d).forEach { n -> emit(listOf(d.nombre, Canto.n5(n), d.importe, d.importe / 10)) } }
                }
                app.contentResolver.openOutputStream(uri)?.use { Xlsx.write(it, listOf(bolas, mayores, derivados)) } ?: error("no se pudo abrir")
            }
            _mensaje.value = r.fold({ "Excel guardado." }, { e -> "Error al exportar: ${e.message}" })
        }
    }
}
