package com.geoloteria.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BLOQUES DE NÚMEROS
 *
 * Un bloque = 400.000 números generados de golpe (≈ 98,2 % de los 100.000 posibles salen al menos una vez).
 * Cada bloque se guarda como CSV comprimido (gzip) en el móvil + un informe estadístico (JSON).
 * El CSV lleva en la primera línea la configuración con la que se generó, para poder recalcular al importarlo.
 */
const val BLOCK_SIZE = 400_000
private const val TOTAL_NUMBERS = 100_000
private const val CSV_HEADER = "numero;latitud;longitud;ciudad;comunidad;habitantes;distancia_km;radio_km;en_nucleo;semilla;palabra;fecha;evento;euromillon;euro_aciertos;euro_sorteo"

/** Uso de cada hash solar dentro del bloque (para poder verificarlo). */
data class SeedUse(val hex: String, val dataTime: String?, val count: Int)

/** Una fila del control estadístico: valor observado frente al esperado. */
data class StatRow(val label: String, val detail: String, val count: Int, val expected: Double, val z: Double, val anomalous: Boolean)

data class BlockReport(
    val id: String,
    val created: Long,
    val n: Int,
    val mode: String,
    val pick: String,
    val radius: String,
    val durationMs: Long,
    val imported: Boolean,
    val seeds: List<SeedUse>,
    // Números
    val distinct: Int,
    val expectedDistinct: Double,
    val zerosZ: Double,
    val chi2: Double,
    val chi2Z: Double,
    val expectedPerNumber: Double,
    val maxCount: Int,
    val highThreshold: Int,               // a partir de cuántas veces un número es anómalo
    val anomalousNumbers: List<StatRow>,
    val topNumbers: List<StatRow>,
    val countHistogram: List<Int>,        // cuántos números salieron 0, 1, 2… veces
    // Cifras por posición
    val digitRows: List<StatRow>,         // 50 filas: D1-0 … D5-9
    val digitCrit: Double,
    // Ciudades y comunidades
    val cityRows: List<StatRow>,          // las más desviadas + todas las anómalas
    val cityCrit: Double,
    val cityTests: Int,
    val regionRows: List<StatRow>,
    val regionCrit: Double,
    // Palabras y fechas (vacío en bloques anteriores a esta versión)
    val wordN: Int = 0,
    val wordDistinct: Int = 0,
    val wordTotal: Int = 0,
    val wordLambda: Double = 0.0,
    val wordThreshold: Int = 0,
    val wordChi2Z: Double = 0.0,
    val anomalousWords: List<StatRow> = emptyList(),
    val topWords: List<StatRow> = emptyList(),
    val dateN: Int = 0,
    val dateDistinct: Int = 0,
    val dateTotal: Int = 0,
    val dateLambda: Double = 0.0,
    val dateThreshold: Int = 0,
    val dateChi2Z: Double = 0.0,
    val anomalousDates: List<StatRow> = emptyList(),
    val topDates: List<StatRow> = emptyList(),
    val yearRows: List<StatRow> = emptyList(),
    val yearCrit: Double = 0.0,
    val monthRows: List<StatRow> = emptyList(),
    val monthCrit: Double = 0.0,
    val weekdayRows: List<StatRow> = emptyList(),
    val weekdayCrit: Double = 0.0,
    // Combinaciones de Euromillones cruzadas con el histórico
    val euroN: Int = 0,
    val euroDraws: Int = 0,
    val euroRows: List<StatRow> = emptyList(),
    val euroCrit: Double = 0.0,
    val euroFull: List<String> = emptyList(),   // "número · combinación · sorteo" de los completos
    // Azar, dinero y premios de Navidad/Niño
    val luckRows: List<StatRow> = emptyList(),
    val luckCrit: Double = 0.0,
    val lottoList: List<String> = emptyList(),   // "número · palabra · premio" de los que coinciden
) {
    val anomalies: Int
        get() = anomalousNumbers.size + digitRows.count { it.anomalous } + cityRows.count { it.anomalous } +
            regionRows.count { it.anomalous } + (if (abs(chi2Z) > 3.3) 1 else 0) + (if (abs(zerosZ) > 3.3) 1 else 0) +
            anomalousWords.size + anomalousDates.size + yearRows.count { it.anomalous } + monthRows.count { it.anomalous } +
            weekdayRows.count { it.anomalous } + euroRows.count { it.anomalous } + luckRows.count { it.anomalous } + (if (wordN > 0 && abs(wordChi2Z) > 3.3) 1 else 0) +
            (if (dateN > 0 && abs(dateChi2Z) > 3.3) 1 else 0)
}

// ---------------------------------------------------------------------------------------------
// Estadística
// ---------------------------------------------------------------------------------------------

object Stats {
    /** Inversa de la normal estándar (algoritmo de Acklam, error < 1e-9). */
    fun invNorm(p: Double): Double {
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val pl = 0.02425
        return when {
            p < pl -> {
                val q = sqrt(-2 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p > 1 - pl -> -invNorm(1 - p)
            else -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
        }
    }

    /**
     * Umbral |z| para [tests] comprobaciones a la vez (Bonferroni): por azar, todo el bloque
     * daría una falsa alarma como mucho 1 vez de cada 20.
     */
    fun zCrit(tests: Int): Double = invNorm(1 - 0.05 / (2.0 * tests.coerceAtLeast(1)))

    /** P(X ≥ k) para X ~ Poisson(λ). */
    fun poissonTail(lambda: Double, k: Int): Double {
        var term = exp(-lambda)
        var cdf = 0.0
        for (i in 0 until k) {
            cdf += term
            term *= lambda / (i + 1)
        }
        return (1 - cdf).coerceAtLeast(0.0)
    }
}

// ---------------------------------------------------------------------------------------------
// Recuento mientras se genera o se importa
// ---------------------------------------------------------------------------------------------

class BlockCounter(private val nucleos: List<Nucleo>) {
    val numbers = IntArray(TOTAL_NUMBERS)
    val digits = Array(5) { IntArray(10) }
    val cities = IntArray(nucleos.size)
    var n = 0
    val seeds = LinkedHashMap<String, Pair<String?, Int>>()
    val words = IntArray(Words.size.coerceAtLeast(1))
    val days = IntArray(Events.size.coerceAtLeast(1))   // veces que ha salido cada evento histórico
    var wordN = 0
    var dateN = 0
    var euroN = 0
    val euroMax = IntArray(8)
    val euroFull = ArrayList<String>()
    var azarN = 0
    var dineroN = 0
    var lottoN = 0
    var doubleN = 0
    var wordTotal = 0
    val lottoList = ArrayList<String>()   // cuántas combinaciones tuvieron como máximo 0…7 aciertos

    fun add(number: String, nucIndex: Int, seedHex: String, seedTime: String?, word: String = "", event: Int = -1, euroHits: Int = -1) {
        if (euroHits in 0..7) { euroMax[euroHits]++; euroN++ }
        val th = if (word.isNotEmpty()) Themes.of(word) else null
        if (word.isNotEmpty()) wordTotal++
        if (th == Themes.AZAR) azarN++ else if (th == Themes.DINERO) dineroN++
        if (Loterias.isPrized(number)) {
            lottoN++
            if (th != null) doubleN++
            if (lottoList.size < 300) lottoList += "$number · «$word»${Themes.icon(th).let { if (it.isEmpty()) "" else " $it" }} · ${Loterias.info(number)}"
        }
        if (word.isNotEmpty()) {
            val wi = Words.index(word)
            if (wi in words.indices) { words[wi]++; wordN++ }
        }
        if (event in days.indices && Events.size > 0) { days[event]++; dateN++ }
        val v = number.toInt()
        numbers[v]++
        for (p in 0 until 5) digits[p][number[p] - '0']++
        if (nucIndex in cities.indices) cities[nucIndex]++
        n++
        val old = seeds[seedHex]
        seeds[seedHex] = (old?.first ?: seedTime) to ((old?.second ?: 0) + 1)
    }

    /**
     * Construye el informe. Probabilidad esperada de cada ciudad: según el modo
     * (habitantes o todas igual entre las que tienen radio > 0). En "Cualquier punto de España"
     * no hay una esperada sencilla y las ciudades solo se cuentan.
     */
    fun report(
        id: String,
        created: Long,
        durationMs: Long,
        mode: GenMode?,
        pick: CityPick?,
        cfg: RadiusConfig,
        imported: Boolean,
    ): BlockReport {
        val lambda = n.toDouble() / TOTAL_NUMBERS
        val distinct = numbers.count { it > 0 }
        val q0 = (1 - 1.0 / TOTAL_NUMBERS).pow(n)
        val expZeros = TOTAL_NUMBERS * q0
        val zeros = TOTAL_NUMBERS - distinct
        val zerosZ = (zeros - expZeros) / sqrt(TOTAL_NUMBERS * q0 * (1 - q0)).coerceAtLeast(1e-9)
        var chi2 = 0.0
        for (c in numbers) chi2 += (c - lambda) * (c - lambda) / lambda
        val df = TOTAL_NUMBERS - 1.0
        val chi2Z = (chi2 - df) / sqrt(2 * df)

        // Umbral de "demasiadas veces": por azar, ningún número debería llegar ni 1 vez de cada 20 bloques
        var k = 1
        while (TOTAL_NUMBERS * Stats.poissonTail(lambda, k) >= 0.05 && k < 1000) k++
        val sd = sqrt(lambda).coerceAtLeast(1e-9)
        fun numRow(v: Int, anomalous: Boolean) =
            StatRow("%05d".format(v), "", numbers[v], lambda, (numbers[v] - lambda) / sd, anomalous)
        val order = numbers.indices.sortedByDescending { numbers[it] }
        val anomalous = order.takeWhile { numbers[it] >= k }.map { numRow(it, true) }
        val top = order.take(20).map { numRow(it, numbers[it] >= k) }
        val maxC = numbers.maxOrNull() ?: 0
        val hist = IntArray(maxC + 1).also { h -> numbers.forEach { h[it]++ } }.toList()

        // Cifras por posición
        val digitCrit = Stats.zCrit(50)
        val eD = n / 10.0
        val sdD = sqrt(n * 0.1 * 0.9).coerceAtLeast(1e-9)
        val digitRows = (0 until 5).flatMap { p ->
            (0..9).map { d ->
                val z = (digits[p][d] - eD) / sdD
                StatRow("D${p + 1}", "$d", digits[p][d], eD, z, abs(z) > digitCrit)
            }
        }

        // Ciudades y comunidades
        val probs: DoubleArray? = when {
            mode == GenMode.CIUDADES && pick != null -> {
                val w = DoubleArray(nucleos.size) { i ->
                    if (cfg.radiusFor(nucleos[i].population) <= 0) 0.0
                    else if (pick == CityPick.POBLACION) nucleos[i].population.toDouble() else 1.0
                }
                val sum = w.sum()
                if (sum > 0) DoubleArray(w.size) { w[it] / sum } else null
            }
            else -> null
        }
        val eligible = probs?.count { it > 0 } ?: nucleos.size
        val cityCrit = Stats.zCrit(eligible)
        val cityAll = nucleos.indices.mapNotNull { i ->
            val c = cities[i]
            if (probs == null) {
                if (c == 0) null else StatRow(nucleos[i].name, nucleos[i].region, c, Double.NaN, 0.0, false)
            } else {
                val p = probs[i]
                if (p <= 0 && c == 0) return@mapNotNull null
                val e = n * p
                val z = if (p > 0) (c - e) / sqrt(n * p * (1 - p)) else if (c > 0) 99.0 else 0.0
                StatRow(nucleos[i].name, nucleos[i].region, c, e, z, abs(z) > cityCrit)
            }
        }
        val cityRows = if (probs == null) cityAll.sortedByDescending { it.count }.take(30)
        else (cityAll.filter { it.anomalous } + cityAll.sortedByDescending { abs(it.z) }.take(30)).distinct()
            .sortedByDescending { abs(it.z) }

        val regions = nucleos.map { it.region }.distinct()
        val regionCrit = Stats.zCrit(regions.size)
        val regionRows = regions.map { r ->
            val idx = nucleos.indices.filter { nucleos[it].region == r }
            val c = idx.sumOf { cities[it] }
            if (probs == null) StatRow(r, "", c, Double.NaN, 0.0, false)
            else {
                val p = idx.sumOf { probs[it] }
                val e = n * p
                val z = if (p > 0 && p < 1) (c - e) / sqrt(n * p * (1 - p)) else 0.0
                StatRow(r, "", c, e, z, abs(z) > regionCrit)
            }
        }.sortedByDescending { if (probs == null) it.count.toDouble() else abs(it.z) }

        // ---- Palabras ----
        val wTotal = words.size
        val wLam = wordN.toDouble() / wTotal
        var wChi = 0.0
        if (wordN > 0) for (c in words) wChi += (c - wLam) * (c - wLam) / wLam
        val wChiZ = if (wordN > 0) (wChi - (wTotal - 1)) / sqrt(2.0 * (wTotal - 1)) else 0.0
        var wk = 1
        if (wordN > 0) while (wTotal * Stats.poissonTail(wLam, wk) >= 0.05 && wk < 100_000) wk++
        val wSd = sqrt(wLam).coerceAtLeast(1e-9)
        val wOrder = if (wordN > 0) words.indices.sortedByDescending { words[it] } else emptyList()
        fun wRow(i: Int) = StatRow(Words.at(i), "", words[i], wLam, (words[i] - wLam) / wSd, words[i] >= wk)
        val anomalousWords = wOrder.takeWhile { words[it] >= wk }.map(::wRow)
        val topWords = wOrder.take(20).map(::wRow)

        // ---- Fechas ----
        val dTotal = days.size
        val dLam = dateN.toDouble() / dTotal
        var dChi = 0.0
        if (dateN > 0) for (c in days) dChi += (c - dLam) * (c - dLam) / dLam
        val dChiZ = if (dateN > 0) (dChi - (dTotal - 1)) / sqrt(2.0 * (dTotal - 1)) else 0.0
        var dk = 1
        if (dateN > 0) while (dTotal * Stats.poissonTail(dLam, dk) >= 0.05 && dk < 100_000) dk++
        val dSd = sqrt(dLam).coerceAtLeast(1e-9)
        val dOrder = if (dateN > 0) days.indices.sortedByDescending { days[it] } else emptyList()
        fun dRow(i: Int): StatRow {
            val e = Events.get(i)
            return StatRow(
                e?.text?.let { if (it.length > 90) it.take(88) + "…" else it } ?: "evento $i",
                (e?.let { Events.showDate(it.date) + " · " + Events.typeName(it.type) + " · evento $i" }).orEmpty(),
                days[i], dLam, (days[i] - dLam) / dSd, days[i] >= dk,
            )
        }
        val anomalousDates = dOrder.takeWhile { days[it] >= dk }.map(::dRow)
        val topDates = dOrder.take(20).map(::dRow)

        // Años, meses y días de la semana: lo esperado es proporcional a los días que tiene cada uno
        fun grouped(key: (Int) -> String, label: (String) -> String): Pair<List<StatRow>, Double> {
            if (dateN == 0) return emptyList<StatRow>() to 0.0
            val cnt = LinkedHashMap<String, Int>()
            val dayCount = LinkedHashMap<String, Int>()   // eventos de cada grupo en el conjunto de datos
            for (i in days.indices) {
                val k = key(i)
                cnt[k] = (cnt[k] ?: 0) + days[i]
                dayCount[k] = (dayCount[k] ?: 0) + 1
            }
            val crit = Stats.zCrit(cnt.size)
            return cnt.map { (k, c) ->
                val p = dayCount.getValue(k).toDouble() / dTotal
                val e = dateN * p
                val z = (c - e) / sqrt(dateN * p * (1 - p)).coerceAtLeast(1e-9)
                StatRow(label(k), "", c, e, z, abs(z) > crit)
            } to crit
        }
        val es = java.util.Locale.forLanguageTag("es-ES")
        // "Años" = siglos, "Meses" = mes del evento, "Días de la semana" = tipo de evento
        val (yearRows0, yearCrit) = grouped({ i -> "%06d".format(Events.years[i] / 100 + 1000) + "|" + Events.century(Events.years[i]) }, { it.substringAfter("|") })
        val yearRows = yearRows0
        val (monthRows, monthCrit) = grouped({ i -> "%02d".format(Events.months[i].toInt().coerceIn(1, 12)) }, { m ->
            java.time.Month.of(m.toInt()).getDisplayName(java.time.format.TextStyle.FULL, es)
        })
        val (weekdayRows, weekdayCrit) = grouped({ i -> Events.types[i].toString() }, { t -> Events.typeName(t.first()) })

        // ---- Euromillones: combinaciones con 5, 6 o 7 aciertos en algún sorteo real ----
        val draws = EuroHistory.size
        fun comb(n: Int, k: Int): Double {
            if (k < 0 || k > n) return 0.0
            var r = 1.0
            for (i in 1..k) r = r * (n - k + i) / i
            return r
        }
        fun pDraw(k: Int): Double {   // P(aciertos ≥ k) en un sorteo (5 de 50 + 2 de 12)
            var p = 0.0
            for (a in 0..5) for (b in 0..2) if (a + b >= k)
                p += comb(5, a) * comb(45, 5 - a) / comb(50, 5) * comb(2, b) * comb(10, 2 - b) / comb(12, 2)
            return p
        }
        val euroCrit = Stats.zCrit(3)
        val euroRows = if (euroN == 0 || draws == 0) emptyList() else (5..7).map { k ->
            val c = (k..7).sumOf { euroMax[it] }
            val p = EuroHistory.pAtLeast(k).takeIf { it > 0 } ?: (1 - (1 - pDraw(k)).pow(draws))
            val e = euroN * p
            val z = (c - e) / sqrt(euroN * p * (1 - p)).coerceAtLeast(1e-9)
            StatRow("$k o más aciertos", if (k == 7) "5 números + 2 estrellas" else "", c, e, z, abs(z) > euroCrit)
        }

        // ---- Azar, dinero y lotería ----
        val luckCrit = Stats.zCrit(4)
        fun row(label: String, detail: String, c: Int, n: Int, p: Double): StatRow {
            val e = n * p
            val z = (c - e) / sqrt(n * p * (1 - p)).coerceAtLeast(1e-9)
            return StatRow(label, detail, c, e, z, abs(z) > luckCrit)
        }
        val ws = Words.size.coerceAtLeast(1).toDouble()
        val pl = Loterias.distinct / 100_000.0
        val pt = (Themes.count(Themes.AZAR) + Themes.count(Themes.DINERO)) / ws
        val luckRows = if (n == 0) emptyList() else listOfNotNull(
            if (wordTotal > 0) row("Palabras de azar y suerte 🍀", "${Themes.count(Themes.AZAR)} palabras posibles", azarN, wordTotal, Themes.count(Themes.AZAR) / ws) else null,
            if (wordTotal > 0) row("Palabras de dinero 💰", "${Themes.count(Themes.DINERO)} palabras posibles", dineroN, wordTotal, Themes.count(Themes.DINERO) / ws) else null,
            row("Premiados en Navidad o el Niño 🎄", "${Loterias.distinct} números premiados", lottoN, n, pl),
            if (wordTotal > 0) row("Doble: premiado + palabra de azar o dinero 🎯", "", doubleN, n, pl * pt) else null,
        )

        return BlockReport(
            luckRows = luckRows, luckCrit = luckCrit, lottoList = lottoList,
            euroN = euroN, euroDraws = draws, euroRows = euroRows, euroCrit = euroCrit, euroFull = euroFull.take(200),
            wordN = wordN, wordDistinct = words.count { it > 0 }, wordTotal = wTotal, wordLambda = wLam,
            wordThreshold = wk, wordChi2Z = wChiZ, anomalousWords = anomalousWords, topWords = topWords,
            dateN = dateN, dateDistinct = days.count { it > 0 }, dateTotal = dTotal, dateLambda = dLam,
            dateThreshold = dk, dateChi2Z = dChiZ, anomalousDates = anomalousDates, topDates = topDates,
            yearRows = yearRows, yearCrit = yearCrit, monthRows = monthRows, monthCrit = monthCrit,
            weekdayRows = weekdayRows,
            weekdayCrit = weekdayCrit,
            id = id, created = created, n = n,
            mode = mode?.name ?: "DESCONOCIDO", pick = pick?.name ?: "", radius = cfg.encode(),
            durationMs = durationMs, imported = imported,
            seeds = seeds.map { (hex, v) -> SeedUse(hex, v.first, v.second) },
            distinct = distinct, expectedDistinct = TOTAL_NUMBERS - expZeros, zerosZ = zerosZ,
            chi2 = chi2, chi2Z = chi2Z, expectedPerNumber = lambda, maxCount = maxC, highThreshold = k,
            anomalousNumbers = anomalous, topNumbers = top, countHistogram = hist,
            digitRows = digitRows, digitCrit = digitCrit,
            cityRows = cityRows, cityCrit = cityCrit, cityTests = eligible,
            regionRows = regionRows, regionCrit = regionCrit,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Guardar / leer en el móvil
// ---------------------------------------------------------------------------------------------

object BlockStore {
    fun dir(ctx: Context): File = File(ctx.filesDir, "bloques").apply { mkdirs() }
    fun dataFile(ctx: Context, id: String) = File(dir(ctx), "$id.csv.gz")

    /** CSV sin comprimir del bloque en tiempo real que se está llenando. */
    fun liveFile(ctx: Context) = File(dir(ctx), "en_curso.csv")
    private fun reportFile(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    fun list(ctx: Context): List<BlockReport> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.created }

    fun delete(ctx: Context, id: String) {
        dataFile(ctx, id).delete()
        reportFile(ctx, id).delete()
    }

    fun saveReport(ctx: Context, r: BlockReport) = reportFile(ctx, r.id).writeText(toJson(r).toString())

    /** Primera línea del CSV: configuración con la que se generó. */
    fun metaLine(mode: GenMode?, pick: CityPick?, cfg: RadiusConfig) =
        "#geoloteria;modo=${mode?.name ?: ""};eleccion=${pick?.name ?: ""};radios=${cfg.encode()}"

    fun header() = CSV_HEADER

    fun openWriter(file: File) = GZIPOutputStream(file.outputStream().buffered(1 shl 16)).bufferedWriter(Charsets.UTF_8)

    /** Abre un CSV del bloque, comprimido (gzip) o no. */
    fun openReader(input: InputStream): BufferedReader {
        val buf = input.buffered(1 shl 16)
        buf.mark(2)
        val b1 = buf.read()
        val b2 = buf.read()
        buf.reset()
        val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buf, 1 shl 16) else buf
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 1 shl 16)
    }

    fun copyData(ctx: Context, id: String, out: OutputStream) = dataFile(ctx, id).inputStream().use { it.copyTo(out) }

    // ---- JSON ----

    private fun rowsToJson(rows: List<StatRow>) = JSONArray().apply {
        rows.forEach { r ->
            put(JSONObject().put("l", r.label).put("d", r.detail).put("c", r.count)
                .put("e", if (r.expected.isNaN()) JSONObject.NULL else r.expected).put("z", r.z).put("a", r.anomalous))
        }
    }

    private fun rowsFromJson(a: JSONArray?): List<StatRow> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            StatRow(o.optString("l"), o.optString("d"), o.optInt("c"),
                if (o.isNull("e")) Double.NaN else o.optDouble("e"), o.optDouble("z"), o.optBoolean("a"))
        }
    }

    fun toJson(r: BlockReport): JSONObject = JSONObject()
        .put("id", r.id).put("created", r.created).put("n", r.n).put("mode", r.mode).put("pick", r.pick)
        .put("radius", r.radius).put("durationMs", r.durationMs).put("imported", r.imported)
        .put("seeds", JSONArray().apply {
            r.seeds.forEach { put(JSONObject().put("hex", it.hex).put("t", it.dataTime ?: "").put("c", it.count)) }
        })
        .put("distinct", r.distinct).put("expectedDistinct", r.expectedDistinct).put("zerosZ", r.zerosZ)
        .put("chi2", r.chi2).put("chi2Z", r.chi2Z).put("lambda", r.expectedPerNumber).put("maxCount", r.maxCount)
        .put("k", r.highThreshold)
        .put("anomalousNumbers", rowsToJson(r.anomalousNumbers)).put("topNumbers", rowsToJson(r.topNumbers))
        .put("hist", JSONArray(r.countHistogram))
        .put("digits", rowsToJson(r.digitRows)).put("digitCrit", r.digitCrit)
        .put("cities", rowsToJson(r.cityRows)).put("cityCrit", r.cityCrit).put("cityTests", r.cityTests)
        .put("regions", rowsToJson(r.regionRows)).put("regionCrit", r.regionCrit)
        .put("wordN", r.wordN).put("wordDistinct", r.wordDistinct).put("wordTotal", r.wordTotal).put("wordLambda", r.wordLambda)
        .put("wordK", r.wordThreshold).put("wordChi2Z", r.wordChi2Z)
        .put("anomalousWords", rowsToJson(r.anomalousWords)).put("topWords", rowsToJson(r.topWords))
        .put("dateN", r.dateN).put("dateDistinct", r.dateDistinct).put("dateTotal", r.dateTotal).put("dateLambda", r.dateLambda)
        .put("dateK", r.dateThreshold).put("dateChi2Z", r.dateChi2Z)
        .put("anomalousDates", rowsToJson(r.anomalousDates)).put("topDates", rowsToJson(r.topDates))
        .put("years", rowsToJson(r.yearRows)).put("yearCrit", r.yearCrit)
        .put("months", rowsToJson(r.monthRows)).put("monthCrit", r.monthCrit)
        .put("weekdays", rowsToJson(r.weekdayRows)).put("weekdayCrit", r.weekdayCrit)
        .put("euroN", r.euroN).put("euroDraws", r.euroDraws).put("euro", rowsToJson(r.euroRows)).put("euroCrit", r.euroCrit)
        .put("euroFull", org.json.JSONArray(r.euroFull))
        .put("luck", rowsToJson(r.luckRows)).put("luckCrit", r.luckCrit).put("lottoList", org.json.JSONArray(r.lottoList))

    fun fromJson(o: JSONObject): BlockReport {
        val seeds = o.optJSONArray("seeds")
        val hist = o.optJSONArray("hist")
        return BlockReport(
            id = o.getString("id"), created = o.optLong("created"), n = o.optInt("n"),
            mode = o.optString("mode"), pick = o.optString("pick"), radius = o.optString("radius"),
            durationMs = o.optLong("durationMs"), imported = o.optBoolean("imported"),
            seeds = (0 until (seeds?.length() ?: 0)).map { i ->
                val s = seeds!!.getJSONObject(i)
                SeedUse(s.optString("hex"), s.optString("t").ifBlank { null }, s.optInt("c"))
            },
            distinct = o.optInt("distinct"), expectedDistinct = o.optDouble("expectedDistinct"),
            zerosZ = o.optDouble("zerosZ"), chi2 = o.optDouble("chi2"), chi2Z = o.optDouble("chi2Z"),
            expectedPerNumber = o.optDouble("lambda"), maxCount = o.optInt("maxCount"), highThreshold = o.optInt("k"),
            anomalousNumbers = rowsFromJson(o.optJSONArray("anomalousNumbers")),
            topNumbers = rowsFromJson(o.optJSONArray("topNumbers")),
            countHistogram = (0 until (hist?.length() ?: 0)).map { hist!!.getInt(it) },
            digitRows = rowsFromJson(o.optJSONArray("digits")), digitCrit = o.optDouble("digitCrit"),
            cityRows = rowsFromJson(o.optJSONArray("cities")), cityCrit = o.optDouble("cityCrit"),
            cityTests = o.optInt("cityTests"),
            regionRows = rowsFromJson(o.optJSONArray("regions")), regionCrit = o.optDouble("regionCrit"),
            wordN = o.optInt("wordN"), wordDistinct = o.optInt("wordDistinct"), wordTotal = o.optInt("wordTotal"),
            wordLambda = o.optDouble("wordLambda", 0.0), wordThreshold = o.optInt("wordK"), wordChi2Z = o.optDouble("wordChi2Z", 0.0),
            anomalousWords = rowsFromJson(o.optJSONArray("anomalousWords")), topWords = rowsFromJson(o.optJSONArray("topWords")),
            dateN = o.optInt("dateN"), dateDistinct = o.optInt("dateDistinct"), dateTotal = o.optInt("dateTotal"),
            dateLambda = o.optDouble("dateLambda", 0.0), dateThreshold = o.optInt("dateK"), dateChi2Z = o.optDouble("dateChi2Z", 0.0),
            anomalousDates = rowsFromJson(o.optJSONArray("anomalousDates")), topDates = rowsFromJson(o.optJSONArray("topDates")),
            yearRows = rowsFromJson(o.optJSONArray("years")), yearCrit = o.optDouble("yearCrit", 0.0),
            monthRows = rowsFromJson(o.optJSONArray("months")), monthCrit = o.optDouble("monthCrit", 0.0),
            weekdayRows = rowsFromJson(o.optJSONArray("weekdays")), weekdayCrit = o.optDouble("weekdayCrit", 0.0),
            euroN = o.optInt("euroN"), euroDraws = o.optInt("euroDraws"), euroRows = rowsFromJson(o.optJSONArray("euro")),
            euroCrit = o.optDouble("euroCrit", 0.0),
            euroFull = o.optJSONArray("euroFull")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
            luckRows = rowsFromJson(o.optJSONArray("luck")), luckCrit = o.optDouble("luckCrit", 0.0),
            lottoList = o.optJSONArray("lottoList")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )
    }
}
