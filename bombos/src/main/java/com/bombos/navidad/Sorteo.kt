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

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        db = SorteoDb(app)
        SolarEntropy.start()
        core = SorteoCore(db) { SolarEntropy.random() }
        core.cantos = { s -> _cantos.tryEmit(s) }
        _velocidad.value = app.getSharedPreferences("bombos", Context.MODE_PRIVATE).getFloat("velocidad", 1f)
        synchronized(lock) {
            val c = db.enCurso()
            if (c != null) core.retomar(c.first, c.second) else core.nuevo(semilla())
            _estado.value = core.publicar()
            frame = core.frame()
        }
        startLoop()
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
        synchronized(lock) { core.flush(); core.nuevo(semilla()) }
        _estado.value = core.publicar()
        _version.value++
        _mensaje.value = "Sorteo nuevo preparado: pulsa ▶"
    }

    fun limpiarMensaje() { _mensaje.value = null }

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
