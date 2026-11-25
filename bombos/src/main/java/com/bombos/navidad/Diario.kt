package com.bombos.navidad

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * SORTEO DIARIO: cada mañana a las 8:00 (apertura de la sala, como el 22 de diciembre) empieza un sorteo
 * nuevo que va en tiempo real con el reloj del móvil: la primera bola sale hacia las 9:09 y la última hacia
 * las 13:25. Se programa con una alarma exacta (la única forma fiable de que Android lo arranque a su hora).
 */
object Diario {
    const val HORA = 8
    const val ACCION = "com.bombos.navidad.DIARIO"
    private const val PREF = "diario"

    fun activo(ctx: Context) = ctx.getSharedPreferences("bombos", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun activar(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences("bombos", Context.MODE_PRIVATE).edit().putBoolean(PREF, v).apply()
        if (v) programar(ctx) else cancelar(ctx)
    }

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, DiarioReceiver::class.java).setAction(ACCION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Próxima apertura (hoy a las 8:00 si aún no ha pasado; si no, mañana). */
    fun proxima(): LocalDateTime {
        val now = LocalDateTime.now()
        val hoy = now.toLocalDate().atTime(HORA, 0)
        return if (now.isBefore(hoy)) hoy else hoy.plusDays(1)
    }

    /** Devuelve false si Android no deja poner alarmas exactas (entonces hay que dar el permiso). */
    fun programar(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val t = proxima().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exacta = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exacta) {
            val abrir = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, abrir), pi(ctx))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(ctx))
        }
        return exacta
    }

    fun cancelar(ctx: Context) { ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx)) }
}

/** Recibe la alarma de las 8:00 (y el reinicio del móvil, para volver a programarla). */
class DiarioReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Diario.activo(ctx)) return
        when (intent.action) {
            Diario.ACCION -> {
                SorteoService.startDiario(ctx)
                Diario.programar(ctx)     // y la de mañana
            }
            else -> Diario.programar(ctx) // BOOT_COMPLETED, cambio de hora…
        }
    }
}
