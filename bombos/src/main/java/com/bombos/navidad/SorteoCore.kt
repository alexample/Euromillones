package com.bombos.navidad

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Una pareja de bolas ensartada en el alambre. */
data class Extraccion(
    val orden: Int,       // 1 … 1.807
    val tabla: Int,       // 1 … 10
    val alambre: Int,     // 1 … 10
    val pos: Int,         // 1 … 20
    val numero: Int,      // 0 … 99.999
    val cat: Cat,
    val t: Long,          // hora del sorteo (segundos desde 1970, hora de Madrid como si fuera UTC)
)

/** Dónde se guarda el sorteo (SQLite en el móvil; en memoria en las pruebas). */
interface Store {
    fun nuevo(semilla: String, inicio: Long): Long
    fun guardar(sorteo: Long, filas: List<Extraccion>)
    fun fase(sorteo: Long, fase: String)
    fun terminar(sorteo: Long)
    fun extracciones(sorteo: Long): List<Extraccion>
    fun enCurso(): Pair<Long, String>?   // sorteo sin terminar y su fase
}

/** Fases reguladas del sorteo, en orden. */
enum class Fase(val titulo: String, val texto: String) {
    LISTO("Preparado", "Pulsa ▶ para empezar. Puedes saltar cualquier fase."),
    RECUENTO(
        "Examen y recuento público de las bolas",
        "21 de diciembre. Se examinan y cuentan en público las 100.000 bolas de números y las 1.807 de premios, " +
            "todas de boj, de 18,8 mm y 3 g, con la cifra grabada a láser. Se ordenan en liras de 500 bolas (10 varillas). " +
            "Cualquiera puede comprobar que su número está.",
    ),
    CIERRE_SALA(
        "Cierre de la sala",
        "Se desaloja la sala y se cierran por dentro todos los accesos. La puerta del escenario se cierra y se precinta " +
            "con tres llaves que guardan tres claveros distintos.",
    ),
    NOCHE("Noche del 21 al 22", "La sala queda cerrada y precintada hasta la mañana."),
    APERTURA("Apertura de la sala", "22 de diciembre, 8:00. Se abren las puertas al público hasta completar el aforo."),
    MESA("Constitución de la mesa", "8:30. Se constituye la mesa que preside y autoriza el sorteo: presidente, interventor y vocales."),
    EXAMEN("Examen de las bolas por el público", "Los asistentes que lo pidan pueden examinar las bolas con permiso del presidente."),
    CARGA(
        "Carga de los bombos",
        "Las liras se cuelgan de los «paraguas» y se vacían en las tolvas transparentes, que llevan las bolas a los bombos. " +
            "Para los 100.000 números hacen falta dos tolvas.",
    ),
    CIERRE_BOMBOS("Cierre de los bombos", "Se cierran las compuertas de carga de los dos bombos."),
    GIRO("Señal del presidente", "A la señal del presidente los dos bombos giran a la vez para mezclar las bolas."),
    EXTRACCION(
        "Extracción",
        "Un niño o niña de San Ildefonso saca una bola del bombo de números y otro, a la vez, una del de premios. " +
            "Bajan por la trompeta a la copa (si caen dos, se canta primero la de abajo). Otros dos niños las cantan " +
            "y las ensartan en el alambre: 20 bolas de cada clase por alambre y 10 alambres por tabla (200 + 200). " +
            "Los bombos giran cada vez que se llena un alambre.",
    ),
    CIERRE_TABLA(
        "Cierre de la tabla y relevo",
        "La tabla llena se cierra delante de la mesa con la conformidad del presidente y del interventor. " +
            "Salen los cuatro niños de esa tabla y entran cuatro nuevos.",
    ),
    FIN("Fin del sorteo", "El sorteo termina cuando no queda ninguna bola en el bombo de premios."),
    LISTA(
        "Lista oficial de premios",
        "Más de 40 personas preparan la lista de premios con un proceso informático controlado paso a paso. " +
            "Unos 45 minutos después se envía a la Fábrica Nacional de Moneda y Timbre, que la imprime esa misma tarde.",
    ),
    EXPOSICION(
        "Tablas expuestas",
        "Las tablas con los números premiados quedan expuestas al público siete días. Después se abren y las bolas " +
            "se cuentan y examinan de nuevo.",
    ),
    TERMINADO("Sorteo terminado", "Busca por número o por premio en la pestaña «Buscar»."),
}

/** Pasos de cada pareja de bolas. */
enum class Paso { MEZCLA, ASIENTO, SACANDO, CANTA_NUMERO, CANTA_PREMIO, CELEBRA, INSERTA }

/** Lo que se dibuja: posiciones de las bolas y estado de los dos bombos. */
class Frame(
    val numXY: FloatArray, val numN: Int, val numAngle: Float, val numGate: Boolean,
    val preXY: FloatArray, val preN: Int, val preAngle: Float, val preGate: Boolean,
    val numR: Float, val preR: Float, val ballR: Float,
    val copaNum: String?, val copaPre: String?, val fly: Float,   // 0…1 bajando por la trompeta; -1 quieta
    val tolva: Float,   // 0…1 cuánto queda en las tolvas durante la carga
)

/** Estado del sorteo para la pantalla. */
data class Estado(
    val sorteo: Long = 0,
    val fase: Fase = Fase.LISTO,
    val paso: Paso? = null,
    val reloj: Long = 0,
    val progreso: Float = 0f,          // dentro de la fase
    val tabla: Int = 1, val alambre: Int = 1, val pos: Int = 0,
    val extraidas: Int = 0,
    val quedan: IntArray = IntArray(Cat.entries.size) { Cat.entries[it].count },
    val ultimas: List<Extraccion> = emptyList(),   // las últimas, la más reciente primero
    val mayores: List<Extraccion> = emptyList(),
    val canto: String = "", val cantoPremio: Boolean = false,
    val liras: Int = 0,
    val fisica: Boolean = true,
    val semilla: String = "",
)

/**
 * EL SORTEO: lleva el reloj del sorteo, las fases del protocolo y los dos bombos.
 * [tick] avanza [dt] segundos del sorteo. Con la física enlazada (velocidad baja) la bola que sale
 * del bombo de premios la decide la simulación; si se acelera mucho, se coge una al azar entre las
 * que quedan (equivale a una mezcla perfecta).
 */
class SorteoCore(private val store: Store, private val rnd: () -> Random) {

    companion object {
        const val NUMEROS = 100_000
        const val NUM_VISIBLES = 2_500        // bolas de números simuladas (1 de cada 40)
        const val POR_ALAMBRE = 20
        const val ALAMBRES_POR_TABLA = 10
        const val LIRAS_NUMEROS = 200
        const val LIRAS_PREMIOS = 4
        const val RPM_NUMEROS = 8f
        const val RPM_PREMIOS = 12f

        // Duraciones (segundos del sorteo)
        const val T_LIRA = 15.0
        const val T_CIERRE_SALA = 180.0
        const val T_MESA = 300.0
        const val T_EXAMEN = 600.0
        const val T_CARGA = 1_200.0
        const val T_CIERRE_BOMBOS = 60.0
        const val T_GIRO = 180.0
        const val T_MEZCLA = 8.0
        const val T_ASIENTO = 3.0
        const val T_SACAR = 1.2
        const val T_ATASCO = 8.0
        const val T_CANTA_NUM = 2.6
        const val T_CANTA_PEDREA = 1.4
        const val T_CANTA_GRANDE = 6.0
        const val T_INSERTA = 0.8
        const val T_CIERRE_TABLA = 150.0
        const val T_LISTA = 2_700.0
        const val T_FLY = 0.7

        /** 21/12 10:00 y 22/12 08:00 del año en curso, como segundos «locales». */
        fun hora(year: Int, mes: Int, dia: Int, h: Int, m: Int): Long =
            java.time.LocalDateTime.of(year, mes, dia, h, m).toEpochSecond(java.time.ZoneOffset.UTC)

        fun celebracion(c: Cat): Double = when (c) {
            Cat.GORDO -> 50.0; Cat.SEGUNDO -> 40.0; Cat.TERCERO -> 35.0
            Cat.CUARTO -> 25.0; Cat.QUINTO -> 18.0; Cat.PEDREA -> 0.0
        }
    }

    val bolas = Cat.bolas()
    val numR = 0.79f                                    // bombo grande: esfera de 1,58 m
    val preR = Drum.radiusFor(Cat.TOTAL_BOLAS, 0.40f)  // sección del bombo pequeño
    val num = Drum(numR, NUM_VISIBLES + 64)
    val pre = Drum(preR, Cat.TOTAL_BOLAS + 16)

    var estado = Estado(); private set
    var cantos: ((String) -> Unit)? = null

    private var year = java.time.LocalDate.now().year
    private var restante = 0.0          // segundos que le quedan al paso/fase actual
    private var faseTotal = 1.0
    private var paso: Paso? = null

    // Bombo de números: las que quedan (para elegir al azar sin repetir)
    private val pool = IntArray(NUMEROS) { it }
    private var poolN = NUMEROS
    // Bombo de premios fuera de la física (modo rápido): las que quedan
    private val quedanPremios = ArrayList<Int>()

    private var copaNum = ArrayDeque<Int>()
    private var copaPre = ArrayDeque<Int>()
    private var actualNum = -1
    private var actualPre = -1
    private var esperando = 0.0
    private var fly = -1.0
    private var pendientes = ArrayList<Extraccion>()
    private var ultimas = ArrayDeque<Extraccion>()
    private var mayores = ArrayList<Extraccion>()
    private val quedan = IntArray(Cat.entries.size) { Cat.entries[it].count }
    private var extraidas = 0
    private var liras = 0
    private var vertidas = 0.0
    var fisica = true

    // ------------------------------------------------------------------ inicio

    fun nuevo(semilla: String) {
        year = java.time.LocalDate.now().year
        val id = store.nuevo(semilla, hora(year, 12, 21, 10, 0))
        reset()
        estado = Estado(sorteo = id, fase = Fase.LISTO, reloj = hora(year, 12, 21, 10, 0), semilla = semilla)
    }

    private fun reset() {
        for (i in 0 until NUMEROS) pool[i] = i
        poolN = NUMEROS
        quedanPremios.clear(); quedanPremios.addAll(bolas.indices)
        copaNum.clear(); copaPre.clear(); actualNum = -1; actualPre = -1
        ultimas.clear(); mayores.clear(); pendientes.clear()
        for (c in Cat.entries) quedan[c.ordinal] = c.count
        extraidas = 0; liras = 0; vertidas = 0.0; fly = -1.0
        num.clear(); pre.clear()
        num.targetOmega = 0f; num.omega = 0f; pre.targetOmega = 0f; pre.omega = 0f
        num.gateOpen = false; pre.gateOpen = false
        paso = null
    }

    /** Retoma un sorteo a medias (después de cerrar la app). */
    fun retomar(id: Long, fase: String) {
        reset()
        val hechas = store.extracciones(id).sortedBy { it.orden }
        val sacados = BooleanArray(NUMEROS)
        val usadas = IntArray(Cat.entries.size)
        hechas.forEach { e ->
            sacados[e.numero] = true
            usadas[e.cat.ordinal]++
            quedan[e.cat.ordinal]--
            ultimas.addFirst(e)
            if (e.cat.grande) mayores += e
        }
        while (ultimas.size > 60) ultimas.removeLast()
        poolN = 0
        for (i in 0 until NUMEROS) if (!sacados[i]) pool[poolN++] = i
        // Quita del bombo de premios una bola de cada premio ya salido
        quedanPremios.clear()
        val vistas = IntArray(Cat.entries.size)
        bolas.forEachIndexed { i, c -> if (vistas[c.ordinal] < usadas[c.ordinal]) vistas[c.ordinal]++ else quedanPremios += i }
        extraidas = hechas.size
        val last = hechas.lastOrNull()
        if (last != null) estado = estado.copy(tabla = last.tabla, alambre = last.alambre, pos = last.pos)
        val f = runCatching { Fase.valueOf(fase) }.getOrDefault(Fase.LISTO)
        year = java.time.LocalDate.now().year
        val reloj = last?.t ?: hora(year, 12, 21, 10, 0)
        estado = estado.copy(sorteo = id, reloj = reloj)
        if (hechas.isNotEmpty() || f.ordinal >= Fase.EXTRACCION.ordinal) {
            llenarAsentados()
            if (quedanPremios.isEmpty()) entrar(Fase.FIN)
            else { entrar(Fase.EXTRACCION); paso = if (last == null) Paso.MEZCLA else Paso.INSERTA; restante = if (last == null) T_MEZCLA else 0.0; if (last == null) arrancar() }
        } else {
            entrar(if (f.ordinal >= Fase.CARGA.ordinal) Fase.CARGA else f)
        }
        publicar()
    }

    private fun llenarAsentados() {
        val r = rnd()
        pre.fillSettled(quedanPremios.shuffled(r).toIntArray(), r)
        num.fillSettled(IntArray(objetivoVisibles()) { -1 }, r)
    }

    private fun objetivoVisibles() = ceil(poolN.toDouble() * NUM_VISIBLES / NUMEROS).toInt()

    // ------------------------------------------------------------------ fases

    private fun entrar(f: Fase) {
        estado = estado.copy(fase = f)
        store.fase(estado.sorteo, f.name)
        val dur = when (f) {
            Fase.LISTO -> Double.MAX_VALUE
            Fase.RECUENTO -> T_LIRA * (LIRAS_NUMEROS + LIRAS_PREMIOS)
            Fase.CIERRE_SALA -> T_CIERRE_SALA
            Fase.NOCHE -> 0.0
            Fase.APERTURA -> 1_800.0
            Fase.MESA -> T_MESA
            Fase.EXAMEN -> T_EXAMEN
            Fase.CARGA -> T_CARGA
            Fase.CIERRE_BOMBOS -> T_CIERRE_BOMBOS
            Fase.GIRO -> T_GIRO
            Fase.EXTRACCION -> Double.MAX_VALUE
            Fase.CIERRE_TABLA -> T_CIERRE_TABLA
            Fase.FIN -> 60.0
            Fase.LISTA -> T_LISTA
            Fase.EXPOSICION -> 30.0
            Fase.TERMINADO -> Double.MAX_VALUE
        }
        restante = dur
        faseTotal = dur
        when (f) {
            Fase.NOCHE -> estado = estado.copy(reloj = hora(year, 12, 22, 8, 0))
            Fase.CARGA -> { num.clear(); pre.clear(); vertidas = 0.0; cargaOrden = null }
            Fase.GIRO -> { asegurarCargados(); arrancar() }
            Fase.CIERRE_TABLA -> parar()
            Fase.FIN -> { parar(); store.terminar(estado.sorteo); estado = estado.copy(canto = "¡Fin del sorteo!") }
            else -> {}
        }
    }

    private fun arrancar() {
        num.targetOmega = Drum.omegaFromRpm(RPM_NUMEROS)
        pre.targetOmega = Drum.omegaFromRpm(RPM_PREMIOS)
    }

    private fun parar() { num.targetOmega = 0f; pre.targetOmega = 0f; num.gateOpen = false; pre.gateOpen = false }

    /** Al cerrar los bombos tienen que estar todas las bolas dentro (si se saltó la carga, se colocan). */
    private fun asegurarCargados() {
        val r = rnd()
        if (pre.n == 0 || num.n == 0) { llenarAsentados(); return }
        val dentro = HashSet<Int>()
        for (i in 0 until pre.n) dentro += pre.id[i]
        quedanPremios.filter { it !in dentro && it !in copaPre }.forEach { b ->
            pre.add((r.nextFloat() - 0.5f) * preR * 0.6f, (r.nextFloat() - 0.2f) * preR * 0.6f, b)
        }
        while (num.n < objetivoVisibles()) num.add((r.nextFloat() - 0.5f) * numR * 0.6f, (r.nextFloat() - 0.2f) * numR * 0.6f, -1)
    }

    private fun siguienteFase() {
        val f = estado.fase
        when (f) {
            Fase.LISTO -> entrar(Fase.RECUENTO)
            Fase.EXTRACCION -> {}
            Fase.CIERRE_TABLA -> { entrar(Fase.EXTRACCION); paso = Paso.MEZCLA; restante = T_MEZCLA; arrancar() }
            Fase.TERMINADO -> {}
            else -> {
                val next = Fase.entries[f.ordinal + 1]
                entrar(next)
                if (next == Fase.EXTRACCION) { paso = Paso.MEZCLA; restante = T_MEZCLA; arrancar() }
            }
        }
    }

    /** Botón «Saltar fase» (durante la extracción salta a la siguiente tabla). */
    fun saltar() {
        when (estado.fase) {
            Fase.EXTRACCION -> {
                // Termina la tabla en curso de golpe (sin física)
                val antes = estado.tabla
                val f = fisica
                fisica = false
                var guard = 0
                while (estado.fase == Fase.EXTRACCION && estado.tabla == antes && guard++ < 100_000) tick(5.0)
                fisica = f
                if (estado.fase == Fase.CIERRE_TABLA) siguienteFase()
            }
            Fase.CARGA -> { llenarAsentados(); siguienteFase() }
            Fase.TERMINADO -> {}
            else -> {
                estado = estado.copy(reloj = estado.reloj + max(0.0, min(restante, 1e7)).toLong())
                siguienteFase()
            }
        }
        flush()
        publicar()
    }

    // ------------------------------------------------------------------ reloj

    /**
     * Avanza [dt] segundos del sorteo. [fisicaDt] = tiempo que se simula de física (puede ser menor
     * si el móvil no da para más). Devuelve false si el sorteo ha terminado.
     */
    fun tick(dt: Double, fisicaDt: Float = 0f): Boolean {
        if (estado.fase == Fase.TERMINADO) return false
        var left = dt
        var guard = 0
        while (left > 1e-9 && guard++ < 10_000) {
            if (estado.fase == Fase.LISTO) return true
            val enlazada = fisica && estado.fase == Fase.EXTRACCION && paso == Paso.SACANDO
            if (enlazada) {
                // Esperando a que caigan las bolas por la compuerta: lo decide la física
                recogerSalidas()
                if (copaNum.isNotEmpty() && copaPre.isNotEmpty()) { tomarPareja(); continue }
                esperando += left
                avanzarReloj(left)
                if (esperando > T_ATASCO) forzarSalida()
                left = 0.0
                break
            }
            val use = min(left, restante)
            restante -= use
            avanzarReloj(use)
            left -= use
            if (restante <= 1e-9) terminarPaso()
        }
        if (fly >= 0) { fly += dt / T_FLY; if (fly > 1) fly = -1.0 }
        if (estado.fase == Fase.CARGA && fisicaDt > 0f) verter(fisicaDt)
        if (fisicaDt > 0f) {
            num.step(fisicaDt, 48)
            pre.step(fisicaDt, 48)
        }
        return estado.fase != Fase.TERMINADO
    }

    private fun avanzarReloj(s: Double) {
        if (s <= 0) return
        acumulado += s
        if (acumulado >= 1.0) {
            val k = acumulado.toLong()
            acumulado -= k
            estado = estado.copy(reloj = estado.reloj + k)
        }
        if (estado.fase == Fase.RECUENTO) liras = (((faseTotal - restante) / T_LIRA).toInt()).coerceAtMost(LIRAS_NUMEROS + LIRAS_PREMIOS)
    }
    private var acumulado = 0.0

    /** Carga: las tolvas vierten las bolas por arriba mientras el bombo gira despacio. */
    private fun verter(fdt: Float) {
        val r = rnd()
        val totalPre = quedanPremios.size
        val totalNum = objetivoVisibles()
        val frac = ((faseTotal - restante) / faseTotal).coerceIn(0.0, 1.0)
        num.targetOmega = Drum.omegaFromRpm(2f); pre.targetOmega = Drum.omegaFromRpm(2f)
        val wantPre = (totalPre * min(1.0, frac * 1.15)).toInt()
        val wantNum = (totalNum * min(1.0, frac * 1.15)).toInt()
        var tries = 0
        while (pre.n < wantPre && tries++ < 6) {
            val bx = (r.nextFloat() - 0.5f) * preR * 0.5f
            val by = preR * 0.55f + r.nextFloat() * preR * 0.3f
            if (pre.isFree(bx, by)) siguientePremioACargar()?.let { pre.add(bx, by, it, 0f, -0.5f) }
        }
        tries = 0
        while (num.n < wantNum && tries++ < 6) {
            val lado = if (r.nextBoolean()) -1 else 1      // dos tolvas
            val bx = lado * numR * 0.3f + (r.nextFloat() - 0.5f) * numR * 0.25f
            val by = numR * 0.55f + r.nextFloat() * numR * 0.3f
            if (num.isFree(bx, by)) num.add(bx, by, -1, 0f, -0.5f)
        }
        if (restante < 1.0) asegurarCargados()
    }

    private var cargaOrden: IntArray? = null
    private var cargaK = 0
    /** Las bolas de premios entran en el orden en que las vierte la tolva (barajadas al vaciar las liras). */
    private fun siguientePremioACargar(): Int? {
        val o = cargaOrden ?: quedanPremios.shuffled(rnd()).toIntArray().also { cargaOrden = it; cargaK = 0 }
        if (cargaK >= o.size) return null
        return o[cargaK++]
    }

    private fun terminarPaso() {
        if (estado.fase != Fase.EXTRACCION) { siguienteFase(); return }
        when (paso) {
            Paso.MEZCLA -> { parar(); paso = Paso.ASIENTO; restante = T_ASIENTO }
            Paso.ASIENTO, Paso.INSERTA, null -> {
                if (paso == Paso.INSERTA) {
                    if (quedanPremios.isEmpty()) { flush(); entrar(Fase.FIN); paso = null; return }
                    val pos = estado.pos
                    if (pos >= POR_ALAMBRE) {
                        // Alambre lleno: siguiente alambre (o tabla)
                        if (estado.alambre >= ALAMBRES_POR_TABLA) {
                            estado = estado.copy(tabla = estado.tabla + 1, alambre = 1, pos = 0)
                            flush(); entrar(Fase.CIERRE_TABLA); paso = null; return
                        }
                        estado = estado.copy(alambre = estado.alambre + 1, pos = 0)
                        paso = Paso.MEZCLA; restante = T_MEZCLA; arrancar(); return
                    }
                }
                empezarSacar()
            }
            Paso.SACANDO -> { // modo rápido: se coge al azar
                sacarAlAzar(); tomarPareja()
            }
            Paso.CANTA_NUMERO -> {
                val c = bolas[actualPre]
                paso = Paso.CANTA_PREMIO
                restante = if (c.grande) T_CANTA_GRANDE else T_CANTA_PEDREA
                cantar(Canto.premio(c), true)
            }
            Paso.CANTA_PREMIO -> {
                val c = bolas[actualPre]
                if (c.grande) { paso = Paso.CELEBRA; restante = celebracion(c); cantar("${Canto.numero(actualNum)}… ${Canto.premio(c)}", true) }
                else insertar()
            }
            Paso.CELEBRA -> insertar()
        }
    }

    private fun empezarSacar() {
        paso = Paso.SACANDO
        esperando = 0.0
        restante = T_SACAR
        if (fisica) {
            if (copaNum.isEmpty()) num.gateOpen = true
            if (copaPre.isEmpty()) pre.gateOpen = true
        }
    }

    private fun recogerSalidas() {
        while (num.exited.isNotEmpty()) {
            num.exited.removeFirst()
            copaNum.addLast(sacarNumero())
            num.gateOpen = false
            // Mantiene la proporción de bolas visibles
            if (num.n < objetivoVisibles()) {
                val r = rnd()
                num.add((r.nextFloat() - 0.5f) * numR * 0.4f, numR * 0.6f, -1)
            }
            fly = 0.0
        }
        while (pre.exited.isNotEmpty()) {
            val b = pre.exited.removeFirst()
            quedanPremios.remove(b)
            copaPre.addLast(b)
            pre.gateOpen = false
            fly = 0.0
        }
    }

    /** Si una compuerta se atasca, el operario empuja la bola de abajo. */
    private fun forzarSalida() {
        if (copaNum.isEmpty()) { val i = num.lowest(); if (i >= 0) { num.removeAt(i); num.exited.addLast(-1) } else num.exited.addLast(-1) }
        if (copaPre.isEmpty()) {
            val i = pre.lowest()
            if (i >= 0) pre.exited.addLast(pre.removeAt(i))
            else if (quedanPremios.isNotEmpty()) pre.exited.addLast(quedanPremios[rnd().nextInt(quedanPremios.size)])
        }
        recogerSalidas()
        esperando = 0.0
    }

    private fun sacarNumero(): Int {
        val k = rnd().nextInt(poolN)
        val v = pool[k]
        pool[k] = pool[poolN - 1]
        poolN--
        return v
    }

    /** Modo rápido: una bola al azar de cada bombo (mezcla perfecta). */
    private fun sacarAlAzar() {
        if (copaNum.isEmpty()) {
            copaNum.addLast(sacarNumero())
            if (num.n > objetivoVisibles() && num.n > 0) num.removeAt(rnd().nextInt(num.n))
        }
        if (copaPre.isEmpty() && quedanPremios.isNotEmpty()) {
            val b = quedanPremios.removeAt(rnd().nextInt(quedanPremios.size))
            // quita esa bola del dibujo si está
            for (i in 0 until pre.n) if (pre.id[i] == b) { pre.removeAt(i); break }
            copaPre.addLast(b)
        }
        fly = 0.0
    }

    private fun tomarPareja() {
        num.gateOpen = false; pre.gateOpen = false
        actualNum = copaNum.removeFirst()
        actualPre = copaPre.removeFirst()
        paso = Paso.CANTA_NUMERO
        restante = T_CANTA_NUM
        cantar(Canto.numero(actualNum), false)
    }

    private fun cantar(s: String, premio: Boolean) {
        estado = estado.copy(canto = s, cantoPremio = premio)
        cantos?.invoke(s)
    }

    private fun insertar() {
        val c = bolas[actualPre]
        val pos = estado.pos + 1
        extraidas++
        quedan[c.ordinal]--
        val e = Extraccion(extraidas, estado.tabla, estado.alambre, pos, actualNum, c, estado.reloj)
        pendientes += e
        ultimas.addFirst(e)
        while (ultimas.size > 60) ultimas.removeLast()
        if (c.grande) { mayores += e; flush() }
        estado = estado.copy(pos = pos)
        paso = Paso.INSERTA
        restante = T_INSERTA
        if (pendientes.size >= 20) flush()
    }

    fun flush() {
        if (pendientes.isEmpty()) return
        store.guardar(estado.sorteo, pendientes.toList())
        pendientes.clear()
    }

    /** Copia del estado para la pantalla. */
    fun publicar(): Estado {
        val prog = when (estado.fase) {
            Fase.EXTRACCION, Fase.CIERRE_TABLA -> extraidas.toFloat() / Cat.TOTAL_BOLAS
            Fase.LISTO, Fase.TERMINADO -> 0f
            else -> if (faseTotal <= 0 || faseTotal == Double.MAX_VALUE) 0f else ((faseTotal - restante) / faseTotal).toFloat()
        }
        estado = estado.copy(
            paso = paso, progreso = prog, extraidas = extraidas, quedan = quedan.copyOf(),
            ultimas = ultimas.toList(), mayores = mayores.toList(), liras = liras, fisica = fisica,
        )
        return estado
    }

    private var bufNum: FloatArray? = null
    private var bufPre: FloatArray? = null

    fun frame(): Frame {
        bufNum = num.snapshot(bufNum)
        bufPre = pre.snapshot(bufPre)
        val cn = (copaNum.firstOrNull() ?: actualNum.takeIf { paso != Paso.SACANDO && it >= 0 })?.let { Canto.n5(it) }
        val cp = (copaPre.firstOrNull() ?: actualPre.takeIf { paso != Paso.SACANDO && it >= 0 })?.let { bolas[it].bola }
        val tolva = if (estado.fase == Fase.CARGA) (restante / faseTotal).toFloat().coerceIn(0f, 1f) else 0f
        return Frame(bufNum!!.copyOf(num.n * 2), num.n, num.angle, num.gateOpen, bufPre!!.copyOf(pre.n * 2), pre.n, pre.angle, pre.gateOpen,
            numR, preR, num.ballR, cn, cp, fly.toFloat(), tolva)
    }

    fun empezar() { if (estado.fase == Fase.LISTO) siguienteFase() }
}
