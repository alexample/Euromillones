package com.bombos.navidad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * FÍSICA DE UN BOMBO (sección vertical en 2D)
 *
 * Las bolas son discos rígidos de boj de 18,8 mm (3 g, todas iguales) que se mueven con la gravedad
 * dentro de una jaula esférica que gira. Se resuelve con dinámica basada en posiciones (PBD):
 *  1. Se predice cada bola con su velocidad y la gravedad.
 *  2. Se corrigen los solapes bola-bola y bola-pared varias veces por paso (cuerpos rígidos).
 *  3. La velocidad sale del desplazamiento real. En la pared se aplica rozamiento con la jaula
 *     que gira (la arrastra hacia arriba y la bola acaba cayendo en cascada) y un rebote pequeño
 *     (la madera de boj es poco elástica).
 * En el punto más bajo hay una compuerta: cuando se abre, la primera bola que pasa cae por la
 * trompeta hasta la copa. Así, en el bombo de premios es la física la que decide qué bola sale.
 *
 * Unidades: metros, segundos. y hacia arriba. Centro del bombo = (0, 0).
 */
class Drum(
    override val radius: Float,               // radio interior de la jaula (m)
    capacity: Int,
    override val ballR: Float = 0.0094f,      // radio de bola: 18,8 mm de diámetro
) : Bombo {
    override var n = 0
        private set
    override val es3D = false
    private var cap = capacity
    var x = FloatArray(cap); private set
    var y = FloatArray(cap); private set
    private var vx = FloatArray(cap)
    private var vy = FloatArray(cap)
    private var px = FloatArray(cap)
    private var py = FloatArray(cap)
    /** Identidad de cada bola (índice del premio, o -1 si la identidad se asigna al salir). */
    var id = IntArray(cap); private set

    /** Ángulo y velocidad angular de la jaula (rad, rad/s; positivo = antihorario). */
    override var angle = 0f
    override var omega = 0f
    override var targetOmega = 0f
    override var gateOpen = false

    /** Bolas que han salido por la compuerta (en orden de salida: la primera es la «de abajo»). */
    override val exited = ArrayDeque<Int>()

    private val g = 9.81f
    private val restitution = 0.25f
    private val wallFriction = 0.35f
    var gateWidthBalls = 1.9f
    private val gateHalf get() = (ballR * gateWidthBalls) / radius        // semiapertura angular de la compuerta (rad)

    // ---- Rejilla espacial ----
    private val cell = ballR * 2f
    private val gridN = ceil((radius * 2f + ballR * 8f) / cell).toInt() + 2
    private val cellStart = IntArray(gridN * gridN + 1)
    private var cellItems = IntArray(cap)
    private var cellOf = IntArray(cap)

    private fun ensure(k: Int) {
        if (k <= cap) return
        val c = max(k, cap * 2)
        x = x.copyOf(c); y = y.copyOf(c); vx = vx.copyOf(c); vy = vy.copyOf(c)
        px = px.copyOf(c); py = py.copyOf(c); id = id.copyOf(c)
        cellItems = IntArray(c); cellOf = IntArray(c)
        cap = c
    }

    override fun clear() { n = 0; exited.clear() }
    override fun idAt(i: Int) = id[i]
    override fun activo() = true
    override fun stepFor(dt: Float, budgetNs: Long): Float { step(dt, 48); return dt }

    /** Añade una bola en (bx, by) con velocidad (ux, uy). */
    override fun add(bx: Float, by: Float, ident: Int, ux: Float, uy: Float) {
        ensure(n + 1)
        x[n] = bx; y[n] = by; vx[n] = ux; vy[n] = uy; id[n] = ident
        n++
    }

    /** Quita la bola i (la última ocupa su sitio). Devuelve su identidad. */
    override fun removeAt(i: Int): Int {
        val ident = id[i]
        val last = n - 1
        x[i] = x[last]; y[i] = y[last]; vx[i] = vx[last]; vy[i] = vy[last]; id[i] = id[last]
        n = last
        return ident
    }

    /** ¿Cabe una bola nueva en (bx, by) sin solaparse con otra? (búsqueda directa, solo para la carga). */
    override fun isFree(bx: Float, by: Float): Boolean {
        val d2 = (ballR * 2f) * (ballR * 2f) * 0.95f
        if (bx * bx + by * by > (radius - ballR) * (radius - ballR)) return false
        for (i in 0 until n) {
            val dx = x[i] - bx; val dy = y[i] - by
            if (dx * dx + dy * dy < d2) return false
        }
        return true
    }

    /**
     * Coloca [ids] ya asentadas en el fondo (empaquetado hexagonal), para retomar un sorteo
     * o para avanzar muy deprisa sin ver la carga.
     */
    override fun fillSettled(ids: IntArray, rnd: Random) {
        clear()
        ensure(ids.size)
        val d = ballR * 2.02f
        val rowH = d * 0.866f
        var k = 0
        var row = 0
        var yy = -radius + ballR * 1.05f
        while (k < ids.size && yy < radius - ballR) {
            val half = sqrt(max(0f, (radius - ballR) * (radius - ballR) - yy * yy))
            var xx = -half + (if (row % 2 == 1) d / 2f else 0f)
            while (xx <= half && k < ids.size) {
                add(xx + (rnd.nextFloat() - 0.5f) * ballR * 0.05f, yy, ids[k++])
                xx += d
            }
            yy += rowH
            row++
        }
        // Las que no quepan (no debería pasar) se ponen encima
        while (k < ids.size) add((rnd.nextFloat() - 0.5f) * radius, radius * 0.5f, ids[k++])
    }

    private fun buildGrid() {
        cellStart.fill(0)
        val off = radius + ballR * 4f
        for (i in 0 until n) {
            var cx = ((x[i] + off) / cell).toInt()
            var cy = ((y[i] + off) / cell).toInt()
            cx = cx.coerceIn(0, gridN - 1); cy = cy.coerceIn(0, gridN - 1)
            val c = cy * gridN + cx
            cellOf[i] = c
            cellStart[c + 1]++
        }
        for (c in 0 until gridN * gridN) cellStart[c + 1] += cellStart[c]
        val fillPos = cellStart.copyOf(gridN * gridN)
        for (i in 0 until n) cellItems[fillPos[cellOf[i]]++] = i
    }

    private fun inGate(ax: Float, ay: Float): Boolean {
        if (!gateOpen) return false
        // Compuerta en el punto más bajo (ángulo -90°)
        val a = atan2(ay, ax)
        return abs(a + (PI / 2).toFloat()) < gateHalf
    }

    /** Avanza la simulación [dt] segundos (se divide en subpasos estables). */
    fun step(dt: Float, maxSub: Int = 24) {
        if (dt <= 0f) return
        // Aceleración/frenado suave de la jaula (motor)
        val acc = 0.8f
        omega += (targetOmega - omega).coerceIn(-acc * dt, acc * dt)
        angle = ((angle + omega * dt) % (2 * PI).toFloat())
        if (n == 0) return
        val subs = ceil(dt / (1f / 480f)).toInt().coerceIn(1, maxSub)
        val h = dt / subs
        repeat(subs) { substep(h); if (exited.isNotEmpty()) gateOpen = false }
    }

    private fun substep(h: Float) {
        val rr = ballR * 2f
        val rr2 = rr * rr
        val wallR = radius - ballR
        // 1) Predicción
        for (i in 0 until n) {
            px[i] = x[i]; py[i] = y[i]
            vy[i] -= g * h
            vx[i] *= 0.9995f; vy[i] *= 0.9995f
            x[i] += vx[i] * h; y[i] += vy[i] * h
        }
        // 2) Restricciones (solapes) con rejilla
        buildGrid()
        repeat(3) {
            for (c in 0 until gridN * gridN) {
                val s0 = cellStart[c]; val s1 = cellStart[c + 1]
                if (s0 == s1) continue
                val cx = c % gridN; val cy = c / gridN
                for (a in s0 until s1) {
                    val i = cellItems[a]
                    // vecinos: la propia celda (j > i) y 4 celdas "hacia delante"
                    for (k in 0 until 5) {
                        val nx = cx + NX[k]; val ny = cy + NY[k]
                        if (nx < 0 || ny < 0 || nx >= gridN || ny >= gridN) continue
                        val nc = ny * gridN + nx
                        val t0 = if (k == 0) a + 1 else cellStart[nc]
                        val t1 = cellStart[nc + 1]
                        for (b in t0 until t1) {
                            val j = cellItems[b]
                            val dx = x[j] - x[i]; val dy = y[j] - y[i]
                            val d2 = dx * dx + dy * dy
                            if (d2 < rr2 && d2 > 1e-12f) {
                                val d = sqrt(d2)
                                val corr = (rr - d) * 0.5f / d
                                val ox = dx * corr; val oy = dy * corr
                                x[i] -= ox; y[i] -= oy; x[j] += ox; y[j] += oy
                            }
                        }
                    }
                }
            }
            // Pared de la jaula (salvo en la compuerta abierta)
            for (i in 0 until n) {
                val d2 = x[i] * x[i] + y[i] * y[i]
                if (d2 > wallR * wallR) {
                    if (inGate(x[i], y[i])) continue
                    val d = sqrt(d2)
                    val f = wallR / d
                    x[i] *= f; y[i] *= f
                }
            }
        }
        // 3) Velocidades, rebote y rozamiento con la jaula que gira
        var i = 0
        while (i < n) {
            vx[i] = (x[i] - px[i]) / h
            vy[i] = (y[i] - py[i]) / h
            val d2 = x[i] * x[i] + y[i] * y[i]
            if (d2 > (wallR - ballR * 0.1f) * (wallR - ballR * 0.1f)) {
                val d = sqrt(d2)
                if (d > radius + ballR && inGate(x[i], y[i])) {
                    // Ha salido por la compuerta: a la trompeta
                    exited.addLast(id[i])
                    removeAt(i)
                    continue
                }
                if (!inGate(x[i], y[i])) {
                    val nx = x[i] / d; val ny = y[i] / d
                    val vn = vx[i] * nx + vy[i] * ny
                    if (vn > 0f) { vx[i] -= nx * vn * (1f + restitution); vy[i] -= ny * vn * (1f + restitution) }
                    // velocidad de la pared en ese punto: ω × r
                    val wx = -omega * y[i]; val wy = omega * x[i]
                    val tx = -ny; val ty = nx
                    val vt = (vx[i] - wx) * tx + (vy[i] - wy) * ty
                    vx[i] -= tx * vt * wallFriction; vy[i] -= ty * vt * wallFriction
                }
            }
            // límite de velocidad (estabilidad numérica)
            val v2 = vx[i] * vx[i] + vy[i] * vy[i]
            val vmax = 6f
            if (v2 > vmax * vmax) { val f = vmax / sqrt(v2); vx[i] *= f; vy[i] *= f }
            i++
        }
    }

    /** La bola más cercana a la compuerta (para forzar la salida si se atasca). */
    override fun lowest(): Int {
        var best = -1
        var by = Float.MAX_VALUE
        for (k in 0 until n) if (y[k] < by) { by = y[k]; best = k }
        return best
    }

    /** Copia de posiciones para dibujar: [x0, y0, x1, y1, …]. */
    override fun snapshot(out: FloatArray?): FloatArray {
        val a = if (out != null && out.size >= n * 2) out else FloatArray(max(16, n * 2 + 64))
        for (k in 0 until n) { a[2 * k] = x[k]; a[2 * k + 1] = y[k] }
        return a
    }

    /** Energía cinética media (para saber si las bolas ya están quietas). */
    fun meanSpeed(): Float {
        if (n == 0) return 0f
        var s = 0f
        for (k in 0 until n) s += abs(vx[k]) + abs(vy[k])
        return s / n
    }

    companion object {
        private val NX = intArrayOf(0, 1, -1, 0, 1)
        private val NY = intArrayOf(0, 0, 1, 1, 1)

        /** Radio de la sección 2D para que [count] bolas ocupen la fracción [fill] del círculo. */
        fun radiusFor(count: Int, fill: Float, ballR: Float = 0.0094f) = ballR * sqrt(count / fill)

        fun rpm(omega: Float) = omega * 60f / (2f * PI.toFloat())
        fun omegaFromRpm(rpm: Float) = rpm * 2f * PI.toFloat() / 60f

        @Suppress("unused")
        private fun clamp(v: Float, a: Float, b: Float) = min(b, max(a, v))
        @Suppress("unused")
        private fun ang(a: Float) = cos(a) + sin(a)
    }
}
