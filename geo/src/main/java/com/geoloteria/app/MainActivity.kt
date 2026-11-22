package com.geoloteria.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.InputChip
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Teal = Color(0xFF0F766E)
private val HitGreen = Color(0xFF16A34A)
private val es = Locale.forLanguageTag("es-ES")
private val timeFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private const val PAGE = 500
private const val LIVE_REPORT = "__en_vivo__"

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

    /** Al salir de la app se guarda todo (también se guarda solo cada 15 s). */
    override fun onStop() {
        super.onStop()
        GeoEngine.saveAsync()
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
    // Búsqueda en TODO lo guardado (base de datos), fuera de la pantalla:
    // al dejar de escribir y cada 3 s; "Ver más" amplía la página.
    var pageLimit by rememberSaveable(query) { mutableStateOf(PAGE) }
    val searchResult by produceState(0L to emptyList<Draw>(), query, pageLimit) {
        if (query.isBlank()) {
            value = 0L to emptyList()
            return@produceState
        }
        delay(300)
        while (true) {
            value = withContext(Dispatchers.Default) { GeoEngine.search(query, pageLimit) }
            delay(3_000)
        }
    }
    val hitsResult by produceState(0L to emptyList<Draw>(), query) {
        if (query.isBlank()) {
            value = 0L to emptyList()
            return@produceState
        }
        delay(300)
        while (true) {
            value = withContext(Dispatchers.Default) { GeoEngine.search(query, 500, hitsOnly = true) }
            delay(3_000)
        }
    }
    val hits: List<Hit> = if (query.isBlank()) s.hits else hitsResult.second.map { Hit(it, null) }
    val recent = if (query.isBlank()) s.recent else searchResult.second
    var selected by remember { mutableStateOf<Draw?>(null) }

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
    var showWatch by rememberSaveable { mutableStateOf(true) }
    var showRanking by rememberSaveable { mutableStateOf(false) }
    var showBlocks by rememberSaveable { mutableStateOf(true) }
    // Informe de bloque abierto (pantalla completa) y bloque pendiente de exportar
    var reportId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = reportId != null) { reportId = null }
    var blockExportId by rememberSaveable { mutableStateOf<String?>(null) }
    val blockExcelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        val id = blockExportId
        if (uri != null && id != null) vm.exportBlockExcel(id, uri)
    }
    val blockCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        val id = blockExportId
        if (uri != null && id != null) vm.exportBlockCsv(id, uri)
    }
    val blockImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importBlock)
    }

    // Al pulsar un número: se copia y se abre el buscador oficial de administraciones
    val clipboard = LocalClipboardManager.current
    // Número cuya búsqueda oficial está abierta dentro de la app (null = pantalla normal)
    var sellerNumber by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = sellerNumber != null) { sellerNumber = null }
    BackHandler(enabled = WikiNav.url != null) { WikiNav.url = null }
    val openSeller: (String) -> Unit = { number ->
        clipboard.setText(AnnotatedString(number))
        sellerNumber = number
    }

    val openMap: (Draw) -> Unit = { d ->
        val uri = Uri.parse("geo:${coord(d.lat)},${coord(d.lon)}?q=${coord(d.lat)},${coord(d.lon)}(${Uri.encode(d.number)})")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            WikiNav.url != null -> "Wikipedia"
                            sellerNumber != null -> "Dónde venden el $sellerNumber"
                            reportId == LIVE_REPORT -> "Bloque en curso (en vivo)"
                            reportId != null -> "Informe del bloque"
                            else -> "GeoLotería"
                        },
                    )
                },
                navigationIcon = {
                    if (sellerNumber != null || reportId != null || WikiNav.url != null) {
                        IconButton(onClick = { if (WikiNav.url != null) WikiNav.url = null else { sellerNumber = null; reportId = null } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                        }
                    }
                },
                actions = {
                    WikiNav.url?.let { u ->
                        TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WikiNav.desktop(u)))) }
                        }) { Text("Navegador", color = Color.White) }
                    }
                    if (sellerNumber != null && WikiNav.url == null) {
                        TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BUSCAR_DECIMO))) }
                            Toast.makeText(ctx, "Número ${sellerNumber} copiado: pégalo en el buscador", Toast.LENGTH_LONG).show()
                        }) { Text("Navegador", color = Color.White) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Teal, titleContentColor = Color.White),
            )
        },
    ) { padding ->
        sellerNumber?.let { n ->
            SellerWeb(n, Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        WikiNav.url?.let { u ->
            WikiPage(u, Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        reportId?.let { id ->
            val r = if (id == LIVE_REPORT) s.liveReport else s.blocks.firstOrNull { it.id == id }
            if (r != null) {
                BlockReportScreen(r, Modifier.fillMaxSize().padding(padding))
                return@Scaffold
            }
        }
        selected?.let { d ->
            AlertDialog(
                onDismissRequest = { selected = null },
                title = { Text(d.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall) },
                text = {
                    Column {
                        Text(
                            (if (d.isHit) "En núcleo urbano: " else "Más cercano: ") +
                                "${d.nearest.name} (${d.nearest.region}) a ${km(d.distanceKm)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("${coord(d.lat)}, ${coord(d.lon)}", fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                        WordDate(d, MaterialTheme.typography.bodyMedium, full = true)
                        Events.get(d.event)?.let { e ->
                            TextButton(onClick = { selected = null; WikiNav.url = e.url }) {
                                Text("Abrir en Wikipedia: ${e.title.replace('_', ' ')}")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "«Dónde lo venden» abre el buscador oficial de Loterías y Apuestas del Estado y busca este número automáticamente.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { openSeller(d.number); selected = null }) { Text("Dónde lo venden") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { openMap(d); selected = null }) { Text("Mapa") }
                        TextButton(onClick = { selected = null }) { Text("Cerrar") }
                    }
                },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    when {
                        !s.ready -> "Cargando mapa de España y núcleos urbanos…"
                        s.mode == GenMode.CIUDADES ->
                            "Cada número de la Lotería Nacional (00000-99999) cae siempre en una ciudad " +
                                "(${thousands(s.nucleos.toLong())} poblaciones de más de 500 habitantes), en un punto al azar " +
                                "dentro de su radio según sus habitantes."
                        else ->
                            "Cada número de la Lotería Nacional (00000-99999) recibe una coordenada al azar dentro de España. " +
                                "Si cae en un núcleo urbano, cuenta como acierto."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---------- Modo ----------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Dónde caen los números", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GenMode.entries.forEach { m ->
                                FilterChip(selected = s.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.title) })
                            }
                        }
                        if (s.mode == GenMode.CIUDADES) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Ciudad:", style = MaterialTheme.typography.bodySmall)
                                CityPick.entries.forEach { p ->
                                    FilterChip(selected = s.pick == p, onClick = { vm.setPick(p) }, label = { Text(p.title) })
                                }
                            }
                            Text(
                                if (s.pick == CityPick.POBLACION)
                                    "Las ciudades con más habitantes salen más (Madrid mucho más que un pueblo pequeño)."
                                else "Todas las poblaciones tienen la misma probabilidad, sean grandes o pequeñas.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "El radio de cada ciudad es el de la tabla «Radio de acción»; un tramo a 0 km no sale nunca.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        EntropyInfo()
                    }
                }
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
                                Text(
                                    d.number,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.displaySmall,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier.clickable { selected = d },
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (d.isHit) "¡EN NÚCLEO\nURBANO!" else "Fuera de\nnúcleo",
                                    color = if (d.isHit) HitGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            WordDate(d, MaterialTheme.typography.titleMedium)
                            Text("${coord(d.lat)}, ${coord(d.lon)}", fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Más cercano: ${d.nearest.name} (${d.nearest.region}) a ${km(d.distanceKm)} · " +
                                    "radio ${km(d.radiusKm)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Toca el número para ver dónde lo venden · toca la tarjeta para verlo en el mapa",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // ---------- Controles ----------
            item {
                if (s.running) {
                    Text(
                        "Generando también en segundo plano: puedes cerrar la app. Para pararlo usa «Parar» aquí o en el aviso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Teal,
                    )
                }
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
                    Switch(checked = s.vibrate, onCheckedChange = vm::setVibrate)
                    Text("  Vibrar al encontrar uno", style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---------- Bloques de 400.000 ----------
            item {
                BlocksCard(
                    s = s,
                    expanded = showBlocks,
                    onToggle = { showBlocks = !showBlocks },
                    onGenerate = vm::startBlock,
                    onCancel = vm::cancelBlock,
                    onImport = { blockImportLauncher.launch(arrayOf("application/gzip", "text/*", "application/octet-stream", "*/*")) },
                    onOpen = { reportId = it },
                    onExcel = { id -> blockExportId = id; blockExcelLauncher.launch("$id.xlsx") },
                    onCsv = { id -> blockExportId = id; blockCsvLauncher.launch("$id.csv.gz") },
                    onDelete = vm::deleteBlock,
                    onOpenLive = { reportId = LIVE_REPORT },
                    onCloseLive = vm::closeLiveNow,
                )
            }

            // ---------- Avisos con filtro ----------
            item {
                WatchCard(
                    s = s,
                    vm = vm,
                    expanded = showWatch,
                    onToggle = { showWatch = !showWatch },
                    onOpen = { selected = it },
                )
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
                    if (s.mode == GenMode.CIUDADES) "En el modo «En ciudades» todos los números caen en una ciudad."
                    else "Con los radios actuales se espera que caiga en un núcleo urbano el " +
                        (s.expectedRate?.let { pct(it) } ?: "… (calculando)") + " de los puntos.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // ---------- Ranking ----------
            item {
                RankingCard(
                    total = s.total,
                    expanded = showRanking,
                    onToggle = { showRanking = !showRanking },
                    onSearch = { query = it },
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
                    label = { Text("Buscar número, ciudad, palabra o fecha del evento") },
                    placeholder = { Text("48213, Toledo, casa, año 1492, 5 aciertos…") },
                )
                if (query.isNotBlank()) {
                    Text(
                        when {
                            Regex("""\d{1,2}/\d{1,2}/\d{4}""").matches(query.trim()) -> "Números con la fecha ${query.trim()}"
                            Regex("""a[nñ]o\s*\d{4}""").matches(query.trim().lowercase()) -> "Números con fecha del ${query.trim().lowercase()}"
                            query.trim().all { it.isDigit() } -> "Números que contienen «${query.trim()}»"
                            else -> "Ciudad, comunidad o palabra que contiene «${query.trim()}»"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ---------- Aciertos ----------
            item {
                Text(
                    "Aciertos · " + if (query.isBlank()) thousands(s.hitCount)
                    else "${thousands(hitsResult.first)} encontrados" +
                        if (hitsResult.first > hits.size) " (se muestran ${hits.size})" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(hits, key = { "h-${it.draw.id}" }) { h ->
                Card(Modifier.fillMaxWidth().clickable { openMap(h.draw) },
                    colors = CardDefaults.cardColors(containerColor = HitGreen.copy(alpha = 0.10f))) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            h.draw.number,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.width(76.dp).clickable { selected = h.draw },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("${h.draw.nearest.name} · ${h.draw.nearest.region}", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            WordDate(h.draw, MaterialTheme.typography.bodySmall)
                            Text("${coord(h.draw.lat)}, ${coord(h.draw.lon)} · a ${km(h.draw.distanceKm)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(h.time?.format(timeFmt).orEmpty(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ---------- Últimos generados ----------
            item {
                Text(
                    if (query.isBlank()) "Últimos generados"
                    else "Resultados en todo lo generado · ${thousands(searchResult.first)}" +
                        if (searchResult.first > recent.size) " (se muestran ${recent.size})" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(recent, key = { "r-${it.id}" }) { d ->
                Row(Modifier.fillMaxWidth().clickable { openMap(d) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.number,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.width(64.dp).clickable { selected = d },
                    )
                    Column(Modifier.weight(1f)) {
                        WordDate(d, MaterialTheme.typography.bodySmall)
                        Text(
                            "${coord(d.lat)}, ${coord(d.lon)}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        if (d.isHit) "✓ ${d.nearest.name}" else "${km(d.distanceKm)}",
                        color = if (d.isHit) HitGreen else Color.Unspecified,
                        fontWeight = if (d.isHit) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (query.isNotBlank() && searchResult.first > recent.size) {
                item {
                    TextButton(onClick = { pageLimit += PAGE }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver más (${thousands(searchResult.first - recent.size)} restantes)")
                    }
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

/** Avisos: números o poblaciones a vigilar, y la lista de avisos que han saltado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchCard(s: GeoState, vm: GeoViewModel, expanded: Boolean, onToggle: () -> Unit, onOpen: (Draw) -> Unit) {
    var numText by rememberSaveable { mutableStateOf("") }
    var townText by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val suggestions = remember(townText, s.ready) { vm.suggestTowns(townText) }
    val active = s.watchNumbers.size + s.watchTowns.size

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E6))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Avisos · $active filtro${if (active == 1) "" else "s"} · ${s.alerts.size} recibido${if (s.alerts.size == 1) "" else "s"}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "▲" else "▼")
            }
            if (!expanded) return@Column
            Text(
                "Te aviso con una notificación cuando salga un número o una población que indiques, " +
                    "esté o no en el núcleo urbano.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // Número
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = numText,
                    onValueChange = { numText = it.filter { c -> c.isDigit() }.take(5); error = null },
                    label = { Text("Número o terminación") },
                    placeholder = { Text("48213 o 13") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    error = vm.addWatchNumber(numText)
                    if (error == null) numText = ""
                }) { Text("Añadir") }
            }
            // Población
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = townText,
                    onValueChange = { townText = it; error = null },
                    label = { Text("Población") },
                    placeholder = { Text("Toledo, Sitges…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    error = vm.addWatchTown(townText)
                    if (error == null) townText = ""
                }) { Text("Añadir") }
            }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { n ->
                        AssistChip(
                            onClick = {
                                error = vm.addWatchTown(n.name)
                                if (error == null) townText = ""
                            },
                            label = { Text("${n.name} (${n.region})", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // Filtros activos
            if (active > 0) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.watchNumbers.forEach { n ->
                        InputChip(
                            selected = true,
                            onClick = { vm.removeWatchNumber(n) },
                            label = { Text(if (n.length == 5) "Nº $n" else "Termina en $n") },
                            trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = "Quitar") },
                        )
                    }
                    s.watchTowns.forEach { t ->
                        InputChip(
                            selected = true,
                            onClick = { vm.removeWatchTown(t) },
                            label = { Text(t) },
                            trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = "Quitar") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = s.stopOnAlert, onCheckedChange = vm::setStopOnAlert)
                Text("  Parar el automático cuando salte un aviso", style = MaterialTheme.typography.bodySmall)
            }

            // Avisos recibidos
            if (s.alerts.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Avisos recibidos", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearAlerts) { Text("Borrar") }
                }
                s.alerts.take(30).forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(a.draw) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.draw.number, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(64.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.reason, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("${a.draw.nearest.name} · a ${km(a.draw.distanceKm)}", style = MaterialTheme.typography.labelSmall)
                            WordDate(a.draw, MaterialTheme.typography.labelSmall)
                        }
                        Text(a.time.format(timeFmt), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (s.alerts.size > 30) Text("… y ${s.alerts.size - 30} más (se exportan a Excel)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Ranking de lo que más ha salido: ciudades, números o comunidades (en todo lo guardado). */
@Composable
private fun RankingCard(total: Long, expanded: Boolean, onToggle: () -> Unit, onSearch: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(RankKind.CIUDADES) }
    var hitsOnly by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableStateOf(20) }
    val ranking by produceState<Ranking?>(null, kind, hitsOnly, limit, expanded) {
        if (!expanded) return@produceState
        while (true) {
            value = withContext(Dispatchers.Default) { GeoEngine.ranking(kind, hitsOnly, limit) }
            delay(5_000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
                Text("Ranking: lo que más ha salido", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼")
            }
            if (!expanded) return@Column
            RankKind.entries.chunked(3).forEach { rowKinds ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowKinds.forEach { k ->
                        FilterChip(selected = k == kind, onClick = { kind = k; limit = 20 }, label = { Text(k.title) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !hitsOnly, onClick = { hitsOnly = false }, label = { Text("Todos") })
                FilterChip(selected = hitsOnly, onClick = { hitsOnly = true }, label = { Text("Solo en núcleo") })
            }
            val r = ranking
            if (r == null) {
                Text("Calculando…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            if (r.total == 0L) {
                Text("Aún no hay datos.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            val what = when (kind) {
                RankKind.CIUDADES -> "ciudades distintas"
                RankKind.NUMEROS -> "números distintos (de 100.000)"
                RankKind.COMUNIDADES -> "comunidades"
                RankKind.PALABRAS -> "palabras distintas (de ${thousands(Words.size.toLong())})"
                RankKind.FECHAS -> "eventos distintos (de ${thousands(Events.size.toLong())})"
                RankKind.ANOS -> "años distintos"
            }
            Text(
                "${thousands(r.total)} ${if (hitsOnly) "aciertos" else "números"} · ${thousands(r.distinct)} $what." +
                    when (kind) {
                        RankKind.NUMEROS -> " Por azar, cada número debería salir unas ${num2(r.total / 100_000.0)} veces."
                        RankKind.PALABRAS -> " Por azar, cada palabra debería salir unas ${num2(r.total / Words.size.coerceAtLeast(1).toDouble())} veces."
                        RankKind.FECHAS -> " Por azar, cada evento debería salir unas ${num2(r.total / Events.size.coerceAtLeast(1).toDouble())} veces."
                        RankKind.ANOS -> " Los años con más eventos en Wikipedia (sobre todo los recientes) salen más, como es lógico."
                        else -> ""
                    } + when (kind) {
                        RankKind.PALABRAS -> " (Solo cuentan los números generados desde la versión 2.4.)"
                        RankKind.FECHAS, RankKind.ANOS -> " (Solo cuentan los números generados desde la versión 2.7.)"
                        else -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            r.rows.forEachIndexed { i, row ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSearch(if (kind == RankKind.CIUDADES || kind == RankKind.COMUNIDADES) row.label else row.key) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}.", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.label,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = if (kind == RankKind.NUMEROS) FontFamily.Monospace else FontFamily.Default,
                        )
                        if (row.detail.isNotEmpty()) Text(row.detail, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${thousands(row.count)} veces", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(pct(row.count.toDouble() / r.total), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row {
                if (r.rows.size >= limit && limit < 500) {
                    TextButton(onClick = { limit = (limit * 2).coerceAtMost(500) }) { Text("Ver más") }
                }
                Spacer(Modifier.weight(1f))
                Text("Toca una fila para buscarla", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

private fun num2(x: Double) = String.format(es, "%.2f", x)

/**
 * «palabra» y el evento histórico del número (los generados antes de la 2.7 tienen fecha sin evento).
 * Tocar el evento abre su artículo en Wikipedia.
 */
@Composable
private fun WordDate(d: Draw, style: androidx.compose.ui.text.TextStyle, full: Boolean = false) {
    val ctx = LocalContext.current
    if (d.word.isNotEmpty()) {
        Text("«${d.word}»", style = style, fontWeight = FontWeight.SemiBold, color = Teal)
    }
    val e = Events.get(d.event)
    if (e != null) {
        val text = if (full || e.text.length <= 120) e.text else e.text.take(118) + "…"
        Text(
            "${Events.showDate(e.date)} · $text",
            style = style,
            color = Color(0xFF1D4ED8),
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { WikiNav.url = e.url },
        )
    } else if (d.date.isNotEmpty()) {
        Text(Words.show(d.date), style = style, color = Teal)
    }
    if (d.euro.isNotEmpty()) {
        Text("Euromillón: ${d.euro.replace(" + ", "  ★ ")}", style = style, fontFamily = FontFamily.Monospace)
        if (d.euroInfo.isNotEmpty()) {
            Text("🎯 ${d.euroInfo}", style = style, color = HitGreen, fontWeight = FontWeight.Bold)
        } else if (full && d.euroHits >= 0) {
            Text("Máximo en el histórico: ${d.euroHits} aciertos (se avisa a partir de ${EuroHistory.MIN_HITS})", style = style)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
