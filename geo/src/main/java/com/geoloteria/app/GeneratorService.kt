package com.geoloteria.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Mantiene vivo el modo automático aunque se cierre la app o se apague la pantalla.
 * Android exige mostrar un aviso permanente mientras trabaja: es silencioso y lleva botón "Parar".
 * No hay notificaciones por cada acierto.
 */
class GeneratorService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GeoEngine.init(this)
        val ch = NotificationChannel(CHANNEL, "Generación en segundo plano", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GeoEngine.stop()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // Mantiene la CPU despierta con la pantalla apagada mientras genera
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeoLoteria:generador")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }
        // Actualiza el contador del aviso cada 5 s
        if (updater == null) {
            updater = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(5_000)
                    if (!GeoEngine.state.value.running) break
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("GeoLotería generando")
        .setContentText(
            GeoEngine.state.value.let { "Generados ${it.total} · en núcleo ${it.hitCount}" },
        )
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, "Parar",
            PendingIntent.getService(
                this, 1,
                Intent(this, GeneratorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        updater?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        GeoEngine.saveAsync()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "segundo_plano"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.geoloteria.app.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GeneratorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GeneratorService::class.java))
        }
    }
}
