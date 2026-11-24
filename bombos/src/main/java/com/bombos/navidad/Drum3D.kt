package com.bombos.navidad

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** Lo que el sorteo necesita de un bombo (2D o 3D). */
interface Bombo {
    val n: Int
    val radius: Float
    val ballR: Float
    var angle: Float
    var omega: Float
    var targetOmega: Float
    var gateOpen: Boolean
    val exited: ArrayDeque<Int>
    val es3D: Boolean
    fun clear()
    fun add(bx: Float, by: Float, ident: Int, ux: Float = 0f, uy: Float = 0f)
    fun removeAt(i: Int): Int
    fun idAt(i: Int): Int
    fun isFree(bx: Float, by: Float): Boolean
    fun fillSettled(ids: IntArray, rnd: Random)
    fun lowest(): Int
    /** ¿Hay algo que simular? (si la jaula está parada, cerrada y las bolas quietas, no). */
    fun activo(): Boolean
    /** Simula hasta [dt] segundos sin pasar de [budgetNs] de cálculo. Devuelve los segundos simulados. */
    fun stepFor(dt: Float, budgetNs: Long): Float
    /** Posiciones para dibujar: x, y (y z en 3D). */
    fun snapshot(out: FloatArray?): FloatArray
}

/**
 * EL BOMBO GRANDE COMPLETO EN 3D: las 100.000 bolas, cada una con su número.
 *
 * Esfera de latón de 1,58 m que gira sobre un eje horizontal; bolas de boj de 18,8 mm.
 * Mismo método que en 2D (dinámica basada en posiciones), pero en 3D y repartido entre todos los
 * núcleos del procesador: cada núcleo calcula las correcciones de un trozo de las bolas (Jacobi)
 * y luego se aplican todas a la vez, así no se pisan.
 * La compuerta es un agujero en el polo inferior: la bola que cae por él es la que sale.
 * Cuando la jaula está parada y las bolas quietas, no se calcula nada (reposo).
 */
class Drum3D(override val radius: Float, capacity: Int, override val ballR: Float = 0.0094f) : Bombo {
    override var n = 0
        private set
    private var cap = capacity
    var x = FloatArray(cap); private set
    var y = FloatArray(cap); private set
    var z = FloatArray(cap); private set
    private var vx = FloatArray(cap); private var vy = FloatArray(cap); private var vz = FloatArray(cap)
    private var px = FloatArray(cap); private var py = FloatArray(cap); private var pz = FloatArray(cap)
    private var dx = FloatArray(cap); private var dy = FloatArray(cap); private var dz = FloatArray(cap)
    private var id = IntArray(cap)
    private var salio = BooleanArray(cap)

    override var angle = 0f
    override var omega = 0f
    override var targetOmega = 0f
    override var gateOpen = false
        set(v) { field = v; if (v) quieto = 0f }
    override val exited = ArrayDeque<Int>()
    override val es3D = true

    private val g = 9.81f
    private val restitution = 0.25f
    private val wallFriction = 0.35f
    var gateRadius = ballR * 1.9f
    private var quieto = 0f   // segundos que lleva todo quieto

    // ---- Hilos ----
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    private val team = Equipo(threads)

    // ---- Rejilla 3D (solo en la caja que ocupan las bolas) ----
    private val cell = ballR * 2f
    private var gx0 = 0f; private var gy0 = 0f; private var gz0 = 0f
    private var nx = 1; private var ny = 1; private var nz = 1
    private var cellStart = IntArray(1)
    private var cellItems = IntArray(cap)
    private var cellOf = IntArray(cap)
    // Vecinas de cada bola (se recalculan en cada subpaso, se usan en todas las iteraciones)
    private val MAXV = 20
    private var nbr = IntArray(cap * MAXV)
    private var nbrN = IntArray(cap)
    private var steps = 0

    private fun ensure(k: Int) {
        if (k <= cap) return
        val c = max(k, cap * 2)
        x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c)
        vx = vx.copyOf(c); vy = vy.copyOf(c); vz = vz.copyOf(c)
        px = px.copyOf(c); py = py.copyOf(c); pz = pz.copyOf(c)
        dx = FloatArray(c); dy = FloatArray(c); dz = FloatArray(c)
        id = id.copyOf(c); salio = BooleanArray(c)
        cellItems = IntArray(c); cellOf = IntArray(c)
        nbr = IntArray(c * MAXV); nbrN = IntArray(c)
        cap = c
    }

    override fun clear() { n = 0; exited.clear(); quieto = 0f }

    override fun add(bx: Float, by: Float, ident: Int, ux: Float, uy: Float) = add3(bx, by, 0f, ident, ux, uy, 0f)

    fun add3(bx: Float, by: Float, bz: Float, ident: Int, ux: Float = 0f, uy: Float = 0f, uz: Float = 0f) {
        ensure(n + 1)
        x[n] = bx; y[n] = by; z[n] = bz; vx[n] = ux; vy[n] = uy; vz[n] = uz; id[n] = ident
        n++
        quieto = 0f
    }

    override fun removeAt(i: Int): Int {
        val ident = id[i]
        val l = n - 1
        x[i] = x[l]; y[i] = y[l]; z[i] = z[l]; vx[i] = vx[l]; vy[i] = vy[l]; vz[i] = vz[l]; id[i] = id[l]
        n = l
        quieto = 0f
        return ident
    }

    override fun idAt(i: Int) = id[i]
    override fun isFree(bx: Float, by: Float) = true

    /**
     * Coloca las bolas en el fondo de la esfera, capa a capa, en el orden dado: es como las deja la
     * tolva (la primera lira abajo del todo). Empaquetado cúbico centrado en caras (el más compacto).
     */
    override fun fillSettled(ids: IntArray, rnd: Random) {
        clear()
        ensure(ids.size)
        val a = ballR * 2.02f * 1.4142f      // arista de la celda FCC
        val off = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0.5f, 0.5f, 0f), floatArrayOf(0.5f, 0f, 0.5f), floatArrayOf(0f, 0.5f, 0.5f))
        val lim = radius - ballR * 1.02f
        var k = 0
        var layer = 0
        var yy = -lim
        while (k < ids.size && yy < lim) {
            // dos subcapas por celda en y
            for (sub in 0..1) {
                val yLayer = yy + sub * a * 0.5f
                if (yLayer > lim || k >= ids.size) break
                val half = sqrt(max(0f, lim * lim - yLayer * yLayer))
                var ix = floor(-half / a).toInt()
                while (ix * a <= half && k < ids.size) {
                    var iz = floor(-half / a).toInt()
                    while (iz * a <= half && k < ids.size) {
                        for (o in off) {
                            if ((o[1] == 0.5f) != (sub == 1)) continue
                            val bx = (ix + o[0]) * a; val bz = (iz + o[2]) * a
                            if (bx * bx + bz * bz + yLayer * yLayer <= lim * lim && k < ids.size) {
                                add3(bx + (rnd.nextFloat() - 0.5f) * ballR * 0.04f, yLayer, bz + (rnd.nextFloat() - 0.5f) * ballR * 0.04f, ids[k++])
                            }
                        }
                        iz++
                    }
                    ix++
                }
            }
            yy += a
            layer++
        }
        while (k < ids.size) add3((rnd.nextFloat() - 0.5f) * radius, 0f, (rnd.nextFloat() - 0.5f) * radius, ids[k++])
        quieto = 0f
    }

    override fun lowest(): Int {
        var best = -1; var by = Float.MAX_VALUE
        for (k in 0 until n) {
            val r2 = x[k] * x[k] + z[k] * z[k]
            val score = y[k] + r2 * 4f
            if (score < by) { by = score; best = k }
        }
        return best
    }

    override fun activo(): Boolean = gateOpen || targetOmega != 0f || omega != 0f || quieto < 3f

    override fun stepFor(dt: Float, budgetNs: Long): Float {
        if (dt <= 0f) return 0f
        val h = 1f / hz
        val t0 = System.nanoTime()
        var sim = 0f
        while (sim + h * 0.5f < dt) {
            val acc = 0.8f
            omega += (targetOmega - omega).coerceIn(-acc * h, acc * h)
            angle = (angle + omega * h) % (2f * Math.PI.toFloat())
            if (n > 0 && activo()) substep(h)
            sim += h
            if (exited.isNotEmpty()) gateOpen = false   // la compuerta deja pasar una (o dos si caen juntas)
            if (System.nanoTime() - t0 > budgetNs) break
        }
        return sim
    }

    private fun parallel(task: (from: Int, to: Int) -> Unit) {
        if (threads == 1 || n < 2000) { task(0, n); return }
        team.run(n, task)
    }

    private fun buildGrid() {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (x[i] < x0) x0 = x[i]; if (x[i] > x1) x1 = x[i]
            if (y[i] < y0) y0 = y[i]; if (y[i] > y1) y1 = y[i]
            if (z[i] < z0) z0 = z[i]; if (z[i] > z1) z1 = z[i]
        }
        gx0 = x0 - cell; gy0 = y0 - cell; gz0 = z0 - cell
        nx = ceil((x1 - gx0) / cell).toInt() + 2
        ny = ceil((y1 - gy0) / cell).toInt() + 2
        nz = ceil((z1 - gz0) / cell).toInt() + 2
        val cells = nx * ny * nz
        if (cellStart.size < cells + 1) cellStart = IntArray(cells + 1 + cells / 4)
        java.util.Arrays.fill(cellStart, 0, cells + 1, 0)
        for (i in 0 until n) {
            val cx = ((x[i] - gx0) / cell).toInt().coerceIn(0, nx - 1)
            val cy = ((y[i] - gy0) / cell).toInt().coerceIn(0, ny - 1)
            val cz = ((z[i] - gz0) / cell).toInt().coerceIn(0, nz - 1)
            val c = (cy * nz + cz) * nx + cx
            cellOf[i] = c
            cellStart[c + 1]++
        }
        for (c in 0 until cells) cellStart[c + 1] += cellStart[c]
        val pos = cellStart.copyOf(cells)
        for (i in 0 until n) cellItems[pos[cellOf[i]]++] = i
    }

    private fun inGate(ax: Float, ay: Float, az: Float) = gateOpen && ay < 0f && ax * ax + az * az < gateRadius * gateRadius

    /** Reordena las bolas según su celda para que las vecinas estén juntas en memoria (más rápido). */
    private fun reordenar() {
        buildGrid()
        val perm = cellItems.copyOf(n)
        fun pf(a: FloatArray) { val t = FloatArray(n) { a[perm[it]] }; System.arraycopy(t, 0, a, 0, n) }
        pf(x); pf(y); pf(z); pf(vx); pf(vy); pf(vz)
        val ti = IntArray(n) { id[perm[it]] }; System.arraycopy(ti, 0, id, 0, n)
    }

    /** Iteraciones de Jacobi en paralelo. Con [mover] también se mueve la posición anterior (sin velocidad). */
    private fun resolver(iter: Int, mover: Boolean) {
        val rr = ballR * 2f
        val rr2 = rr * rr
        val wallR = radius - ballR
        repeat(iter) {
            parallel { a, b ->
                for (i in a until b) {
                    var sx = 0f; var sy = 0f; var sz = 0f
                    var cnt = 0
                    val base = i * MAXV
                    for (q in 0 until nbrN[i]) {
                        val j = nbr[base + q]
                        val ex = x[i] - x[j]; val ey = y[i] - y[j]; val ez = z[i] - z[j]
                        val d2 = ex * ex + ey * ey + ez * ez
                        if (d2 < rr2 && d2 > 1e-14f) {
                            val d = sqrt(d2)
                            // Propagación del peso: la bola de arriba cede más que la de abajo (pilas estables)
                            val share = if (mover) 0.5f + 0.5f * (ey / (rr * 0.5f)).coerceIn(-1f, 1f) else 0.5f
                            val corr = (rr - d) * share / d
                            sx += ex * corr; sy += ey * corr; sz += ez * corr
                            cnt++
                        }
                    }
                    val f = if (cnt <= 1) 1f else 1f / sqrt(cnt.toFloat())
                    var cx = sx * f; var cy = sy * f; var cz = sz * f
                    val m2 = cx * cx + cy * cy + cz * cz
                    val lim = ballR * 0.25f
                    if (m2 > lim * lim) { val k = lim / sqrt(m2); cx *= k; cy *= k; cz *= k }
                    dx[i] = cx; dy[i] = cy; dz[i] = cz
                }
            }
            parallel { a, b ->
                for (i in a until b) {
                    var mx = dx[i]; var my = dy[i]; var mz = dz[i]
                    x[i] += mx; y[i] += my; z[i] += mz
                    val d2 = x[i] * x[i] + y[i] * y[i] + z[i] * z[i]
                    if (d2 > wallR * wallR && !inGate(x[i], y[i], z[i])) {
                        val k = wallR / sqrt(d2)
                        val ox = x[i]; val oy = y[i]; val oz = z[i]
                        x[i] *= k; y[i] *= k; z[i] *= k
                        mx += x[i] - ox; my += y[i] - oy; mz += z[i] - oz
                    }
                    if (mover) { px[i] += mx; py[i] += my; pz[i] += mz }
                }
            }
        }
    }

    /** Pasadas de estabilización (propagación del peso de abajo arriba). */
    var estab = 0
    /** Subpasos por segundo, iteraciones por subpaso y cada cuántos subpasos se recalculan las vecinas. */
    var hz = 480f
    var iters = 2
    var vecinasCada = 2

    private fun substep(h: Float) {
        if (++steps % 60 == 0) reordenar()
        val damp = if (omega == 0f && targetOmega == 0f) 0.985f else 0.9995f
        val rr = ballR * 2f
        // 1) Vecinas de cada bola en la posición actual (con margen, valen para varios subpasos)
        if (steps % vecinasCada == 0) {
        buildGrid()
        val reach2 = (rr * (1.1f + 0.08f * vecinasCada)) * (rr * (1.1f + 0.08f * vecinasCada))
        parallel { a, b ->
            for (i in a until b) {
                var cnt = 0
                val base = i * MAXV
                val c = cellOf[i]
                val cx = c % nx; val cz = (c / nx) % nz; val cy = c / (nx * nz)
                loop@ for (oy in -1..1) {
                    val yy = cy + oy; if (yy < 0 || yy >= ny) continue
                    for (oz in -1..1) {
                        val zz = cz + oz; if (zz < 0 || zz >= nz) continue
                        for (ox in -1..1) {
                            val xx = cx + ox; if (xx < 0 || xx >= nx) continue
                            val cc = (yy * nz + zz) * nx + xx
                            for (q in cellStart[cc] until cellStart[cc + 1]) {
                                val j = cellItems[q]
                                if (j == i) continue
                                val ex = x[i] - x[j]; val ey = y[i] - y[j]; val ez = z[i] - z[j]
                                if (ex * ex + ey * ey + ez * ez < reach2) {
                                    nbr[base + cnt] = j
                                    cnt++
                                    if (cnt == MAXV) break@loop
                                }
                            }
                        }
                    }
                }
                nbrN[i] = cnt
            }
        }
        }
        // 2) Estabilización: se deshacen los solapes que ya había SIN tocar la velocidad
        //    (si no, el peso de 30 capas de bolas se convierte en «saltos»)
        if (estab > 0) resolver(estab, mover = true)
        // 3) Predicción con la gravedad
        parallel { a, b ->
            for (i in a until b) {
                px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]
                vy[i] -= g * h
                vx[i] *= damp; vy[i] *= damp; vz[i] *= damp
                x[i] += vx[i] * h; y[i] += vy[i] * h; z[i] += vz[i] * h
            }
        }
        // 4) Contactos nuevos de este paso (estos sí cambian la velocidad: choques)
        resolver(iters, mover = false)
        val wallR = radius - ballR
        // 3) Velocidades, rebote y rozamiento con la jaula (gira sobre el eje z: pared = ω × r)
        var moving = 0f
        val acum = java.util.concurrent.atomic.DoubleAdder()
        parallel { a, b ->
            var s = 0f
            for (i in a until b) {
                vx[i] = (x[i] - px[i]) / h; vy[i] = (y[i] - py[i]) / h; vz[i] = (z[i] - pz[i]) / h
                salio[i] = false
                val d2 = x[i] * x[i] + y[i] * y[i] + z[i] * z[i]
                if (d2 > (wallR - ballR * 0.1f) * (wallR - ballR * 0.1f)) {
                    val d = sqrt(d2)
                    if (inGate(x[i], y[i], z[i])) {
                        if (y[i] < -(radius + ballR)) salio[i] = true
                    } else {
                        val ux = x[i] / d; val uy = y[i] / d; val uz = z[i] / d
                        val vn = vx[i] * ux + vy[i] * uy + vz[i] * uz
                        if (vn > 0f) { val k = vn * (1f + restitution); vx[i] -= ux * k; vy[i] -= uy * k; vz[i] -= uz * k }
                        val wx = -omega * y[i]; val wy = omega * x[i]
                        // componente tangencial de la velocidad relativa a la pared
                        var rx = vx[i] - wx; var ry = vy[i] - wy; var rz = vz[i]
                        val rn = rx * ux + ry * uy + rz * uz
                        rx -= ux * rn; ry -= uy * rn; rz -= uz * rn
                        vx[i] -= rx * wallFriction; vy[i] -= ry * wallFriction; vz[i] -= rz * wallFriction
                    }
                }
                val v2 = vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i]
                if (v2 > 36f) { val k = 6f / sqrt(v2); vx[i] *= k; vy[i] *= k; vz[i] *= k }
                s += v2
            }
            acum.add(s.toDouble())
        }
        moving = acum.sum().toFloat()
        // Bolas que han caído por la compuerta (de la más baja a la más alta)
        if (gateOpen) {
            val out = ArrayList<Int>()
            for (i in 0 until n) if (salio[i]) out += i
            out.sortedBy { y[it] }.map { id[it] }.forEach { exited.addLast(it) }
            out.sortedDescending().forEach { removeAt(it) }
        }
        val mean = if (n > 0) sqrt(moving / n) else 0f
        quieto = if (omega == 0f && targetOmega == 0f && !gateOpen) quieto + h * (if (mean < 0.03f) 2f else 1f) else 0f
    }

    override fun snapshot(out: FloatArray?): FloatArray {
        val a = if (out != null && out.size >= n * 3) out else FloatArray(max(16, n * 3 + 96))
        for (k in 0 until n) { a[3 * k] = x[k]; a[3 * k + 1] = y[k]; a[3 * k + 2] = z[k] }
        return a
    }

    fun close() { team.close() }

    fun meanSpeed(): Float {
        if (n == 0) return 0f
        var s = 0.0
        for (k in 0 until n) s += sqrt((vx[k] * vx[k] + vy[k] * vy[k] + vz[k] * vz[k]).toDouble())
        return (s / n).toFloat()
    }
}

/**
 * Equipo de hilos para la física: se quedan «calientes» esperando trabajo (sin crear tareas nuevas en
 * cada paso, que en Android cuesta mucho) y se reparten el trabajo en bloques pequeños: así los núcleos
 * rápidos y los lentos del móvil terminan a la vez. Si no hay trabajo un rato, se duermen.
 */
class Equipo(private val hilos: Int) {
    @Volatile private var gen = 0
    @Volatile private var tarea: ((Int, Int) -> Unit)? = null
    @Volatile private var total = 0
    @Volatile private var vivo = true
    private val siguiente = AtomicInteger(0)
    private val pendientes = AtomicInteger(0)
    private val bloque = 512
    private val workers = (1 until hilos).map { k ->
        Thread({ bucle() }, "bombo3d-$k").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
    }

    private fun trabajar() {
        val t = tarea ?: return
        val n = total
        while (true) {
            val a = siguiente.getAndAdd(bloque)
            if (a >= n) break
            t(a, min(n, a + bloque))
        }
    }

    private fun bucle() {
        var visto = 0
        while (vivo) {
            var spins = 0
            while (gen == visto && vivo) {
                if (++spins < 20_000) Thread.onSpinWait() else LockSupport.parkNanos(200_000)
            }
            if (!vivo) return
            visto = gen
            trabajar()
            pendientes.decrementAndGet()
        }
    }

    fun run(n: Int, t: (Int, Int) -> Unit) {
        tarea = t
        total = n
        siguiente.set(0)
        pendientes.set(workers.size)
        gen++
        workers.forEach { LockSupport.unpark(it) }
        trabajar()
        while (pendientes.get() > 0) Thread.onSpinWait()
        tarea = null
    }

    fun close() { vivo = false; workers.forEach { LockSupport.unpark(it) } }
}
