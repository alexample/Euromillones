package com.geoloteria.app

import android.content.Context
import org.json.JSONArray
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
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
    /** Radio que se considera casco urbano: crece con la población (0,5 km → 5 km). */
    val radiusKm: Double get() = min(5.0, 0.25 + 0.3 * sqrt(population / 1000.0))
}

data class Draw(
    val id: Long,
    val number: String,
    val lat: Double,
    val lon: Double,
    val nearest: Nucleo,
    val distanceKm: Double,
) {
    val isHit: Boolean get() = distanceKm <= nearest.radiusKm
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

    /** Núcleo más cercano y distancia en km (aproximación plana, precisa a estas escalas). */
    fun nearest(lat: Double, lon: Double): Pair<Nucleo, Double> {
        val kx = 111.32 * cos(Math.toRadians(lat))
        val ky = 110.57
        var best = nucleos.first()
        var bestD2 = Double.MAX_VALUE
        for (n in nucleos) {
            val dx = (n.lon - lon) * kx
            val dy = (n.lat - lat) * ky
            val d2 = dx * dx + dy * dy
            if (d2 < bestD2) {
                bestD2 = d2
                best = n
            }
        }
        return best to sqrt(bestD2)
    }

    fun draw(id: Long, rnd: Random = Random.Default): Draw {
        val number = "%05d".format(rnd.nextInt(100_000))
        val (lat, lon) = randomPoint(rnd)
        val (n, d) = nearest(lat, lon)
        return Draw(id, number, lat, lon, n, d)
    }
}
