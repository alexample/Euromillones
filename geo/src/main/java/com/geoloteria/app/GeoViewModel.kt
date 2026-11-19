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
    fun addWatchNumber(n: String) = GeoEngine.addWatchNumber(n)
    fun addWatchTown(t: String) = GeoEngine.addWatchTown(t)
    fun removeWatchNumber(n: String) = GeoEngine.removeWatchNumber(n)
    fun removeWatchTown(t: String) = GeoEngine.removeWatchTown(t)
    fun setStopOnAlert(v: Boolean) = GeoEngine.setStopOnAlert(v)
    fun clearAlerts() = GeoEngine.clearAlerts()
    fun suggestTowns(q: String) = GeoEngine.suggestTowns(q)
    fun setMode(m: GenMode) = GeoEngine.setMode(m)
    fun startBlock() = GeoEngine.startBlock()
    fun cancelBlock() = GeoEngine.cancelBlock()
    fun deleteBlock(id: String) = GeoEngine.deleteBlock(id)
    fun exportBlockCsv(id: String, uri: Uri) = GeoEngine.exportBlockCsv(id, uri)
    fun exportBlockExcel(id: String, uri: Uri) = GeoEngine.exportBlockExcel(id, uri)
    fun importBlock(uri: Uri) = GeoEngine.importBlock(uri)
    fun setPick(p: CityPick) = GeoEngine.setPick(p)
}
