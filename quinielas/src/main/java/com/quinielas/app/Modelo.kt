package com.quinielas.app

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

// ======================================================================== DATOS

/** Una quiniela jugada: sus 14 signos y (desde ~2004) sus partidos. */
data class Quiniela(
    val fecha: String, val numero: Int, val temporada: String?, val signos: String, val pleno: String,
    val partidos: List<Pair<String, String>>,
)

/** Un partido de liga (1ª o 2ª). */
data class Liga(val fecha: String, val div: Int, val local: String, val visit: String, val gl: Int, val gv: Int)

/** Partido de una quiniela por jugar (o ya jugado si tiene marcador). */
data class PartidoProx(val pos: Int, val local: String, val visit: String, val signo: String, val marcador: String, val fecha: String)

data class Prox(val fecha: String, val numero: Int, val jornada: Int?, val partidos: List<PartidoProx>) {
    val pendiente: Boolean get() = partidos.any { it.marcador.isBlank() }
    /** Signos reales de los 14 partidos (' ' si aún no se ha jugado). */
    val real: String get() = String(CharArray(14) { i ->
        val x = partidos.getOrNull(i); val c = x?.signo?.trim()?.uppercase()?.firstOrNull()
        if (x != null && x.marcador.isNotBlank() && c != null && c in SIGNOS) c else ' '
    })
}

/** Escrutinio de unas columnas contra el resultado real. */
class Escrutinio(real: String, columnas: List<String>) {
    val jugados = real.count { it != ' ' }
    val aciertos: IntArray = IntArray(columnas.size) { n -> columnas[n].indices.count { i -> real.getOrNull(i)?.let { it != ' ' && it == columnas[n][i] } == true } }
    /** reparto[k] = columnas con k aciertos. */
    val reparto: IntArray = IntArray(15).also { r -> aciertos.forEach { r[it]++ } }
    val mejor: Int = aciertos.maxOrNull() ?: 0
    /** Categorías de premio actuales (14, 13, 12, 11, 10). */
    val premiadas: Int = (10..14).sumOf { reparto[it] }
}


const val SIGNOS = "1X2"

// ======================================================================== ESTADÍSTICA 1X2

/** Una «máscara» de quiniela: cuántos 1, X y 2 lleva en los 14 partidos. */
data class Mascara(val unos: Int, val equis: Int, val doses: Int) {
    val codigo: Int get() = unos * 16 + equis
    override fun toString() = "$unos-$equis-$doses"

    companion object {
        fun de(s: String) = Mascara(s.count { it == '1' }, s.count { it == 'X' }, s.count { it == '2' })
        fun deCodigo(c: Int) = Mascara(c / 16, c % 16, 14 - c / 16 - c % 16)
    }
}

class Estadistica(q: List<Quiniela>) {
    val total = q.size
    val media = DoubleArray(3)
    val minimo = IntArray(3) { 14 }
    val maximo = IntArray(3)
    /** Cuántas quinielas tuvieron k unos / equis / doses (k = 0…14). */
    val reparto = Array(3) { IntArray(15) }
    /** Máscaras ordenadas de más a menos frecuente. */
    val mascaras: List<Pair<Mascara, Int>>
    /** % de 1, X y 2 en cada posición (1…14). */
    val porPosicion = Array(14) { DoubleArray(3) }
    /** Racha más larga de signos iguales seguidos en cada quiniela (distribución). */
    val rachas = IntArray(15)
    val mediaUltimas: DoubleArray      // media de las últimas 5 temporadas (~380 jornadas)

    init {
        val cuenta = HashMap<Mascara, Int>()
        for (x in q) {
            val m = Mascara.de(x.signos)
            cuenta[m] = (cuenta[m] ?: 0) + 1
            val v = intArrayOf(m.unos, m.equis, m.doses)
            for (i in 0..2) {
                media[i] += v[i].toDouble(); reparto[i][v[i]]++
                if (v[i] < minimo[i]) minimo[i] = v[i]; if (v[i] > maximo[i]) maximo[i] = v[i]
            }
            x.signos.forEachIndexed { p, c -> porPosicion[p][SIGNOS.indexOf(c)] += 1.0 }
            var best = 1; var run = 1
            for (i in 1 until 14) { if (x.signos[i] == x.signos[i - 1]) { run++; if (run > best) best = run } else run = 1 }
            rachas[best]++
        }
        if (total > 0) {
            for (i in 0..2) media[i] /= total
            for (p in 0 until 14) for (i in 0..2) porPosicion[p][i] = porPosicion[p][i] * 100 / total
        }
        mascaras = cuenta.entries.sortedByDescending { it.value }.map { it.key to it.value }
        val ult = q.takeLast(380)
        mediaUltimas = DoubleArray(3) { i -> if (ult.isEmpty()) 0.0 else ult.sumOf { x -> x.signos.count { it == SIGNOS[i] } }.toDouble() / ult.size }
    }

    fun porcentaje(m: Mascara): Double = (mascaras.firstOrNull { it.first == m }?.second ?: 0) * 100.0 / total.coerceAtLeast(1)

    /** Las máscaras más frecuentes hasta cubrir [cobertura] % de las quinielas jugadas. */
    fun masFrecuentes(cobertura: Double): Set<Mascara> {
        val out = LinkedHashSet<Mascara>()
        var acc = 0
        for ((m, n) in mascaras) {
            if (acc * 100.0 / total >= cobertura) break
            out += m; acc += n
        }
        return out
    }

    fun cobertura(set: Set<Mascara>): Double = mascaras.filter { it.first in set }.sumOf { it.second } * 100.0 / total.coerceAtLeast(1)
}

// ======================================================================== EQUIPOS

/** Traduce los nombres de la quiniela (SELAE) a los de los resultados de liga (football-data). */
class Equipos(private val mapa: Map<String, String>, nombresLiga: Collection<String>) {
    private val liga = nombresLiga.toSet()
    private val porClave = nombresLiga.groupBy { clave(it) }

    fun deLiga(nombre: String): String? {
        if (nombre.contains("(f)", ignoreCase = true)) return null   // fútbol femenino: no hay datos de liga
        mapa[nombre]?.let { return it }
        if (nombre in liga) return nombre
        val k = clave(nombre)
        porClave[k]?.firstOrNull()?.let { return it }
        // por palabras: «R. Sociedad» ~ «Sociedad», «At. Madrid» ~ «Ath Madrid»
        val w = palabras(nombre)
        if (w.isEmpty()) return null
        return liga.maxByOrNull { l -> val lw = palabras(l); w.count { it in lw }.toDouble() / (w.size + lw.size) }
            ?.takeIf { l -> palabras(l).any { it in w && it.length >= 4 } }
    }

    companion object {
        private val VACIAS = setOf("real", "club", "cd", "ud", "sd", "rcd", "fc", "cf", "sad", "deportivo", "union", "de", "la", "el", "r", "at", "ath", "atletico", "c", "f")
        fun sinAcentos(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        fun clave(s: String) = sinAcentos(s).lowercase().replace(Regex("[^a-z0-9]"), "")
        fun palabras(s: String) = sinAcentos(s).lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in VACIAS }
    }
}

// ======================================================================== TENDENCIAS

/** Probabilidades de un partido a partir de las rachas (G = gana, E = empata, P = pierde). */
data class Pronostico(
    val p: DoubleArray,            // 1, X, 2 (suman 1)
    val formaLocal: String?, val formaVisit: String?,
    val k: Int,                    // longitud del patrón usado
    val nLocal: Int, val nVisit: Int,
    val siguienteLocal: IntArray?, val siguienteVisit: IntArray?,   // G, E, P de los equipos con el mismo patrón
    val nota: String,
)

/**
 * Para cada equipo, su serie de resultados (todas las temporadas de 1ª y 2ª). Para un patrón como
 * «E P G G E P P» (del más antiguo al más reciente) se buscan TODOS los momentos en que cualquier equipo
 * llegó con ese mismo patrón a un partido en casa (o fuera) y se cuenta qué pasó después.
 */
class Tendencias(ligas: List<Liga>) {
    private class R(val fecha: String, val casa: Boolean, val res: Char, val rival: String, val gf: Int, val gc: Int)

    private val serie = HashMap<String, MutableList<R>>()
    private val tabla = HashMap<String, IntArray>()   // "k|patrón|C/F" -> G, E, P
    val base = IntArray(3)                            // 1, X, 2 de todos los partidos de liga
    val equipos: Set<String> get() = serie.keys

    init {
        for (m in ligas) {
            val rl = if (m.gl > m.gv) 'G' else if (m.gl == m.gv) 'E' else 'P'
            val rv = if (rl == 'G') 'P' else if (rl == 'P') 'G' else 'E'
            serie.getOrPut(m.local) { ArrayList() } += R(m.fecha, true, rl, m.visit, m.gl, m.gv)
            serie.getOrPut(m.visit) { ArrayList() } += R(m.fecha, false, rv, m.local, m.gv, m.gl)
            base[if (rl == 'G') 0 else if (rl == 'E') 1 else 2]++
        }
        for ((_, s) in serie) {
            for (i in s.indices) {
                val sig = s[i]
                for (k in 2..MAX_K) {
                    if (i < k) break
                    val pat = buildString { for (j in i - k until i) append(s[j].res) }
                    val key = "$k|$pat|${if (sig.casa) 'C' else 'F'}"
                    val a = tabla.getOrPut(key) { IntArray(3) }
                    a["GEP".indexOf(sig.res)]++
                }
            }
        }
    }

    /** Un caso histórico: un equipo que llegó con la misma racha, y qué hizo en el partido siguiente. */
    data class Caso(val equipo: String, val fecha: String, val rival: String, val casa: Boolean, val gf: Int, val gc: Int, val res: Char)

    /**
     * Todos los equipos que llegaron con la racha [patron] a un partido en casa ([casa] = true) o fuera,
     * del más reciente al más antiguo.
     */
    fun casos(patron: String, casa: Boolean, antesDe: String = "9999"): List<Caso> {
        val k = patron.length
        val out = ArrayList<Caso>()
        for ((eq, s) in serie) {
            for (i in k until s.size) {
                val sig = s[i]
                if (sig.casa != casa || sig.fecha >= antesDe) continue
                var igual = true
                for (j in 0 until k) if (s[i - k + j].res != patron[j]) { igual = false; break }
                if (igual) out += Caso(eq, sig.fecha, sig.rival, sig.casa, sig.gf, sig.gc, sig.res)
            }
        }
        out.sortByDescending { it.fecha }
        return out
    }

    /** Últimos [k] resultados del equipo antes de [fecha] (más antiguo → más reciente). */
    fun forma(equipo: String, fecha: String, k: Int): String? {
        val s = serie[equipo] ?: return null
        val antes = s.filter { it.fecha < fecha }
        if (antes.size < k) return null
        return antes.takeLast(k).joinToString("") { it.res.toString() }
    }

    fun siguiente(k: Int, patron: String, casa: Boolean): IntArray = tabla["$k|$patron|${if (casa) 'C' else 'F'}"] ?: IntArray(3)

    /**
     * Probabilidad 1X2. Se usa el patrón más largo (hasta [kMax]) que tenga al menos [minN] casos; si no, se acorta.
     * p(1) = media de P(local gana en casa) y P(visitante pierde fuera); igual para X y 2.
     */
    fun pronostico(local: String?, visit: String?, fecha: String, kMax: Int, minN: Int = 30): Pronostico {
        val tot = base.sum().coerceAtLeast(1).toDouble()
        val pBase = DoubleArray(3) { base[it] / tot }
        if (local == null && visit == null) return Pronostico(pBase, null, null, 0, 0, 0, null, null, "Sin datos de liga: media de 1ª y 2ª")
        var k = kMax
        while (k >= 2) {
            val fl = local?.let { forma(it, fecha, k) }
            val fv = visit?.let { forma(it, fecha, k) }
            val sl = fl?.let { siguiente(k, it, true) }
            val sv = fv?.let { siguiente(k, it, false) }
            val nl = sl?.sum() ?: 0
            val nv = sv?.sum() ?: 0
            val ok = (sl == null || nl >= minN) && (sv == null || nv >= minN) && (sl != null || sv != null)
            if (ok || k == 2) {
                fun frac(a: IntArray?, def: DoubleArray): DoubleArray {
                    if (a == null) return def
                    val n = a.sum() + 3.0
                    return DoubleArray(3) { (a[it] + 1.0) / n }
                }
                // del lado del local: G,E,P en casa; del visitante: G,E,P fuera
                val gl = frac(sl, doubleArrayOf(pBase[0], pBase[1], pBase[2]))
                val gv = frac(sv, doubleArrayOf(pBase[2], pBase[1], pBase[0]))
                // Media de lo que dicen las dos rachas (probado con 2.797 partidos de 2023-2026 que el modelo no había
                // visto: mejora un poco a la media general; multiplicarlas exageraba y salía peor que no usar nada)
                val p = doubleArrayOf((gl[0] + gv[2]) / 2, (gl[1] + gv[1]) / 2, (gl[2] + gv[0]) / 2)
                val nota = when {
                    sl == null && sv == null -> "Sin racha suficiente"
                    sl == null -> "Solo hay datos del visitante"
                    sv == null -> "Solo hay datos del local"
                    k < kMax -> "Patrón acortado a $k para tener casos suficientes"
                    else -> "Patrón de $k partidos"
                }
                return Pronostico(p, fl, fv, k, nl, nv, sl, sv, nota)
            }
            k--
        }
        return Pronostico(pBase, null, null, 0, 0, 0, null, null, "Sin datos")
    }

    companion object { const val MAX_K = 7 }
}

// ======================================================================== GENERADOR

class Generacion(
    val columnas: IntArray,
    /** Con el modo «por máscara»: cuántas columnas válidas había en cada máscara (código → nº). */
    val porMascara: Map<Int, Long> = emptyMap(),      // cada columna: 14 signos en base 3 (0 = 1, 1 = X, 2 = 2), posición 0 = dígito más bajo
    val total: Long,             // columnas que cumplen todos los filtros
    val probAcierto: Double,     // probabilidad (según el modelo) de que la ganadora esté entre las elegidas
    val coberturaHist: Double,   // % de quinielas históricas cuya máscara estaba permitida
    /** Columnas elegidas antes del biorritmo (si se aplicó); null si no se aplicó. */
    val antesBio: Int? = null,
    /** Columnas quitadas por el biorritmo en cada partido (posición → nº). */
    val quitadasBio: IntArray = IntArray(14),
) {
    /**
     * Biorritmo sobre las columnas que ya han pasado máscaras, rachas y probabilidad: se quita cada columna que
     * da la victoria a un equipo claramente «bajo» ([vetados]: bits de signos vetados por partido).
     */
    fun conBiorritmo(vetados: IntArray, probs: Array<DoubleArray>): Generacion {
        val q = IntArray(14)
        val quedan = columnas.filter { c ->
            var ok = true; var cc = c
            for (i in 0 until 14) { val sg = cc % 3; cc /= 3; if (vetados[i] and (1 shl sg) != 0) { q[i]++; ok = false } }
            ok
        }.toIntArray()
        val p = quedan.sumOf { c -> var cc = c; var x = 1.0; for (i in 0 until 14) { x *= probs[i][cc % 3]; cc /= 3 }; x }
        return Generacion(quedan, porMascara, total, p, coberturaHist, columnas.size, q)
    }
}

object Generador {
    fun signo(col: Int, pos: Int): Char { var c = col; repeat(pos) { c /= 3 }; return SIGNOS[c % 3] }
    fun texto(col: Int): String = buildString { var c = col; repeat(14) { append(SIGNOS[c % 3]); c /= 3 } }

    /**
     * Recorre todas las columnas posibles con los signos permitidos en cada partido y se queda con las que
     * cumplen la máscara (nº de 1, X y 2) y la racha máxima. Se guardan como máximo [tope] (las más probables).
     *
     * @param permitidos bits por partido: 1 = «1», 2 = «X», 4 = «2»
     * @param probs probabilidad 1X2 de cada partido (para ordenar y estimar el acierto)
     */
    fun generar(
        permitidos: IntArray, mascaras: Set<Mascara>, rachaMax: Int, probs: Array<DoubleArray>,
        tope: Int, modo: Modo, rnd: Random, coberturaHist: Double,
    ): Generacion {
        val ok = BooleanArray(16 * 16)
        for (m in mascaras) ok[m.codigo] = true
        val maxU = mascaras.maxOfOrNull { it.unos } ?: 14
        val maxX = mascaras.maxOfOrNull { it.equis } ?: 14
        val maxD = mascaras.maxOfOrNull { it.doses } ?: 14
        val lp = Array(14) { i -> DoubleArray(3) { j -> ln(probs[i][j].coerceAtLeast(1e-9)) } }
        // Las mejores [tope] por probabilidad: montículo mínimo por puntuación
        val heapCol = IntArray(tope.coerceAtLeast(1)); val heapSc = DoubleArray(tope.coerceAtLeast(1)); var hn = 0
        // Muestreo al azar uniforme entre las válidas (reservoir)
        val res = IntArray(tope.coerceAtLeast(1)); var vistos = 0L
        var total = 0L
        var masa = 0.0
        // Las [tope] mejores DENTRO de cada máscara (rachas + máscaras combinadas)
        val grupoCol = HashMap<Int, IntArray>(); val grupoSc = HashMap<Int, DoubleArray>(); val grupoN = HashMap<Int, Int>()
        val grupoTotal = HashMap<Int, Long>()
        fun pushGrupo(code: Int, col: Int, sc: Double) {
            grupoTotal[code] = (grupoTotal[code] ?: 0L) + 1
            val cs = grupoCol.getOrPut(code) { IntArray(minOf(tope, 1024)) }; val ss = grupoSc.getOrPut(code) { DoubleArray(minOf(tope, 1024)) }
            var n = grupoN[code] ?: 0
            // montículo mínimo por grupo (crece hasta [tope])
            if (n < tope) {
                var a = cs; var b = ss
                if (n == a.size) { a = a.copyOf(minOf(tope, n * 2)); b = b.copyOf(a.size); grupoCol[code] = a; grupoSc[code] = b }
                var i = n++; a[i] = col; b[i] = sc; grupoN[code] = n
                while (i > 0) { val p = (i - 1) / 2; if (b[p] <= b[i]) break
                    val tc = a[p]; a[p] = a[i]; a[i] = tc; val ts = b[p]; b[p] = b[i]; b[i] = ts; i = p }
                return
            }
            if (sc <= ss[0]) return
            cs[0] = col; ss[0] = sc
            var i = 0
            while (true) { val l = 2 * i + 1; val r = l + 1; var m = i
                if (l < n && ss[l] < ss[m]) m = l; if (r < n && ss[r] < ss[m]) m = r
                if (m == i) break
                val tc = cs[m]; cs[m] = cs[i]; cs[i] = tc; val ts = ss[m]; ss[m] = ss[i]; ss[i] = ts; i = m }
        }
        val pow3 = IntArray(14).also { it[0] = 1; for (i in 1 until 14) it[i] = it[i - 1] * 3 }

        fun push(col: Int, sc: Double) {
            if (hn < heapCol.size) {
                var i = hn++; heapCol[i] = col; heapSc[i] = sc
                while (i > 0) { val p = (i - 1) / 2; if (heapSc[p] <= heapSc[i]) break
                    val tc = heapCol[p]; heapCol[p] = heapCol[i]; heapCol[i] = tc; val ts = heapSc[p]; heapSc[p] = heapSc[i]; heapSc[i] = ts; i = p }
            } else if (sc > heapSc[0]) {
                heapCol[0] = col; heapSc[0] = sc
                var i = 0
                while (true) { val l = 2 * i + 1; val r = l + 1; var m = i
                    if (l < hn && heapSc[l] < heapSc[m]) m = l; if (r < hn && heapSc[r] < heapSc[m]) m = r
                    if (m == i) break
                    val tc = heapCol[m]; heapCol[m] = heapCol[i]; heapCol[i] = tc; val ts = heapSc[m]; heapSc[m] = heapSc[i]; heapSc[i] = ts; i = m }
            }
        }

        fun rec(pos: Int, col: Int, u: Int, x: Int, d: Int, last: Int, run: Int, sc: Double) {
            if (pos == 14) {
                if (!ok[u * 16 + x]) return
                total++
                masa += exp(sc)
                when (modo) {
                    Modo.PROBABLES, Modo.TODAS -> push(col, sc)
                    Modo.POR_MASCARA -> pushGrupo(u * 16 + x, col, sc)
                    Modo.AZAR -> { vistos++; if (vistos <= res.size) res[(vistos - 1).toInt()] = col
                        else { val j = (rnd.nextDouble() * vistos).toLong(); if (j < res.size) res[j.toInt()] = col } }
                }
                return
            }
            val restantes = 13 - pos
            for (s in 0..2) {
                if (permitidos[pos] and (1 shl s) == 0) continue
                val nu = u + if (s == 0) 1 else 0; val nx = x + if (s == 1) 1 else 0; val nd = d + if (s == 2) 1 else 0
                if (nu > maxU || nx > maxX || nd > maxD) continue
                val nr = if (s == last) run + 1 else 1
                if (rachaMax in 1..13 && nr > rachaMax) continue
                // poda: ¿aún se puede llegar a alguna máscara permitida?
                if (restantes < 6) {
                    var alguna = false
                    for (m in mascaras) if (m.unos >= nu && m.equis >= nx && m.doses >= nd && m.unos - nu + m.equis - nx + m.doses - nd == restantes) { alguna = true; break }
                    if (!alguna) continue
                }
                rec(pos + 1, col + s * pow3[pos], nu, nx, nd, s, nr, sc + lp[pos][s])
            }
        }
        rec(0, 0, 0, 0, 0, -1, 0, 0.0)

        val orden = mascaras.toList()      // de más a menos frecuente
        val elegidas = when (modo) {
            Modo.POR_MASCARA -> {
                val out = ArrayList<Int>()
                for (m in orden) {
                    val n = grupoN[m.codigo] ?: continue
                    val cs = grupoCol[m.codigo]!!; val ss = grupoSc[m.codigo]!!
                    (0 until n).sortedByDescending { ss[it] }.forEach { out += cs[it] }
                }
                out.toIntArray()
            }
            Modo.AZAR -> res.copyOf(minOf(res.size.toLong(), vistos).toInt())
            else -> {
                val idx = (0 until hn).sortedByDescending { heapSc[it] }
                IntArray(idx.size) { heapCol[idx[it]] }
            }
        }
        val pElegidas = elegidas.sumOf { c -> var s = 0.0; for (i in 0 until 14) s += lp[i][(c / pow3[i]) % 3]; exp(s) }
        return Generacion(elegidas, grupoTotal, total, if (modo == Modo.TODAS && total <= tope) masa else pElegidas, coberturaHist)
    }

    enum class Modo { TODAS, PROBABLES, AZAR, POR_MASCARA }

    /** Cuántas columnas habría acertado cada máscara permitida en el histórico (para validar el filtro). */
    fun aciertosHistoricos(q: List<Quiniela>, mascaras: Set<Mascara>, rachaMax: Int): Double {
        if (q.isEmpty()) return 0.0
        val n = q.count { x ->
            Mascara.de(x.signos) in mascaras && (rachaMax !in 1..13 || run(x.signos) <= rachaMax)
        }
        return n * 100.0 / q.size
    }

    private fun run(s: String): Int { var b = 1; var r = 1; for (i in 1 until s.length) { if (s[i] == s[i - 1]) { r++; if (r > b) b = r } else r = 1 }; return b }

    const val PRECIO = 0.75
}


// ======================================================================== BIORRITMO

/** Jugador de la plantilla actual: fecha de nacimiento y partidos como titular esta temporada. */
data class Jugador(val nombre: String, val nacimiento: java.time.LocalDate, val titular: Int, val partidos: Int, val puesto: String)

data class Plantilla(val nombre: String, val liga: Int, val jugadores: List<Jugador>)

/** Biorritmo medio de un equipo: físico (23 días), emocional (28) e intelectual (33), de −1 a +1. */
data class Bio(val fisico: Double, val emocional: Double, val intelectual: Double, val jugadores: Int) {
    val media: Double get() = (fisico + emocional + intelectual) / 3
}

/**
 * Teoría del biorritmo: tres ciclos que empiezan el día del nacimiento. Se calcula el de los 11 jugadores
 * que más veces han sido titulares esta temporada y se hace la media para el día del partido.
 * (Es una teoría sin respaldo científico; aquí se usa solo como filtro para reducir columnas.)
 */
object Biorritmo {
    fun ciclos(nac: java.time.LocalDate, dia: java.time.LocalDate): DoubleArray {
        val t = java.time.temporal.ChronoUnit.DAYS.between(nac, dia).toDouble()
        return doubleArrayOf(kotlin.math.sin(2 * Math.PI * t / 23), kotlin.math.sin(2 * Math.PI * t / 28), kotlin.math.sin(2 * Math.PI * t / 33))
    }

    fun equipo(p: Plantilla, dia: java.time.LocalDate, n: Int = 11): Bio? {
        val xi = p.jugadores.filter { it.titular > 0 }.sortedByDescending { it.titular }.take(n)
            .ifEmpty { p.jugadores.sortedByDescending { it.partidos }.take(n) }
        if (xi.isEmpty()) return null
        val a = DoubleArray(3)
        for (j in xi) { val c = ciclos(j.nacimiento, dia); for (i in 0..2) a[i] += c[i] }
        return Bio(a[0] / xi.size, a[1] / xi.size, a[2] / xi.size, xi.size)
    }
}

/** Busca la plantilla de un equipo de la quiniela (los nombres de SELAE y de ESPN no coinciden del todo). */
class Plantillas(val todas: List<Plantilla>) {
    fun de(nombre: String): Plantilla? {
        if (nombre.contains("(f)", ignoreCase = true)) return null
        val w = fichas(nombre)
        if (w.isEmpty()) return null
        var mejor: Plantilla? = null; var ms = 0.0; var segundo = 0.0
        for (p in todas) {
            val pw = fichas(p.nombre)
            val inter = w.count { it in pw }.toDouble()
            val sc = inter / (w.size + pw.size - inter)
            if (sc > ms) { segundo = ms; ms = sc; mejor = p } else if (sc > segundo) segundo = sc
        }
        // Vale si todas las palabras del nombre de la quiniela están en el de la plantilla («Betis» → «Real Betis»)
        // o si se parecen mucho; así «Sporting de Portugal» no se confunde con el Sporting de Gijón.
        val m = mejor ?: return null
        val sub = w.all { it in fichas(m.nombre) }
        return m.takeIf { (sub || ms >= 0.67) && ms > segundo }
    }

    companion object {
        private val VACIAS = setOf("club", "cd", "ud", "sd", "rcd", "rc", "fc", "cf", "sad", "de", "la", "el", "c", "f")
        fun fichas(s: String): Set<String> = Equipos.sinAcentos(s).lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in VACIAS }
            .map { when (it) { "at", "atl", "atletico" -> "atl"; "athletic", "ath" -> "ath"; "r", "real" -> "real"; "vigo" -> "celta"; "gijon" -> "sporting"; "ii" -> "b"; else -> it } }
            .toSet()
    }
}
