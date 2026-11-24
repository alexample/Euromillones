package com.bombos.navidad

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

/** Mantiene el sorteo en tiempo real con la pantalla apagada o la app en segundo plano. */
class SorteoService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Sorteo.init(this)
        val ch = NotificationChannel(CHANNEL, "Sorteo en curso", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            Sorteo.pausar()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Bombos:sorteo")
                .apply { acquire(8 * 60 * 60 * 1000L) }
        }
        if (updater == null) {
            updater = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    delay(3_000)
                    if (!Sorteo.running.value) break
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(Sorteo.estado.value.let { "Sorteo de Navidad · ${it.fase.titulo}" })
        .setContentText(
            Sorteo.estado.value.let { e ->
                if (e.fase == Fase.EXTRACCION || e.fase == Fase.CIERRE_TABLA)
                    "Tabla ${e.tabla} · alambre ${e.alambre} · ${e.extraidas} de 1.807 bolas"
                else e.fase.texto.take(80)
            },
        )
        .setOngoing(true).setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE),
        )
        .addAction(0, "Pausar", PendingIntent.getService(this, 1, Intent(this, SorteoService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        updater?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sorteo"
        private const val NOTIF_ID = 1
        private const val ACTION_PAUSE = "com.bombos.navidad.PAUSE"

        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SorteoService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, SorteoService::class.java)) }
    }
}
