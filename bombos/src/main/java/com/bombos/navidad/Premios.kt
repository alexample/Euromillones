package com.bombos.navidad

/**
 * PROGRAMA DE PREMIOS DEL SORTEO EXTRAORDINARIO DE NAVIDAD (por serie de 100.000 números).
 * Premios que salen del bombo de premios (1.807 bolas) y premios que se derivan de ellos.
 * Importes por billete (serie); el décimo cobra la décima parte.
 */
enum class Cat(val code: String, val nombre: String, val count: Int, val importe: Long, val bola: String) {
    GORDO("1", "Primer premio (el Gordo)", 1, 4_000_000, "4.000.000"),
    SEGUNDO("2", "Segundo premio", 1, 1_250_000, "1.250.000"),
    TERCERO("3", "Tercer premio", 1, 500_000, "500.000"),
    CUARTO("4", "Cuarto premio", 2, 200_000, "200.000"),
    QUINTO("5", "Quinto premio", 8, 60_000, "60.000"),
    PEDREA("P", "Pedrea", 1_794, 1_000, "1.000");

    val grande: Boolean get() = this != PEDREA

    companion object {
        fun of(code: String): Cat? = entries.firstOrNull { it.code == code }
        val TOTAL_BOLAS: Int = entries.sumOf { it.count }      // 1.807

        /** Bolas del bombo de premios: el índice de cada bola indica su premio. */
        fun bolas(): Array<Cat> = entries.flatMap { c -> List(c.count) { c } }.toTypedArray()
    }
}

/** Premios que no salen del bombo: se deducen de los premios mayores. */
enum class Derivado(val nombre: String, val importe: Long, val count: Int) {
    APROX_1("Aproximación al primer premio (anterior o posterior)", 20_000, 2),
    APROX_2("Aproximación al segundo premio (anterior o posterior)", 12_500, 2),
    APROX_3("Aproximación al tercer premio (anterior o posterior)", 9_600, 2),
    CENTENA_1("Centena del primer premio", 1_000, 99),
    CENTENA_2("Centena del segundo premio", 1_000, 99),
    CENTENA_3("Centena del tercer premio", 1_000, 99),
    CENTENA_4("Centena de los cuartos premios", 1_000, 198),
    TERM_1("Dos últimas cifras del primer premio", 1_000, 999),
    TERM_2("Dos últimas cifras del segundo premio", 1_000, 999),
    TERM_3("Dos últimas cifras del tercer premio", 1_000, 999),
    REINTEGRO("Reintegro (última cifra del primer premio)", 200, 9_999),
}

/** Los premios mayores de un sorteo (los que generan los derivados). */
data class Mayores(val gordo: Int?, val segundo: Int?, val tercero: Int?, val cuartos: List<Int>) {

    /** Todos los premios derivados que corresponden al número [n]. */
    fun derivados(n: Int): List<Derivado> {
        val out = ArrayList<Derivado>()
        fun aprox(p: Int?, d: Derivado) { if (p != null && (n == (p + 1) % 100_000 || n == (p + 99_999) % 100_000)) out += d }
        fun cent(p: Int?, d: Derivado) { if (p != null && n != p && n / 100 == p / 100) out += d }
        fun term(p: Int?, d: Derivado) { if (p != null && n != p && n % 100 == p % 100) out += d }
        aprox(gordo, Derivado.APROX_1); aprox(segundo, Derivado.APROX_2); aprox(tercero, Derivado.APROX_3)
        cent(gordo, Derivado.CENTENA_1); cent(segundo, Derivado.CENTENA_2); cent(tercero, Derivado.CENTENA_3)
        cuartos.forEach { c -> if (n != c && n / 100 == c / 100) out += Derivado.CENTENA_4 }
        term(gordo, Derivado.TERM_1); term(segundo, Derivado.TERM_2); term(tercero, Derivado.TERM_3)
        if (gordo != null && n != gordo && n % 10 == gordo % 10) out += Derivado.REINTEGRO
        return out
    }

    /** Números que cobran un premio derivado (en orden). */
    fun numeros(d: Derivado): List<Int> {
        fun aprox(p: Int?) = if (p == null) emptyList() else listOf((p + 99_999) % 100_000, (p + 1) % 100_000)
        fun cent(p: Int?) = if (p == null) emptyList() else (p / 100 * 100 until p / 100 * 100 + 100).filter { it != p }
        fun term(p: Int?) = if (p == null) emptyList() else (0 until 1000).map { it * 100 + p % 100 }.filter { it != p }
        return when (d) {
            Derivado.APROX_1 -> aprox(gordo)
            Derivado.APROX_2 -> aprox(segundo)
            Derivado.APROX_3 -> aprox(tercero)
            Derivado.CENTENA_1 -> cent(gordo)
            Derivado.CENTENA_2 -> cent(segundo)
            Derivado.CENTENA_3 -> cent(tercero)
            Derivado.CENTENA_4 -> cuartos.flatMap { cent(it) }.sorted()
            Derivado.TERM_1 -> term(gordo)
            Derivado.TERM_2 -> term(segundo)
            Derivado.TERM_3 -> term(tercero)
            Derivado.REINTEGRO -> if (gordo == null) emptyList() else (0 until 10_000).map { it * 10 + gordo % 10 }.filter { it != gordo }
        }
    }
}

/** Cómo cantan los niños de San Ildefonso: números y premios en palabras. */
object Canto {
    private val U = arrayOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
        "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve")
    private val D = arrayOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val C = arrayOf("", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos",
        "setecientos", "ochocientos", "novecientos")

    private fun hasta99(n: Int): String = if (n < 30) U[n] else D[n / 10] + if (n % 10 != 0) " y " + U[n % 10] else ""

    private fun hasta999(n: Int): String = when {
        n == 100 -> "cien"
        n < 100 -> hasta99(n)
        else -> C[n / 100] + if (n % 100 != 0) " " + hasta99(n % 100) else ""
    }

    /** Número en palabras (0 … 999.999.999), con «un» delante de mil/millón cuando toca. */
    fun palabras(n: Long): String {
        if (n == 0L) return "cero"
        val millones = (n / 1_000_000).toInt()
        val miles = ((n / 1000) % 1000).toInt()
        val resto = (n % 1000).toInt()
        val p = ArrayList<String>()
        if (millones > 0) p += if (millones == 1) "un millón" else apocope(hasta999(millones)) + " millones"
        if (miles > 0) p += if (miles == 1) "mil" else apocope(hasta999(miles)) + " mil"
        if (resto > 0) p += hasta999(resto)
        return p.joinToString(" ")
    }

    private fun apocope(s: String) = s.replace(Regex("veintiuno$"), "veintiún").replace(Regex("uno$"), "un")

    /** Lo que canta el niño del número: «cuarenta y ocho mil doscientos trece». */
    fun numero(n: Int): String = palabras(n.toLong())

    /** Lo que canta la niña del premio: «mil euros», «cuatro millones de euros». */
    fun premio(c: Cat): String {
        val w = palabras(c.importe)
        return if (c.importe % 1_000_000 == 0L) "$w de euros" else "$w euros"
    }

    fun n5(n: Int) = "%05d".format(n)
    fun euros(v: Long): String = "%,d €".format(java.util.Locale.forLanguageTag("es-ES"), v)
}
