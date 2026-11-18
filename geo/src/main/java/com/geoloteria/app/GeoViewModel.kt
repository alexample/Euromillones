package com.geoloteria.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/** La pantalla solo observa y manda órdenes al motor ([GeoEngine]), que sigue vivo en segundo plano. */
class GeoViewModel(app: Application) : AndroidViewModel(app) {

    init {
        GeoEngine.init(app)
    }

    val state: StateFlow<GeoState> = GeoEngine.state

    fun generateOne() = GeoEngine.generateOne()
    fun toggleAuto() = GeoEngine.toggleAuto()
    fun setSpeed(s: Int) = GeoEngine.setSpeed(s)
    fun setStopOnHit(v: Boolean) = GeoEngine.setStopOnHit(v)
    fun setVibrate(v: Boolean) = GeoEngine.setVibrate(v)
    fun clear() = GeoEngine.clear()
    fun setRadius(tier: Int, km: Double) = GeoEngine.setRadius(tier, km)
    fun resetRadius() = GeoEngine.resetRadius()
    fun dismissMessage() = GeoEngine.dismissMessage()
    fun exportExcel(uri: Uri) = GeoEngine.exportExcel(uri)
    fun importExcel(uri: Uri) = GeoEngine.importExcel(uri)
}
