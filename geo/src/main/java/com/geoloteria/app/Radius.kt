package com.geoloteria.app

import android.content.Context

/**
 * Radio de acción (km) según el número de habitantes del núcleo.
 * Tramos fijos de población; el radio de cada tramo es configurable. 0 km = ese tramo no cuenta.
 */
data class RadiusConfig(val km: List<Double> = DEFAULT_KM) {

    fun radiusFor(population: Int): Double {
        val i = TIER_MIN.indexOfLast { population >= it }
        return if (i < 0) 0.0 else km.getOrElse(i) { 0.0 }
    }

    fun with(tier: Int, value: Double): RadiusConfig =
        RadiusConfig(km.toMutableList().also { it[tier] = value.coerceIn(0.0, MAX_KM) })

    fun encode(): String = km.joinToString(",")

    companion object {
        /** Población mínima de cada tramo. */
        val TIER_MIN = listOf(500, 1_000, 5_000, 20_000, 100_000, 500_000)
        val TIER_LABEL = listOf(
            "500 – 999",
            "1.000 – 4.999",
            "5.000 – 19.999",
            "20.000 – 99.999",
            "100.000 – 499.999",
            "500.000 o más",
        )
        val DEFAULT_KM = listOf(0.5, 0.8, 1.3, 2.2, 3.5, 5.0)
        const val MAX_KM = 20.0
        const val STEP = 0.1

        fun decode(s: String?): RadiusConfig {
            val v = s?.split(",")?.mapNotNull { it.toDoubleOrNull() }
            return if (v != null && v.size == DEFAULT_KM.size) RadiusConfig(v) else RadiusConfig()
        }

        private const val PREFS = "geoloteria"
        private const val KEY = "radios"

        fun load(ctx: Context): RadiusConfig =
            decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        fun save(ctx: Context, c: RadiusConfig) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, c.encode()).apply()
        }
    }
}
