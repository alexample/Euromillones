package com.geoloteria.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

data class Hit(val draw: Draw, val time: LocalTime)

data class GeoState(
    val ready: Boolean = false,
    val nucleos: Int = 0,
    val last: Draw? = null,
    val recent: List<Draw> = emptyList(),
    val hits: List<Hit> = emptyList(),
    val total: Long = 0,
    val hitCount: Long = 0,
    val running: Boolean = false,
    val speed: Int = 5,
    val stopOnHit: Boolean = false,
    val notify: Boolean = true,
)

val SPEEDS = listOf(1, 5, 20, 100)

class GeoViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(GeoState())
    val state: StateFlow<GeoState> = _state.asStateFlow()

    private var geo: Geo? = null
    private var job: Job? = null
    private var nextId = 1L
    private var lastAlertAt = 0L

    init {
        createChannel()
        viewModelScope.launch(Dispatchers.Default) {
            val g = Geo(app)
            geo = g
            _state.update { it.copy(ready = true, nucleos = g.nucleos.size) }
        }
    }

    fun generateOne() {
        val g = geo ?: return
        viewModelScope.launch(Dispatchers.Default) { record(g.draw(nextId++)) }
    }

    fun toggleAuto() {
        if (_state.value.running) stop() else start()
    }

    private fun start() {
        val g = geo ?: return
        _state.update { it.copy(running = true) }
        job = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && _state.value.running) {
                val speed = _state.value.speed
                val tickMs = if (speed >= 10) 100L else 1000L / speed
                val perTick = if (speed >= 10) speed / 10 else 1
                repeat(perTick) {
                    if (_state.value.running) record(g.draw(nextId++))
                }
                delay(tickMs)
            }
        }
    }

    fun stop() {
        _state.update { it.copy(running = false) }
        job?.cancel()
    }

    fun setSpeed(s: Int) = _state.update { it.copy(speed = s) }
    fun setStopOnHit(v: Boolean) = _state.update { it.copy(stopOnHit = v) }
    fun setNotify(v: Boolean) = _state.update { it.copy(notify = v) }
    fun clear() = _state.update { it.copy(last = null, recent = emptyList(), hits = emptyList(), total = 0, hitCount = 0) }

    private fun record(d: Draw) {
        _state.update { s ->
            s.copy(
                last = d,
                recent = (listOf(d) + s.recent).take(50),
                hits = if (d.isHit) (listOf(Hit(d, LocalTime.now())) + s.hits).take(500) else s.hits,
                total = s.total + 1,
                hitCount = s.hitCount + if (d.isHit) 1 else 0,
            )
        }
        if (d.isHit) {
            alert(d)
            if (_state.value.stopOnHit) stop()
        }
    }

    // ---------- Avisos ----------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Aciertos en núcleo urbano", NotificationManager.IMPORTANCE_HIGH)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun alert(d: Draw) {
        // Como mucho un aviso cada 2 segundos para no saturar a alta velocidad
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < 2000) return
        lastAlertAt = now

        @Suppress("DEPRECATION")
        app.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

        if (!_state.value.notify) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("¡${d.number} en ${d.nearest.name}!")
            .setContentText("A ${"%.2f".format(d.distanceKm)} km del centro (${d.nearest.region})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(app).notify(d.id.toInt(), n)
    }

    companion object {
        const val CHANNEL = "aciertos"
    }
}
