package com.quinielas.app

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Superficie de dibujo mínima. En el móvil es el Canvas de Android; en las pruebas, una imagen de Java.
 * Así el boleto se dibuja con el mismo código en los dos sitios y se puede revisar como imagen.
 */
interface Lienzo {
    enum class Fuente { MAQUINA, IMPRENTA, ESTRECHA, ESTRECHA_NEGRITA, LAPIZ }

    fun papel(l: Float, t: Float, r: Float, b: Float, semilla: Int)
    fun linea(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, ancho: Float)
    fun curva(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, color: Int, ancho: Float)
    fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, relleno: Boolean, ancho: Float = 1f)
    fun circulo(cx: Float, cy: Float, r: Float, color: Int)
    fun mancha(cx: Float, cy: Float, r: Float, color: Int)
    /** alinear: 0 izquierda, 1 centro, 2 derecha. giro en grados alrededor de (x, y). */
    fun texto(s: String, x: Float, y: Float, f: Fuente, tam: Float, color: Int, alinear: Int = 0, espaciado: Float = 0f, giro: Float = 0f)
    fun medir(s: String, f: Fuente, tam: Float): Float
}

/** Los datos de un boleto. */
class DatosBoleto(
    val numero: Int, val primera: Int, val partidos: List<Pair<String, String>>, val pleno: Pair<String, String>?,
    val columnas: List<String>, val jornada: Int?, val fecha: String, val margen: String?,
)

/**
 * BOLETO DE QUINIELA DE 1984, dibujado a mano (sin imágenes): papel amarillento con grano, fibras y manchas,
 * tinta naranja de imprenta algo irregular, cabecera con partidos, clave, jornada, fecha y número de
 * resguardo, el aviso vertical, los 14 partidos con puntos suspensivos, el pronóstico escrito en el margen
 * y 8 apuestas con las casillas tachadas a lápiz (cada cruz son varias pasadas de grafito, ninguna igual).
 *
 * Coordenadas lógicas: 1000 × 750.
 */
object Boleto80 {
    const val ANCHO = 1000f
    const val ALTO = 750f

    private const val NARANJA = 0xFFE4572E.toInt()
    private const val NARANJA_SUAVE = 0x55E4572E
    private const val NEGRO = 0xFF2A2420.toInt()
    private const val ROJO_SELLO = 0xFFE03C31.toInt()
    private const val GRAFITO = 0xFF3C3C44.toInt()
    private const val TINTA_BOLI = 0xFF8E1F22.toInt()

    // Rejilla
    private const val X_NOMBRE = 150f
    private const val X_APUESTAS = 432f
    private const val ANCHO_APUESTA = 67.5f
    private const val Y_CABECERA = 178f
    private const val Y_FILAS = 212f
    private const val ALTO_FILA = 33.5f
    private const val CASILLA = 17f

    private fun alpha(c: Int, a: Float) = (((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (c and 0xFFFFFF))

    fun dibujar(l: Lienzo, d: DatosBoleto) {
        val semilla = d.columnas.fold(d.numero * 7919) { a, c -> a * 31 + c.hashCode() }
        val rnd = Random(semilla)
        val serie = (semilla and 0x7FFFFFFF) % 90_000_000 + 10_000_000
        papel(l, semilla, rnd)
        sello(l, d, serie)
        cabecera(l, d, serie, rnd)
        aviso(l)
        rejilla(l, rnd)
        partidos(l, d, rnd)
        cruces(l, d, rnd)
        pie(l, d)
        suciedad(l, rnd)
    }

    // ------------------------------------------------------------ papel
    private fun papel(l: Lienzo, semilla: Int, rnd: Random) {
        l.papel(18f, 14f, 986f, 736f, semilla)
        // Manchas de humedad y de los dedos
        repeat(5) { l.mancha(60f + rnd.nextFloat() * 880f, 40f + rnd.nextFloat() * 670f, 60f + rnd.nextFloat() * 160f, 0x14806020) }
        l.mancha(950f, 700f, 180f, 0x22705020)
        // Matriz troquelada a la izquierda (el talón arrancado)
        for (k in 0 until 26) {
            val y = 24f + k * 7.4f
            l.rect(22f, y, 30f, y + 3.4f, 0x66806040, relleno = true)
        }
        l.linea(36f, 18f, 36f, 210f, 0x33806040, 1f)
        // Borde del papel algo más oscuro
        l.rect(18f, 14f, 986f, 736f, 0x40806030, relleno = false, ancho = 3f)
    }

    // ------------------------------------------------------------ sello de las apuestas (vertical, rojo)
    private fun sello(l: Lienzo, d: DatosBoleto, serie: Int) {
        val L = 52f; val T = 22f; val R = 196f; val B = 206f
        l.rect(L, T, R, B, ROJO_SELLO, relleno = true)
        // borde dentado de sello
        var x = L; while (x < R) { l.circulo(x, T, 3.2f, 0xFFEFE3B6.toInt()); l.circulo(x, B, 3.2f, 0xFFEFE3B6.toInt()); x += 9f }
        var y = T; while (y < B) { l.circulo(L, y, 3.2f, 0xFFEFE3B6.toInt()); l.circulo(R, y, 3.2f, 0xFFEFE3B6.toInt()); y += 9f }
        l.rect(L + 14f, T + 10f, R - 14f, B - 10f, 0xFFF8EDE6.toInt(), relleno = true)
        l.rect(L + 14f, T + 10f, R - 14f, B - 10f, ROJO_SELLO, relleno = false, ancho = 1.4f)
        // grecas laterales
        for (k in 0..16) { val yy = T + 16f + k * 10.5f; l.texto("◆", L + 7f, yy, Lienzo.Fuente.ESTRECHA, 7f, 0xFFF8EDE6.toInt(), 1); l.texto("◆", R - 7f, yy, Lienzo.Fuente.ESTRECHA, 7f, 0xFFF8EDE6.toInt(), 1) }
        // texto vertical
        l.texto("${d.columnas.size}", 98f, 64f, Lienzo.Fuente.IMPRENTA, 34f, ROJO_SELLO, 1, giro = 90f)
        l.texto("APUESTAS", 112f, 112f, Lienzo.Fuente.IMPRENTA, 17f, ROJO_SELLO, 1, espaciado = 1.5f, giro = 90f)
        l.texto("ZA$serie".take(9), 146f, 114f, Lienzo.Fuente.MAQUINA, 21f, NEGRO, 1, espaciado = 0.5f, giro = 90f)
    }

    // ------------------------------------------------------------ cabecera
    private fun cabecera(l: Lienzo, d: DatosBoleto, serie: Int, rnd: Random) {
        val T = 30f; val B = 168f
        tinta(l, 212f, T, 975f, T, NEGRO, 2f, rnd); tinta(l, 212f, B, 975f, B, NEGRO, 2f, rnd)
        tinta(l, 212f, T, 212f, B, NEGRO, 2f, rnd); tinta(l, 975f, T, 975f, B, NEGRO, 2f, rnd)
        tinta(l, 590f, T, 590f, B, NEGRO, 2f, rnd); tinta(l, 735f, T, 735f, B, NEGRO, 2f, rnd)
        // Partidos (el pleno al 15 ocupa el sitio de los «reservas»)
        l.texto("PARTIDOS RESERVAS:", 232f, 58f, Lienzo.Fuente.ESTRECHA_NEGRITA, 20f, NEGRO, espaciado = 0.6f)
        val p = d.pleno
        l.texto("1.º ${p?.let { "${nombre(it.first)}- ${nombre(it.second)}." } ?: "—"}", 262f, 84f, Lienzo.Fuente.ESTRECHA, 20f, NEGRO)
        l.texto("2.º —", 262f, 108f, Lienzo.Fuente.ESTRECHA, 20f, NEGRO)
        l.texto("3.º —", 262f, 132f, Lienzo.Fuente.ESTRECHA, 20f, NEGRO)
        l.texto("RECLAMACIONES: (Véase el dorso)", 232f, 160f, Lienzo.Fuente.ESTRECHA_NEGRITA, 15f, NEGRO)
        // Clave, jornada, fecha
        val clave = "ABCDEFGH"[serie % 8]
        l.texto("Clave $clave", 662f, 62f, Lienzo.Fuente.IMPRENTA, 22f, NEGRO, 1)
        tinta(l, 590f, 74f, 735f, 74f, NEGRO, 1.6f, rnd)
        l.texto("Jornada", 662f, 94f, Lienzo.Fuente.ESTRECHA, 16f, NEGRO, 1)
        l.texto("${d.jornada ?: "—"}", 662f, 124f, Lienzo.Fuente.IMPRENTA, 30f, NEGRO, 1)
        tinta(l, 590f, 134f, 735f, 134f, NEGRO, 1.6f, rnd)
        val f = runCatching { java.time.LocalDate.parse(d.fecha.take(10)) }.getOrNull()
        l.texto(f?.let { "${it.dayOfMonth}-${it.monthValue}-${it.year}" } ?: "", 662f, 160f, Lienzo.Fuente.ESTRECHA, 20f, NEGRO, 1)
        // Resguardo con fondo de seguridad ondulado y número en rojo
        for (k in 0 until 9) {
            val y0 = 40f + k * 9f
            var x = 742f
            while (x < 968f) { l.curva(x, y0, x + 7f, y0 - 4f, x + 14f, y0, alpha(NEGRO, 0.45f), 1f); l.curva(x + 14f, y0, x + 21f, y0 + 4f, x + 28f, y0, alpha(NEGRO, 0.45f), 1f); x += 28f }
        }
        l.texto("$serie", 855f, 98f, Lienzo.Fuente.MAQUINA, 40f, alpha(0xFFC8352C.toInt(), 0.85f), 1, espaciado = 2f)
        tinta(l, 735f, 122f, 975f, 122f, NEGRO, 1.6f, rnd)
        l.texto("RESGUARDO", 855f, 156f, Lienzo.Fuente.ESTRECHA, 30f, NEGRO, 1, espaciado = 1.5f)
    }

    // ------------------------------------------------------------ aviso vertical
    private fun aviso(l: Lienzo) {
        l.texto("ATENCION", 92f, 600f, Lienzo.Fuente.IMPRENTA, 20f, NEGRO, 1, espaciado = 2f, giro = -90f)
        l.texto("PARA LA JORNADA 17.ª Y SUCESIVAS SOLO PODRAN", 116f, 470f, Lienzo.Fuente.ESTRECHA_NEGRITA, 14f, NEGRO, 1, giro = -90f)
        l.texto("FORMULARSE PRONOSTICOS UTILIZANDO EL SIGNO «X»", 132f, 470f, Lienzo.Fuente.ESTRECHA_NEGRITA, 14f, NEGRO, 1, giro = -90f)
    }

    // ------------------------------------------------------------ rejilla de casillas
    private fun xCasilla(a: Int, s: Int) = X_APUESTAS + a * ANCHO_APUESTA + 4f + s * (ANCHO_APUESTA - 8f) / 3f + (ANCHO_APUESTA - 8f) / 6f
    private fun yFila(i: Int) = Y_FILAS + i * ALTO_FILA + ALTO_FILA / 2

    private fun rejilla(l: Lienzo, rnd: Random) {
        // Cabecera 1 X 2 repetida
        for (a in 0 until 8) for (s in 0..2) {
            l.texto(SIGNOS[s].toString(), xCasilla(a, s), Y_CABECERA + 26f, Lienzo.Fuente.IMPRENTA, 26f, NARANJA, 1)
        }
        // Líneas verticales gruesas entre apuestas
        for (a in 0..8) {
            val x = X_APUESTAS + a * ANCHO_APUESTA
            tinta(l, x, Y_CABECERA, x, Y_FILAS + 14 * ALTO_FILA + 34f, NARANJA, if (a == 0 || a == 8) 2.6f else 2.2f, rnd)
        }
        tinta(l, X_APUESTAS, Y_CABECERA, X_APUESTAS + 8 * ANCHO_APUESTA, Y_CABECERA, NARANJA, 2.4f, rnd)
        tinta(l, X_APUESTAS, Y_FILAS - 2f, X_APUESTAS + 8 * ANCHO_APUESTA, Y_FILAS - 2f, NARANJA, 1.4f, rnd)
        // Casillas
        for (i in 0 until 14) for (a in 0 until 8) for (s in 0..2) {
            val cx = xCasilla(a, s); val cy = yFila(i)
            val h = CASILLA / 2
            val j = { rnd.nextFloat() * 0.6f - 0.3f }
            tinta(l, cx - h + j(), cy - h, cx + h + j(), cy - h, NARANJA, 1.5f, rnd)
            tinta(l, cx - h, cy + h + j(), cx + h, cy + h + j(), NARANJA, 1.5f, rnd)
            tinta(l, cx - h, cy - h + j(), cx - h, cy + h + j(), NARANJA, 1.5f, rnd)
            tinta(l, cx + h + j(), cy - h, cx + h + j(), cy + h, NARANJA, 1.5f, rnd)
        }
        // Separadores cada 4 partidos (de lado a lado)
        for (i in listOf(4, 8, 12)) {
            val y = Y_FILAS + i * ALTO_FILA
            tinta(l, X_NOMBRE - 6f, y, X_APUESTAS + 8 * ANCHO_APUESTA, y, NARANJA, 2.4f, rnd)
        }
    }

    // ------------------------------------------------------------ partidos y pronóstico en el margen
    private fun partidos(l: Lienzo, d: DatosBoleto, rnd: Random) {
        for (i in 0 until 14) {
            val y = yFila(i) + 7f
            val p = d.partidos.getOrNull(i)
            val txt = "${i + 1} " + (p?.let { "${nombre(it.first)}-${nombre(it.second)}" } ?: "")
            l.texto(txt, X_NOMBRE, y, Lienzo.Fuente.ESTRECHA_NEGRITA, 21f, NEGRO)
            var x = X_NOMBRE + l.medir(txt, Lienzo.Fuente.ESTRECHA_NEGRITA, 21f) + 4f
            while (x < 396f) { l.circulo(x, y - 1.5f, 1.1f, alpha(NEGRO, 0.85f)); x += 6f }
            // pronóstico apuntado a mano (bolígrafo rojo oscuro, cada uno algo distinto)
            val s = d.margen?.getOrNull(i) ?: continue
            if (s == ' ') continue
            if (s == 'X') cruz(l, 412f, yFila(i), 24f, rnd, TINTA_BOLI, pasadas = 2, grosor = 2.8f, intensidad = 1.6f)
            else repeat(2) { k ->   // repasado con el bolígrafo
                l.texto(if (s == '1') "1" else "2", 410f + rnd.nextFloat() * 2 + k * 0.8f, y + 10f, Lienzo.Fuente.LAPIZ, 58f, alpha(TINTA_BOLI, 0.85f), 1,
                    giro = rnd.nextFloat() * 14f - 7f)
            }
        }
    }

    // ------------------------------------------------------------ cruces a lápiz
    private fun cruces(l: Lienzo, d: DatosBoleto, rnd: Random) {
        d.columnas.forEachIndexed { a, col ->
            if (a >= 8) return@forEachIndexed
            for (i in 0 until 14) {
                val s = SIGNOS.indexOf(col[i]); if (s < 0) continue
                val dx = rnd.nextFloat() * 4f - 2f; val dy = rnd.nextFloat() * 4f - 2f
                cruz(l, xCasilla(a, s) + dx, yFila(i) + dy, CASILLA * (1.05f + rnd.nextFloat() * 0.35f), rnd, GRAFITO, pasadas = 3, grosor = 1.5f, intensidad = 1.25f)
            }
        }
    }

    /**
     * Una cruz a lápiz: dos trazos algo curvos que se pasan del borde, cada uno repasado varias veces
     * con presión y grosor distintos, y grano de grafito a lo largo del trazo.
     */
    fun cruz(l: Lienzo, cx: Float, cy: Float, tam: Float, rnd: Random, color: Int, pasadas: Int, grosor: Float, intensidad: Float = 1f) {
        val h = tam / 2
        val giro = (rnd.nextFloat() - 0.5f) * 0.35f
        fun rot(x: Float, y: Float): Pair<Float, Float> = (cx + x * cos(giro) - y * sin(giro)) to (cy + x * sin(giro) + y * cos(giro))
        val trazos = listOf(
            floatArrayOf(-h * (0.9f + rnd.nextFloat() * 0.3f), -h * (1f + rnd.nextFloat() * 0.25f), h * (0.95f + rnd.nextFloat() * 0.3f), h * (0.9f + rnd.nextFloat() * 0.3f)),
            floatArrayOf(h * (0.85f + rnd.nextFloat() * 0.3f), -h * (1.05f + rnd.nextFloat() * 0.2f), -h * (0.9f + rnd.nextFloat() * 0.3f), h * (1f + rnd.nextFloat() * 0.25f)),
        )
        for (t in trazos) {
            val (x0, y0) = rot(t[0], t[1]); val (x1, y1) = rot(t[2], t[3])
            val len = hypot(x1 - x0, y1 - y0)
            val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
            repeat(pasadas) { k ->
                val off = (rnd.nextFloat() - 0.5f) * 1.6f
                val bow = (rnd.nextFloat() - 0.5f) * len * 0.12f
                val mx = (x0 + x1) / 2 + nx * bow; val my = (y0 + y1) / 2 + ny * bow
                l.curva(x0 + nx * off, y0 + ny * off, mx + nx * off, my + ny * off, x1 + nx * off + (rnd.nextFloat() - 0.5f) * 2, y1 + ny * off,
                    alpha(color, (0.35f + rnd.nextFloat() * 0.4f - k * 0.05f) * intensidad), grosor * (0.6f + rnd.nextFloat() * 0.7f))
            }
            // grano del grafito
            val n = (len / 1.6f).toInt()
            for (q in 0..n) {
                if (rnd.nextFloat() < 0.45f) continue
                val u = q.toFloat() / n
                val bx = x0 + (x1 - x0) * u + nx * (rnd.nextFloat() - 0.5f) * 2.2f
                val by = y0 + (y1 - y0) * u + ny * (rnd.nextFloat() - 0.5f) * 2.2f
                l.circulo(bx, by, 0.35f + rnd.nextFloat() * 0.5f, alpha(color, 0.2f + rnd.nextFloat() * 0.35f))
            }
        }
    }

    // ------------------------------------------------------------ pie
    private fun pie(l: Lienzo, d: DatosBoleto) {
        val y0 = Y_FILAS + 14 * ALTO_FILA + 2f
        l.rect(X_NOMBRE - 6f, y0, X_APUESTAS + 8 * ANCHO_APUESTA, y0 + 32f, NARANJA_SUAVE, relleno = true)
        l.rect(X_NOMBRE - 6f, y0, X_APUESTAS + 8 * ANCHO_APUESTA, y0 + 32f, NARANJA, relleno = false, ancho = 2f)
        l.texto("APUESTAS", (X_NOMBRE + X_APUESTAS) / 2 - 8f, y0 + 25f, Lienzo.Fuente.IMPRENTA, 22f, NARANJA, 1, espaciado = 6f)
        for (a in 0 until 8) {
            val n = d.primera + a
            l.texto("$n.ª", X_APUESTAS + a * ANCHO_APUESTA + ANCHO_APUESTA / 2, y0 + 25f, Lienzo.Fuente.IMPRENTA, 20f,
                if (a < d.columnas.size) NARANJA else alpha(NARANJA, 0.4f), 1)
        }
    }

    /** Roces de lápiz y de goma que suelen quedar en un boleto usado. */
    private fun suciedad(l: Lienzo, rnd: Random) {
        repeat(4) {
            val x = 450f + rnd.nextFloat() * 500f; val y = 230f + rnd.nextFloat() * 420f
            l.curva(x, y, x + 20f, y - 10f + rnd.nextFloat() * 20f, x + 40f + rnd.nextFloat() * 30f, y + rnd.nextFloat() * 20f, alpha(GRAFITO, 0.12f), 1f)
        }
    }

    /** Línea de imprenta: la tinta no es perfecta (pequeñas variaciones de grosor y de intensidad). */
    private fun tinta(l: Lienzo, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, ancho: Float, rnd: Random) {
        l.linea(x0, y0, x1, y1, alpha(color, 0.82f + rnd.nextFloat() * 0.18f), ancho * (0.9f + rnd.nextFloat() * 0.2f))
    }

    /** Nombres como se imprimían: «R. MADRID», «AT. BILBAO», «D. CORUÑA», «R. GIJON». */
    fun nombre(n: String): String {
        var s = Equipos.sinAcentos(n).uppercase().replace(Regex("\\s*\\((M|F)\\)"), "")
        s = s.replace("REAL MADRID", "R. MADRID").replace("REAL SOCIEDAD", "R. SOCIEDAD").replace("REAL ", "R. ")
            .replace("ATLETICO DE MADRID", "AT. MADRID").replace("ATLETICO ", "AT. ").replace("AT. MADRID", "AT. MADRID")
            .replace("ATHLETIC CLUB", "AT. BILBAO").replace(Regex("^ATHLETIC$"), "AT. BILBAO")
            .replace(Regex("^DEPORTIVO$"), "D. CORUÑA").replace("SPORTING", "R. GIJON").replace("RAYO VALLECANO", "R. VALLECANO")
        return s.take(13)
    }
}
