package com.geoloteria.app

import android.content.Context
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Nombres de comunidades autónomas en español (GeoNames los da en inglés). */
private val REGIONES = mapOf(
    "Andalusia" to "Andalucía", "Castille and León" to "Castilla y León", "Catalonia" to "Cataluña",
    "Castille-La Mancha" to "Castilla-La Mancha", "Valencia" to "Comunidad Valenciana", "Madrid" to "Comunidad de Madrid",
    "Aragon" to "Aragón", "Canary Islands" to "Canarias", "Basque Country" to "País Vasco",
    "Balearic Islands" to "Islas Baleares", "Navarre" to "Navarra", "Murcia" to "Región de Murcia",
)

fun regionEs(r: String): String = REGIONES[r] ?: r

/** Población española (núcleo de más de 500 habitantes, datos de GeoNames). */
data class Nucleo(val name: String, val lat: Double, val lon: Double, val population: Int, val region: String) {
    /** Nombre y comunidad ya normalizados (sin tildes, minúsculas): se calculan una sola vez. */
    val key: String by lazy { norm(name) }
    val regionKey: String by lazy { norm(region) }
}

/**
 * Un número con su coordenada. [nearest] = el núcleo que "manda" en ese punto: aquel en cuyo radio cae
 * (si hay varios, el más céntrico respecto a su radio) o, si no cae en ninguno, el más cercano.
 */
data class Draw(
    val id: Long,
    val number: String,
    val lat: Double,
    val lon: Double,
    val nearest: Nucleo,
    val distanceKm: Double,
    val radiusKm: Double,
) {
    val isHit: Boolean get() = radiusKm > 0 && distanceKm <= radiusKm
}

/**
 * Contorno de España (Natural Earth) y núcleos urbanos (GeoNames), cargados de los assets.
 * Los puntos se sortean uniformes en superficie y se descartan los que caen en el mar u otros países.
 */
class Geo(context: Context) {
    private val rings: List<DoubleArray>        // cada polígono: [lon0, lat0, lon1, lat1, …]
    private val minLon: Double
    private val maxLon: Double
    private val minLat: Double
    private val maxLat: Double
    val nucleos: List<Nucleo>

    init {
        val arr = JSONArray(context.assets.open("spain.json").bufferedReader().use { it.readText() })
        val rs = ArrayList<DoubleArray>()
        for (i in 0 until arr.length()) {
            val ring = arr.getJSONArray(i)
            val d = DoubleArray(ring.length() * 2)
            for (j in 0 until ring.length()) {
                val p = ring.getJSONArray(j)
                d[2 * j] = p.getDouble(0)
                d[2 * j + 1] = p.getDouble(1)
            }
            rs += d
        }
        rings = rs
        minLon = rs.minOf { r -> (r.indices step 2).minOf { r[it] } }
        maxLon = rs.maxOf { r -> (r.indices step 2).maxOf { r[it] } }
        minLat = rs.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
        maxLat = rs.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }

        nucleos = context.assets.open("nucleos.csv").bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val p = line.split(";")
                if (p.size < 4) null
                else Nucleo(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toIntOrNull() ?: 0, regionEs(p.getOrElse(4) { "" }))
            }.toList()
        }
    }

    /** Posición de cada núcleo en la lista (para guardar en el móvil). */
    val index: Map<Nucleo, Int> by lazy { nucleos.withIndex().associate { it.value to it.index } }

    fun inSpain(lon: Double, lat: Double): Boolean = rings.any { inside(lon, lat, it) }

    private fun inside(x: Double, y: Double, r: DoubleArray): Boolean {
        var c = false
        val n = r.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = r[2 * i]; val yi = r[2 * i + 1]
            val xj = r[2 * j]; val yj = r[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Punto al azar dentro de España, uniforme en superficie. */
    fun randomPoint(rnd: Random = Random.Default): Pair<Double, Double> {
        val s0 = sin(Math.toRadians(minLat))
        val s1 = sin(Math.toRadians(maxLat))
        while (true) {
            val lon = rnd.nextDouble(minLon, maxLon)
            val lat = Math.toDegrees(asin(rnd.nextDouble(s0, s1)))
            if (inSpain(lon, lat)) return lat to lon
        }
    }

    /**
     * Evalúa un punto: busca el núcleo en cuyo radio cae (distancia/radio más pequeño) y, si no cae en
     * ninguno, el más cercano. Distancias con aproximación plana (precisa a estas escalas).
     */
    fun evaluate(id: Long, number: String, lat: Double, lon: Double, cfg: RadiusConfig): Draw {
        val kx = 111.32 * cos(Math.toRadians(lat))
        val ky = 110.57
        var nearest = nucleos.first()
        var nearestD = Double.MAX_VALUE
        var hit: Nucleo? = null
        var hitD = 0.0
        var hitR = 0.0
        var hitRatio = Double.MAX_VALUE
        for (n in nucleos) {
            val dx = (n.lon - lon) * kx
            val dy = (n.lat - lat) * ky
            val d = sqrt(dx * dx + dy * dy)
            if (d < nearestD) {
                nearestD = d
                nearest = n
            }
            val r = cfg.radiusFor(n.population)
            if (r > 0 && d <= r && d / r < hitRatio) {
                hitRatio = d / r
                hit = n
                hitD = d
                hitR = r
            }
        }
        return if (hit != null) Draw(id, number, lat, lon, hit, hitD, hitR)
        else Draw(id, number, lat, lon, nearest, nearestD, cfg.radiusFor(nearest.population))
    }

    fun draw(id: Long, cfg: RadiusConfig, rnd: Random = Random.Default): Draw {
        val number = "%05d".format(rnd.nextInt(100_000))
        val (lat, lon) = randomPoint(rnd)
        return evaluate(id, number, lat, lon, cfg)
    }

    /** Muestra fija de puntos para estimar la tasa de aciertos de una configuración. */
    private val sample: List<Pair<Double, Double>> by lazy {
        val rnd = Random(2026)
        List(2000) { randomPoint(rnd) }
    }

    fun estimateRate(cfg: RadiusConfig): Double =
        sample.count { (lat, lon) -> evaluate(0, "", lat, lon, cfg).isHit }.toDouble() / sample.size
}
