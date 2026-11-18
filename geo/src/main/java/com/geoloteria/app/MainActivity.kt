package com.geoloteria.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Teal = Color(0xFF0F766E)
private val HitGreen = Color(0xFF16A34A)
private val es = Locale.forLanguageTag("es-ES")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
private val FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")
private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White)) { App() }
        }
    }
}

private fun km(x: Double) = String.format(es, "%.2f km", x)
private fun coord(x: Double) = String.format(Locale.US, "%.5f", x)
private fun pct(x: Double) = String.format(es, "%.2f %%", x * 100)
private fun thousands(x: Long) = String.format(es, "%,d", x)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: GeoViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(s.hits, query) { s.hits.filter { matches(it.draw, query) } }
    val recent = remember(s.recent, query) { s.recent.filter { matches(it, query) } }

    // Permiso de notificaciones (Android 13+)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(XLSX_MIME),
    ) { uri -> uri?.let(vm::exportExcel) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importExcel)
    }
    var showRadius by rememberSaveable { mutableStateOf(false) }

    val openMap: (Draw) -> Unit = { d ->
        val uri = Uri.parse("geo:${coord(d.lat)},${coord(d.lon)}?q=${coord(d.lat)},${coord(d.lon)}(${Uri.encode(d.number)})")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GeoLotería") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (s.ready) "Cada número de la Lotería Nacional (00000-99999) recibe una coordenada al azar dentro de España. " +
                        "Si cae en un núcleo urbano (${thousands(s.nucleos.toLong())} poblaciones de más de 500 habitantes), te aviso."
                    else "Cargando mapa de España y núcleos urbanos…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---------- Último número ----------
            item {
                val d = s.last
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = d != null) { d?.let(openMap) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (d?.isHit == true) HitGreen.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (d == null) {
                            Text("Pulsa «Generar» para empezar", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(d.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.displaySmall)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (d.isHit) "¡EN NÚCLEO\nURBANO!" else "Fuera de\nnúcleo",
                                    color = if (d.isHit) HitGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text("${coord(d.lat)}, ${coord(d.lon)}", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Más cercano: ${d.nearest.name} (${d.nearest.region}) a ${km(d.distanceKm)} · " +
                                    "radio ${km(d.radiusKm)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Toca para verlo en el mapa", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // ---------- Controles ----------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = s.ready && !s.running, onClick = vm::generateOne) { Text("Generar uno") }
                    Button(
                        enabled = s.ready,
                        onClick = vm::toggleAuto,
                    ) { Text(if (s.running) "Parar" else "Automático") }
                    OutlinedButton(onClick = vm::clear) { Text("Borrar") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Por segundo:", style = MaterialTheme.typography.bodySmall)
                    SPEEDS.forEach { sp ->
                        FilterChip(selected = s.speed == sp, onClick = { vm.setSpeed(sp) }, label = { Text("$sp") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.stopOnHit, onCheckedChange = vm::setStopOnHit)
                    Text("  Parar al encontrar uno", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.notify, onCheckedChange = vm::setNotify)
                    Text("  Avisar con notificación y vibración", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---------- Aviso de importar / exportar ----------
            s.message?.let { msg ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }

            // ---------- Excel ----------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = s.historySize > 0 || s.hits.isNotEmpty(),
                        onClick = { exportLauncher.launch("geoloteria_${LocalDateTime.now().format(FILE_FMT)}.xlsx") },
                    ) { Text("Exportar Excel") }
                    OutlinedButton(
                        enabled = s.ready,
                        onClick = { importLauncher.launch(arrayOf(XLSX_MIME, "application/octet-stream", "*/*")) },
                    ) { Text("Importar Excel") }
                }
                Text(
                    "Exporta aciertos, todo lo generado (${thousands(s.historySize.toLong())}) y los radios. " +
                        "Para importar basta con columnas Número, Latitud y Longitud.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---------- Radio de acción ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showRadius = !showRadius },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Radio de acción según habitantes", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (showRadius) "▲" else "▼")
                        }
                        if (showRadius) {
                            Text(
                                "Distancia al centro del núcleo para que cuente como urbano. 0 km = ese tramo no cuenta.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                            RadiusConfig.TIER_LABEL.forEachIndexed { i, label ->
                                val v = s.radius.km[i]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$label hab.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { vm.setRadius(i, v - RadiusConfig.STEP) }) { Text("−", fontWeight = FontWeight.Bold) }
                                    Text(
                                        km(v).replace(",00", ""),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(64.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                    IconButton(onClick = { vm.setRadius(i, v + RadiusConfig.STEP) }) { Text("+", fontWeight = FontWeight.Bold) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Tasa esperada: " + (s.expectedRate?.let { pct(it) } ?: "calculando…"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = vm::resetRadius) { Text("Restablecer") }
                            }
                            Text(
                                "Los cambios se aplican a los números nuevos y a lo que importes.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // ---------- Estadísticas ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Generados", thousands(s.total))
                        Stat("En núcleo", thousands(s.hitCount))
                        Stat("Tasa", if (s.total > 0) pct(s.hitCount.toDouble() / s.total) else "–")
                    }
                }
                Text(
                    "Con los radios actuales se espera que caiga en un núcleo urbano el " +
                        (s.expectedRate?.let { pct(it) } ?: "… (calculando)") + " de los puntos.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---------- Búsqueda ----------
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Borrar búsqueda") }
                        }
                    },
                    label = { Text("Buscar por número o ciudad") },
                    placeholder = { Text("Ej.: 48213, 482, Toledo, Galicia…") },
                )
                if (query.isNotBlank()) {
                    Text(
                        if (query.trim().all { it.isDigit() }) "Números que contienen «${query.trim()}»"
                        else "Ciudad o comunidad que contiene «${query.trim()}»",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- Aciertos ----------
            item {
                Text(
                    "Aciertos · " + if (query.isBlank()) "${s.hits.size}" else "${hits.size} de ${s.hits.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(hits, key = { "h-${it.draw.id}" }) { h ->
                Card(Modifier.fillMaxWidth().clickable { openMap(h.draw) },
                    colors = CardDefaults.cardColors(containerColor = HitGreen.copy(alpha = 0.10f))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.draw.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(76.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${h.draw.nearest.name} · ${h.draw.nearest.region}", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("${coord(h.draw.lat)}, ${coord(h.draw.lon)} · a ${km(h.draw.distanceKm)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(h.time.format(timeFmt), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ---------- Últimos generados ----------
            item {
                Text(
                    "Últimos generados" + if (query.isBlank()) "" else " · ${recent.size} de ${s.recent.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(recent, key = { "r-${it.id}" }) { d ->
                Row(Modifier.fillMaxWidth().clickable { openMap(d) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(d.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(64.dp))
                    Text(
                        "${coord(d.lat)}, ${coord(d.lon)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (d.isHit) "✓ ${d.nearest.name}" else "${km(d.distanceKm)}",
                        color = if (d.isHit) HitGreen else Color.Unspecified,
                        fontWeight = if (d.isHit) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                TextButton(onClick = {}) {
                    Text("Datos: contorno Natural Earth · poblaciones GeoNames", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Solo dígitos → busca en el número; si no → en la ciudad (núcleo más cercano) o la comunidad. */
private fun matches(d: Draw, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (q.all { it.isDigit() }) return d.number.contains(q)
    val nq = norm(q)
    return norm(d.nearest.name).contains(nq) || norm(d.nearest.region).contains(nq)
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
