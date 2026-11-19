package com.geoloteria.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * MOTOR ALEATORIO A PARTIR DEL SOL
 *
 * Cada 5 minutos se descargan los datos de rayos X del Sol que miden los satélites GOES
 * (NOAA, se actualizan cada minuto) y se calcula su SHA-256. Ese hash es la semilla:
 * cualquiera que descargue el mismo fichero obtiene el mismo hash, así que es verificable.
 *
 * Los números salen de encadenar SHA-256(semilla ‖ contador): un bloque de 32 bytes por contador.
 * Si no hay conexión se sigue con el último hash; si nunca se ha podido descargar,
 * se usa el azar seguro del móvil (y se indica).
 */
object SolarEntropy {
    const val SOURCE_URL = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json"
    const val SOURCE_NAME = "Rayos X del Sol · satélites GOES (NOAA)"
    private const val REFRESH_MS = 5 * 60 * 1000L
    private const val RETRY_MS = 60 * 1000L

    data class Seed(
        val hex: String,          // SHA-256 de los datos, en hexadecimal
        val dataTime: String?,    // hora del último dato solar (UTC)
        val fetchedAt: Long,      // cuándo se descargó (ms)
        val sizeBytes: Int,
    )

    data class Status(val seed: Seed? = null, val error: String? = null, val fallback: Boolean = true)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var current: Random = SecureRandom().asKotlinRandom()

    /** Generador a usar ahora mismo (el del Sol si hay hash; si no, el seguro del móvil). */
    fun random(): Random = current

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val ok = runCatching { refresh() }.onFailure { e ->
                    _status.value = _status.value.copy(error = e.message ?: "sin conexión")
                }.isSuccess
                delay(if (ok) REFRESH_MS else RETRY_MS)
            }
        }
    }

    /** Fuerza una actualización ahora. */
    fun refreshNow() {
        scope.launch {
            runCatching { refresh() }.onFailure { e ->
                _status.value = _status.value.copy(error = e.message ?: "sin conexión")
            }
        }
    }

    private fun refresh() {
        val conn = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        val bytes = try {
            if (conn.responseCode != 200) error("NOAA respondió ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        if (bytes.size < 1000) error("datos solares vacíos")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = hash.joinToString("") { "%02x".format(it) }
        val lastTime = Regex("\"time_tag\"\\s*:\\s*\"([^\"]+)\"").findAll(bytes.decodeToString()).lastOrNull()?.groupValues?.get(1)
        current = HashRandom(hash)
        _status.value = Status(Seed(hex, lastTime, System.currentTimeMillis(), bytes.size), error = null, fallback = false)
    }
}

/**
 * Generador determinista a partir de un hash: bloque_i = SHA-256(semilla ‖ i).
 * Kotlin construye sobre nextBits() todo lo demás (nextInt(n) sin sesgo, barajar, etc.).
 */
class HashRandom(private val seed: ByteArray) : Random() {
    private val md = MessageDigest.getInstance("SHA-256")
    private var counter = 0L
    private var block = ByteArray(0)
    private var pos = 0

    private fun refill() {
        md.reset()
        md.update(seed)
        for (i in 7 downTo 0) md.update((counter ushr (8 * i)).toByte())
        counter++
        block = md.digest()
        pos = 0
    }

    private fun next32(): Int {
        if (pos + 4 > block.size) refill()
        val b = block
        val v = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or
            ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
        pos += 4
        return v
    }

    @Synchronized
    override fun nextBits(bitCount: Int): Int {
        val r = next32()
        return r.ushr(32 - bitCount) and (-bitCount shr 31)
    }
}
