package com.quinielas.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Verde = Tinta

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SolarEntropy.start()
        setContent { TemaRetro { App() } }
    }
}

private fun pct(x: Double) = "%.0f%%".format(x * 100)
private fun dec(x: Double, d: Int = 2) = "%.${d}f".format(Locale.forLanguageTag("es-ES"), x)
private fun miles(n: Long) = "%,d".format(Locale.forLanguageTag("es-ES"), n)

/** Estado compartido entre pestañas. */
private class Sel {
    var jornada by mutableIntStateOf(-1)
    var vista: Base? = null          // base sobre la que se eligió la jornada
    var manual: Int? = null          // nº de quiniela elegido a mano
    var pasada by mutableStateOf<Prox?>(null)   // quiniela ya jugada elegida para probar los filtros
    var verAnteriores by mutableStateOf(false)
    var k by mutableIntStateOf(7)
    var umbral by mutableFloatStateOf(20f)
    var permitidos by mutableStateOf(IntArray(14) { 7 })
    var editado by mutableStateOf(false)
    var cobertura by mutableFloatStateOf(70f)
    var mascaras by mutableStateOf<Set<Mascara>?>(null)
    var racha by mutableIntStateOf(0)
    var tope by mutableIntStateOf(100)
    var modo by mutableStateOf(Generador.Modo.POR_MASCARA)
    var porMascara by mutableIntStateOf(3)
    var bio by mutableStateOf(false)
    var bioUmbral by mutableFloatStateOf(0.3f)
    var resultado by mutableStateOf<Generacion?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    val base by Datos.base.collectAsState()
    val estado by Datos.estado.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sel = remember { Sel() }
    LaunchedEffect(Unit) { Datos.cargar(ctx, red = false); Datos.cargar(ctx, red = true) }

    Scaffold(containerColor = Papel, topBar = {
        TopAppBar(
            title = { Column {
                Text("LA QUINIELA", fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Text("APUESTAS DEPORTIVAS · $estado".uppercase(), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            } },
            actions = { TextButton(onClick = { scope.launch { Datos.cargar(ctx, red = true) } }) { Text("ACTUALIZAR", color = Papel) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Tinta, titleContentColor = Papel),
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = PapelOscuro, contentColor = Tinta) {
                listOf("GENERADOR", "ESTADÍSTICA", "HISTÓRICO").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp, letterSpacing = 1.sp) })
                }
            }
            val b = base
            if (b == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            if (sel.vista !== b) {
                // Datos nuevos: se vuelve a elegir por nº de quiniela, no por posición
                sel.vista = b
                val m = sel.manual?.let { n -> b.proximas.indexOfFirst { it.numero == n } }?.takeIf { it >= 0 }
                val nueva = m ?: siguienteJornada(b.proximas)
                if (nueva != sel.jornada) { sel.jornada = nueva; sel.editado = false; sel.resultado = null }
            }
            if (sel.mascaras == null) sel.mascaras = b.estadistica.masFrecuentes(sel.cobertura.toDouble())
            when (tab) {
                0 -> GeneradorVista(b, sel)
                1 -> EstadisticaVista(b)
                2 -> HistoricoVista(b)
            }
        }
    }
}

// ============================================================ JORNADA

/** La próxima jornada: la primera que aún no ha empezado; si no hay, la primera con partidos por jugar. */
private fun siguienteJornada(l: List<Prox>): Int {
    val hoy = java.time.LocalDate.now().toString()
    fun inicio(p: Prox) = p.partidos.mapNotNull { it.fecha.take(10).ifEmpty { null } }.minOrNull() ?: p.fecha
    return l.indexOfFirst { it.pendiente && it.partidos.all { x -> x.marcador.isBlank() } && inicio(it) >= hoy }.takeIf { it >= 0 }
        ?: l.indexOfFirst { it.pendiente }.takeIf { it >= 0 } ?: (l.size - 1)
}

/** ¿Falta por publicar la siguiente quiniela? (todas las conocidas ya han empezado) */
private fun faltaSiguiente(l: List<Prox>): Boolean = l.none { it.pendiente && it.partidos.all { x -> x.marcador.isBlank() } }

private fun pronosticos(b: Base, p: Prox, k: Int): List<Pronostico> = p.partidos.map { x ->
    val f = x.fecha.take(10).ifEmpty { p.fecha }
    b.tendencias.pronostico(b.equipos.deLiga(x.local), b.equipos.deLiga(x.visit), f, k)
}

/** Biorritmo medio de los titulares de los dos equipos para el día del partido (solo equipos españoles). */
private fun biorritmos(b: Base, p: Prox): List<Pair<Bio?, Bio?>> = p.partidos.map { x ->
    val dia = runCatching { java.time.LocalDate.parse(x.fecha.take(10).ifEmpty { p.fecha }) }.getOrDefault(java.time.LocalDate.parse(p.fecha))
    b.plantillas.de(x.local)?.let { Biorritmo.equipo(it, dia) } to b.plantillas.de(x.visit)?.let { Biorritmo.equipo(it, dia) }
}

/** Filtro biorritmo: si un equipo está claramente por debajo del otro, se quita el signo que le da la victoria. */
private fun conBiorritmo(perm: IntArray, bios: List<Pair<Bio?, Bio?>>, umbral: Float): IntArray = IntArray(14) { i ->
    val (l, v) = bios.getOrNull(i) ?: return@IntArray perm[i]
    if (l == null || v == null) return@IntArray perm[i]
    val d = l.media - v.media
    var m = perm[i]
    if (d >= umbral) m = m and (1 shl 2).inv()        // el visitante está «bajo»: fuera el 2
    else if (d <= -umbral) m = m and 1.inv()           // el local está «bajo»: fuera el 1
    if (m == 0) perm[i] else m
}

private val Rojo = androidx.compose.ui.graphics.Color(0xFFC8231E)

private fun rachaMax(t: String): Int { var m = 0; var r = 0; var prev = ' '; for (c in t) { r = if (c == prev) r + 1 else 1; prev = c; if (r > m) m = r }; return m }

private fun bioTxt(x: Bio?) = if (x == null) "sin plantilla" else "%+.2f".format(x.media)

private fun autoPermitidos(pr: List<Pronostico>, umbral: Float): IntArray = IntArray(14) { i ->
    val p = pr.getOrNull(i)?.p ?: return@IntArray 7
    var m = 0
    for (s in 0..2) if (p[s] * 100 >= umbral) m = m or (1 shl s)
    if (m == 0) m = 1 shl p.indices.maxByOrNull { p[it] }!!
    m
}

private fun androidx.compose.foundation.lazy.LazyListScope.jornadaItems(b: Base, sel: Sel, prox: Prox, pr: List<Pronostico>, bios: List<Pair<Bio?, Bio?>>, efectivos: IntArray) {
        val real = prox.real
        val hayReal = real.any { it != ' ' }
        item {
            Text("Jornada", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                b.proximas.forEachIndexed { i, x ->
                    FilterChip(selected = sel.pasada == null && sel.jornada == i, onClick = { sel.jornada = i; sel.manual = x.numero; sel.pasada = null; sel.editado = false; sel.resultado = null },
                        label = { Text("Nº ${x.numero} · ${x.fecha.substring(8, 10)}/${x.fecha.substring(5, 7)}" + if (x.pendiente) "" else " ✓") })
                }
            }
            TextButton(onClick = { sel.verAnteriores = !sel.verAnteriores }) {
                Text(if (sel.verAnteriores) "Ocultar jornadas ya jugadas" else "Probar los filtros con jornadas ya jugadas…")
            }
            if (sel.verAnteriores) {
                val vistas = b.proximas.map { it.fecha to it.numero }.toSet()
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.anteriores.filter { (it.fecha to it.numero) !in vistas }.take(60).forEach { x ->
                        FilterChip(selected = sel.pasada?.let { it.fecha == x.fecha && it.numero == x.numero } == true,
                            onClick = { sel.pasada = x; sel.editado = false; sel.resultado = null },
                            label = { Text("Nº ${x.numero} · ${x.fecha.substring(8, 10)}/${x.fecha.substring(5, 7)}/${x.fecha.substring(2, 4)}") })
                    }
                }
                Text("Se calcula todo como si fuera la víspera: las rachas solo usan partidos anteriores a esa fecha " +
                    "(el biorritmo usa las plantillas de hoy).", style = MaterialTheme.typography.labelSmall)
            }
            if (faltaSiguiente(b.proximas)) Text("La quiniela nº ${(b.proximas.maxOfOrNull { it.numero } ?: 0) + 1} todavía no la ha publicado Loterías " +
                "(suele salir al acabar la jornada intersemanal). Los datos se revisan cada 2 h; pulsa ACTUALIZAR." +
                (b.actualizado.takeIf { it.length >= 16 }?.let { " Última revisión: ${it.substring(8, 10)}/${it.substring(5, 7)} ${it.substring(11, 16)} UTC." } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Tinta)
            Text("Quiniela nº ${prox.numero}" + (prox.jornada?.let { " (jornada $it)" } ?: "") + " del ${prox.fecha.substring(8, 10)}/${prox.fecha.substring(5, 7)}" +
                if (prox.pendiente) "" else " · ya jugada", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Filtro por tendencia (rachas)", fontWeight = FontWeight.Bold)
                    Text("Patrón de los últimos ${sel.k} partidos de cada equipo (G gana, E empata, P pierde; el último a la derecha). " +
                        "Se buscan todos los equipos de 1ª y 2ª desde 2000 que llegaron con la misma racha a un partido en casa (o fuera) " +
                        "y se cuenta qué hicieron después. Si hay menos de 30 casos, el patrón se acorta.", style = MaterialTheme.typography.bodySmall)
                    Text("Longitud del patrón: ${sel.k}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sel.k.toFloat(), onValueChange = { sel.k = it.toInt(); sel.editado = false }, valueRange = 3f..7f, steps = 3)
                    Text("Signos que entran: los que tienen al menos un ${sel.umbral.toInt()} %", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sel.umbral, onValueChange = { sel.umbral = it; sel.editado = false }, valueRange = 10f..45f)
                    val dobles = sel.permitidos.count { Integer.bitCount(it) == 2 }; val triples = sel.permitidos.count { it == 7 }
                    Text("Ahora: ${14 - dobles - triples} fijos, $dobles dobles y $triples triples. Toca 1, X o 2 para cambiarlos a mano.",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (sel.editado) TextButton(onClick = { sel.editado = false; sel.permitidos = autoPermitidos(pr, sel.umbral) }) { Text("Volver a la tendencia") }
                    if (hayReal) {
                        val jug = (0 until 14).filter { real[it] != ' ' }
                        val ok = jug.filter { efectivos[it] and (1 shl SIGNOS.indexOf(real[it])) != 0 }
                        val mal = jug - ok.toSet()
                        Text("Contra el resultado real", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Text("Resultado: " + real.chunked(1).joinToString(" ") { it.ifBlank { "·" } }, fontFamily = Maquina, fontWeight = FontWeight.Bold)
                        Text("El filtro${if (sel.bio) " (con biorritmo)" else ""} acierta ${ok.size} de ${jug.size} partidos" +
                            if (mal.isEmpty()) ": la columna ganadora está dentro." else "; falla en " +
                                mal.joinToString(", ") { "${it + 1} (salió ${real[it]})" } + ".",
                            color = if (mal.isEmpty()) Verde else Rojo, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        val fijos = (0 until 14).filter { Integer.bitCount(efectivos[it]) == 1 && real[it] != ' ' }
                        val dob = (0 until 14).filter { Integer.bitCount(efectivos[it]) == 2 && real[it] != ' ' }
                        Text("Fijos: ${fijos.count { it in ok }}/${fijos.size} · dobles: ${dob.count { it in ok }}/${dob.size} · triples siempre aciertan.",
                            style = MaterialTheme.typography.bodySmall)
                        if (jug.size == 14) {
                            val m = Mascara.de(real)
                            val enM = m in sel.mascaras.orEmpty()
                            val r = rachaMax(real)
                            val enR = sel.racha == 0 || r <= sel.racha
                            Text("Máscara real $m: ${if (enM) "✓ está entre las elegidas" else "✗ no está entre las elegidas"} · " +
                                "racha más larga $r: ${if (enR) "✓ cumple" else "✗ la descartaría el límite"}.",
                                color = if (enM && enR) Verde else Rojo, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        itemsIndexed(prox.partidos) { i, x ->
            val p = pr.getOrNull(i)
            Card {
                Row(Modifier.padding(10.dp)) {
                    // Izquierda: el partido con sus puntos suspensivos, como en el boleto, y debajo el estudio
                    Column(Modifier.weight(1f)) {
                        Text("${x.pos} ${Boleto80.nombre(x.local)}-${Boleto80.nombre(x.visit)} ......................................",
                            fontFamily = Estrecha, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Clip, softWrap = false)
                        if (x.marcador.isNotBlank()) Text("Resultado: ${x.marcador}  →  ${x.signo}", fontWeight = FontWeight.Bold, color = Verde,
                            style = MaterialTheme.typography.bodySmall)
                        val r = real.getOrNull(i) ?: ' '
                        if (i < 14 && r != ' ') {
                            val ok = efectivos[i] and (1 shl SIGNOS.indexOf(r)) != 0
                            val tuyos = (0..2).filter { efectivos[i] and (1 shl it) != 0 }.joinToString(" ") { SIGNOS[it].toString() }
                            Text(if (ok) "✓ Acierto: el filtro incluía el $r" else "✗ Fallo: salió $r y el filtro dejaba $tuyos",
                                color = if (ok) Verde else Rojo, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        }
                        if (p != null) {
                            Text("Local: ${p.formaLocal?.toCharArray()?.joinToString(" ") ?: "sin datos"}   ·   Visitante: ${p.formaVisit?.toCharArray()?.joinToString(" ") ?: "sin datos"}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = Maquina)
                            Text("1 ${pct(p.p[0])} · X ${pct(p.p[1])} · 2 ${pct(p.p[2])}   (${p.nota}; casos ${p.nLocal}/${p.nVisit})",
                                style = MaterialTheme.typography.bodySmall)
                            val bio = bios.getOrNull(i)
                            if (bio != null && (bio.first != null || bio.second != null)) Text(
                                "Biorritmo: local ${bioTxt(bio.first)} · visitante ${bioTxt(bio.second)}" +
                                    if (sel.bio && bio.first != null && bio.second != null && kotlin.math.abs(bio.first!!.media - bio.second!!.media) >= sel.bioUmbral)
                                        (if (bio.first!!.media > bio.second!!.media) "  → filtro quita el 2" else "  → filtro quita el 1") else "",
                                style = MaterialTheme.typography.labelSmall)
                            p.siguienteLocal?.let { s -> Text("Tras «${p.formaLocal}» en casa: G ${s[0]} · E ${s[1]} · P ${s[2]}", style = MaterialTheme.typography.labelSmall) }
                            p.siguienteVisit?.let { s -> Text("Tras «${p.formaVisit}» fuera: G ${s[0]} · E ${s[1]} · P ${s[2]}", style = MaterialTheme.typography.labelSmall) }
                            var ver by remember(p) { mutableStateOf(false) }
                            if (p.formaLocal != null || p.formaVisit != null) TextButton(onClick = { ver = !ver }) {
                                Text(if (ver) "Ocultar equipos con la misma racha" else "Ver equipos con la misma racha")
                            }
                            if (ver) {
                                val f = x.fecha.take(10).ifEmpty { prox.fecha }
                                p.formaLocal?.let { CasosLista(b, x.local, it, true, f) }
                                p.formaVisit?.let { CasosLista(b, x.visit, it, false, f) }
                            }
                        }
                        if (i >= 14) Text("Pleno al 15 (goles): no entra en el generador.", style = MaterialTheme.typography.labelSmall)
                    }
                    // Derecha: las casillas 1 X 2, que se tachan a lápiz
                    if (i < 14) Row(Modifier.padding(start = 4.dp)) {
                        for (s in 0..2) {
                            val on = sel.permitidos[i] and (1 shl s) != 0
                            CasillaLapiz(SIGNOS[s], on, prox.numero * 97 + i * 7 + s) {
                                val a = sel.permitidos.copyOf(); val nv = a[i] xor (1 shl s); if (nv != 0) a[i] = nv
                                sel.permitidos = a; sel.editado = true; sel.resultado = null
                            }
                        }
                    }
                }
            }
        }
}

/** Equipos que llegaron con la misma racha y qué hicieron en el partido siguiente. */
@Composable
private fun CasosLista(b: Base, equipo: String, patron: String, casa: Boolean, fecha: String) {
    val casos = remember(patron, casa, fecha) { b.tendencias.casos(patron, casa, fecha) }
    val g = casos.count { it.res == 'G' }; val e = casos.count { it.res == 'E' }; val pp = casos.count { it.res == 'P' }
    val n = casos.size.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().border(1.dp, Verde.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text("$equipo llega con ${patron.toCharArray().joinToString(" ")} ${if (casa) "y juega en casa" else "y juega fuera"}",
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        Text("${casos.size} veces un equipo de 1ª o 2ª llegó así ${if (casa) "a un partido en casa" else "a un partido fuera"}: " +
            "ganó $g (${pct(g.toDouble() / n)}), empató $e (${pct(e.toDouble() / n)}), perdió $pp (${pct(pp.toDouble() / n)}).",
            style = MaterialTheme.typography.bodySmall)
        val porEquipo = casos.groupingBy { it.equipo }.eachCount().entries.sortedByDescending { it.value }.take(6)
        if (porEquipo.isNotEmpty()) Text("Los que más veces: " + porEquipo.joinToString(", ") { "${it.key} (${it.value})" }, style = MaterialTheme.typography.labelSmall)
        Text("Los más recientes:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        casos.take(12).forEach { c ->
            Text("${c.fecha.substring(8, 10)}/${c.fecha.substring(5, 7)}/${c.fecha.take(4)} · ${c.equipo} ${if (c.casa) "vs" else "en"} ${c.rival} ${c.gf}-${c.gc} → ${c.res}",
                style = MaterialTheme.typography.labelSmall, fontFamily = Maquina)
        }
    }
}

// ============================================================ GENERADOR

@Composable
private fun GeneradorVista(b: Base, sel: Sel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var calculando by remember { mutableStateOf(false) }
    var verBoleto by remember { mutableStateOf(true) }
    val est = b.estadistica
    val prox = sel.pasada ?: b.proximas.getOrNull(sel.jornada)
    val real = prox?.real ?: ""
    val hayReal = real.any { it != ' ' }
    val pr = remember(prox, sel.k, b) { prox?.let { pronosticos(b, it, sel.k) }.orEmpty() }
    val bios = remember(prox, b) { prox?.let { biorritmos(b, it) }.orEmpty() }
    val efectivos = if (sel.bio) conBiorritmo(sel.permitidos, bios, sel.bioUmbral) else sel.permitidos
    LaunchedEffect(pr, sel.umbral) { if (!sel.editado) sel.permitidos = autoPermitidos(pr, sel.umbral) }
    val excel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val g = sel.resultado ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    val partidos = prox?.partidos.orEmpty()
                    val sh = Xlsx.Sheet("Columnas") { emit ->
                        emit(listOf<Any?>("Nº") + (1..14).map { i -> partidos.getOrNull(i - 1)?.let { "$i. ${it.local}-${it.visit}" } ?: "$i" } + listOf("Máscara"))
                        g.columnas.forEachIndexed { n, c -> val t = Generador.texto(c); emit(listOf<Any?>(n + 1) + t.map { it.toString() } + listOf(Mascara.de(t).toString())) }
                    }
                    Xlsx.write(out, listOf(sh))
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prox == null) item { Text("No hay jornadas publicadas todavía.") } else jornadaItems(b, sel, prox, pr, bios, efectivos)
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("1 · Máscaras (cuántos 1, X y 2)", fontWeight = FontWeight.Bold)
                    Text("De media una quiniela trae ${dec(est.media[0], 1)} unos, ${dec(est.media[1], 1)} equis y ${dec(est.media[2], 1)} doses " +
                        "(${est.total} jornadas desde 1998). Se juegan solo las máscaras más habituales.", style = MaterialTheme.typography.bodySmall)
                    Text("Cubrir el ${sel.cobertura.toInt()} % de las quinielas históricas", style = MaterialTheme.typography.labelMedium)
                    Slider(value = sel.cobertura, onValueChange = { sel.cobertura = it; sel.mascaras = est.masFrecuentes(it.toDouble()); sel.resultado = null }, valueRange = 30f..98f)
                    val ms = sel.mascaras.orEmpty()
                    Text("${ms.size} máscaras · cubren el ${dec(est.cobertura(ms), 1)} %", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        est.mascaras.take(30).forEach { (m, _) ->
                            FilterChip(selected = m in ms, onClick = {
                                sel.mascaras = if (m in ms) ms - m else ms + m; sel.resultado = null
                            }, label = { Text("$m · ${dec(est.porcentaje(m), 1)}%", fontSize = 11.sp) })
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("2 · Tendencia (arriba, partido a partido)", fontWeight = FontWeight.Bold)
                    val d = sel.permitidos.count { Integer.bitCount(it) == 2 }; val t = sel.permitidos.count { it == 7 }
                    Text("Signos elegidos: ${14 - d - t} fijos, $d dobles, $t triples " +
                        "(${miles(sel.permitidos.fold(1L) { a, m -> a * Integer.bitCount(m) })} columnas antes de las máscaras).",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Biorritmo", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text("Media del biorritmo (físico 23 días, emocional 28, intelectual 33) de los 11 jugadores que más veces " +
                        "han sido titulares esta temporada, el día del partido. Solo equipos de 1ª y 2ª. Si un equipo está por debajo " +
                        "del otro más que el umbral, se quita el signo que le daría la victoria. Ojo: es una teoría sin respaldo científico.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = sel.bio, onCheckedChange = { sel.bio = it; sel.resultado = null })
                        Text("  Aplicar filtro biorritmo · umbral ${"%.2f".format(sel.bioUmbral)}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (sel.bio) Slider(value = sel.bioUmbral, onValueChange = { sel.bioUmbral = it; sel.resultado = null }, valueRange = 0.05f..0.8f)
                    val antes = sel.permitidos.fold(1L) { a, m -> a * Integer.bitCount(m) }
                    val despues = efectivos.fold(1L) { a, m -> a * Integer.bitCount(m) }
                    if (sel.bio) Text("Con el biorritmo: ${miles(antes)} → ${miles(despues)} columnas antes de las máscaras " +
                        "(${bios.count { it.first != null && it.second != null }} partidos con datos de los dos equipos).",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("3 · Racha máxima de signos iguales seguidos", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0, 3, 4, 5, 6).forEach { r ->
                            FilterChip(selected = sel.racha == r, onClick = { sel.racha = r; sel.resultado = null },
                                label = { Text(if (r == 0) "Sin límite" else "$r") })
                        }
                    }
                    Text("4 · Cuántas columnas", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(8, 16, 50, 100, 500, 2000, 20000).forEach { n ->
                            FilterChip(selected = sel.tope == n, onClick = { sel.tope = n; sel.resultado = null }, label = { Text(miles(n.toLong())) })
                        }
                    }
                    Text("Cómo elegirlas", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                    FilterChip(selected = sel.modo == Generador.Modo.POR_MASCARA, onClick = { sel.modo = Generador.Modo.POR_MASCARA; sel.resultado = null },
                        label = { Text("Las más probables de cada máscara") })
                    if (sel.modo == Generador.Modo.POR_MASCARA) {
                        Text("Para cada máscara elegida, las columnas con más probabilidad según rachas (y biorritmo): " +
                            "${sel.porMascara} por máscara × ${sel.mascaras.orEmpty().size} máscaras = ${sel.porMascara * sel.mascaras.orEmpty().size} columnas como máximo.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1, 2, 3, 5, 10, 25).forEach { n ->
                                FilterChip(selected = sel.porMascara == n, onClick = { sel.porMascara = n; sel.resultado = null }, label = { Text("$n") })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = sel.modo == Generador.Modo.PROBABLES, onClick = { sel.modo = Generador.Modo.PROBABLES; sel.resultado = null }, label = { Text("Las más probables") })
                        FilterChip(selected = sel.modo == Generador.Modo.AZAR, onClick = { sel.modo = Generador.Modo.AZAR; sel.resultado = null }, label = { Text("Al azar (solar)") })
                    }
                    Button(enabled = !calculando && sel.mascaras.orEmpty().isNotEmpty(), onClick = {
                        calculando = true
                        scope.launch {
                            val probs = Array(14) { i -> pr.getOrNull(i)?.p ?: doubleArrayOf(1 / 3.0, 1 / 3.0, 1 / 3.0) }
                            val ms = sel.mascaras.orEmpty()
                            val g = withContext(Dispatchers.Default) {
                                Generador.generar(efectivos, ms, sel.racha, probs,
                                    if (sel.modo == Generador.Modo.POR_MASCARA) sel.porMascara else sel.tope, sel.modo, SolarEntropy.random(),
                                    Generador.aciertosHistoricos(b.quinielas, ms, sel.racha))
                            }
                            sel.resultado = g; calculando = false
                        }
                    }) { Text(if (calculando) "Calculando…" else "Generar columnas") }
                    if (calculando) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        sel.resultado?.let { g ->
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text("${miles(g.total)} columnas cumplen todo · elegidas ${miles(g.columnas.size.toLong())}", fontWeight = FontWeight.Bold)
                        val apuestas = g.columnas.size.coerceAtLeast(2)
                        Text("Coste: ${dec(apuestas * Generador.PRECIO)} € (${Generador.PRECIO} € por apuesta; mínimo 2 apuestas)", style = MaterialTheme.typography.bodySmall)
                        Text("Probabilidad de 14 aciertos según el modelo: ${if (g.probAcierto < 0.0001) "%.2e".format(g.probAcierto) else dec(g.probAcierto * 100, 3) + " %"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Las máscaras${if (sel.racha > 0) " y la racha" else ""} elegidas habrían incluido la combinación ganadora en el ${dec(g.coberturaHist, 1)} % de las quinielas desde 1998.",
                            style = MaterialTheme.typography.bodySmall)
                        if (hayReal) {
                            val esc = remember(g, real) { Escrutinio(real, g.columnas.map { Generador.texto(it) }) }
                            Text("Escrutinio con el resultado real", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                            Text("Mejor columna: ${esc.mejor} aciertos de ${esc.jugados}" + if (esc.jugados == 14) " · columnas con premio (10 o más): ${esc.premiadas}" else "",
                                color = if (esc.mejor >= 10) Verde else Rojo, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            Text((14 downTo 8).joinToString(" · ") { "$it: ${esc.reparto[it]}" } + " · menos: ${(0..7).sumOf { esc.reparto[it] }}",
                                fontFamily = Maquina, style = MaterialTheme.typography.bodySmall)
                            val fallos = (0 until 14).filter { real[it] != ' ' }.map { i -> i to g.columnas.count { Generador.texto(it)[i] != real[i] } }
                                .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)
                            if (fallos.isNotEmpty()) Text("Partidos que más columnas fallan: " + fallos.joinToString(", ") {
                                "${it.first + 1} (${pct(it.second.toDouble() / g.columnas.size)})" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val txt = buildString {
                                    append("Quiniela nº ${prox?.numero ?: ""}\n")
                                    g.columnas.forEachIndexed { n, c -> append("${n + 1}. ${Generador.texto(c)}\n") }
                                }
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Compartir columnas"))
                            }) { Text("Compartir") }
                            OutlinedButton(onClick = { excel.launch("quiniela_${prox?.numero ?: ""}.xlsx") }) { Text("Excel") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = verBoleto, onClick = { verBoleto = true }, label = { Text("Boleto") })
                    FilterChip(selected = !verBoleto, onClick = { verBoleto = false }, label = { Text("Lista por máscara") })
                }
            }
            if (verBoleto) {
                val bloques = g.columnas.take(400).toList().chunked(8)
                items(bloques.size) { k ->
                    Boleto(k + 1, k * 8 + 1, prox?.partidos.orEmpty(), bloques[k], prox?.jornada, prox?.fecha.orEmpty(),
                        if (hayReal) real else String(CharArray(14) { i -> pr.getOrNull(i)?.p?.let { p -> SIGNOS[p.indices.maxByOrNull { p[it] }!!] } ?: ' ' }),
                        if (hayReal) real else null)
                }
            } else items(g.columnas.take(500).withIndex().toList()) { (n, c) ->
                val t = Generador.texto(c)
                val m = Mascara.de(t)
                val prev = if (n > 0) Mascara.de(Generador.texto(g.columnas[n - 1])) else null
                if (g.porMascara.isNotEmpty() && m != prev) Text(
                    "Máscara $m · ${dec(b.estadistica.porcentaje(m), 1)} % de las quinielas · ${miles(g.porMascara[m.codigo] ?: 0)} columnas posibles con los filtros",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("${n + 1}.", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
                    val txt = androidx.compose.ui.text.buildAnnotatedString {
                        t.forEachIndexed { i, c ->
                            val r = real.getOrNull(i) ?: ' '
                            if (r != ' ' && r != c) withStyle(androidx.compose.ui.text.SpanStyle(color = Rojo,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(c) } else append(c)
                            if (i < t.length - 1) append(' ')
                        }
                    }
                    Text(txt, fontFamily = Maquina, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (hayReal) Text("${t.indices.count { real.getOrNull(it) == t[it] }} ac.  ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(Mascara.de(t).toString(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ============================================================ ESTADÍSTICA

@Composable
private fun EstadisticaVista(b: Base) {
    val e = b.estadistica
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("${e.total} quinielas (${b.quinielas.first().fecha.take(4)}–${b.quinielas.last().fecha.take(4)})", fontWeight = FontWeight.Bold)
                    listOf("Unos", "Equis", "Doses").forEachIndexed { i, n ->
                        Text("$n: media ${dec(e.media[i])} (últimas 5 temporadas ${dec(e.mediaUltimas[i])}) · mín ${e.minimo[i]} · máx ${e.maximo[i]}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("¿Cuántas quinielas tuvieron k signos de cada tipo?", fontWeight = FontWeight.Bold)
                    Row { Text("k", Modifier.width(36.dp), fontWeight = FontWeight.SemiBold); listOf("1", "X", "2").forEach { Text(it, Modifier.weight(1f), fontWeight = FontWeight.SemiBold) } }
                    for (k in 0..14) {
                        if (e.reparto.all { it[k] == 0 }) continue
                        Row {
                            Text("$k", Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall)
                            for (i in 0..2) Text("${e.reparto[i][k]} (${dec(e.reparto[i][k] * 100.0 / e.total, 1)}%)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Text("Máscaras más frecuentes (unos-equis-doses)", fontWeight = FontWeight.Bold) }
        item {
            var acc = 0
            Column {
                e.mascaras.take(40).forEach { (m, n) ->
                    acc += n
                    Row {
                        Text(m.toString(), Modifier.width(70.dp), fontFamily = Maquina, fontWeight = FontWeight.SemiBold)
                        Text("$n veces · ${dec(n * 100.0 / e.total, 1)} %", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("acumulado ${dec(acc * 100.0 / e.total, 0)} %", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Por posición del partido (% de 1 · X · 2)", fontWeight = FontWeight.Bold)
                    for (p in 0 until 14) Text("${p + 1}: ${dec(e.porPosicion[p][0], 0)} · ${dec(e.porPosicion[p][1], 0)} · ${dec(e.porPosicion[p][2], 0)}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = Maquina)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Racha más larga de signos iguales seguidos", fontWeight = FontWeight.Bold)
                    var acc = 0
                    for (r in 1..14) { if (e.rachas[r] == 0) continue; acc += e.rachas[r]
                        Text("$r: ${e.rachas[r]} quinielas (${dec(e.rachas[r] * 100.0 / e.total, 1)} %, acumulado ${dec(acc * 100.0 / e.total, 0)} %)", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Text("Base de la tendencia: ${miles(b.ligas.size.toLong())} partidos de 1ª y 2ª desde 2000 (football-data.co.uk). En casa: " +
                "gana ${pct(b.tendencias.base[0] / b.tendencias.base.sum().toDouble())}, empata ${pct(b.tendencias.base[1] / b.tendencias.base.sum().toDouble())}, " +
                "pierde ${pct(b.tendencias.base[2] / b.tendencias.base.sum().toDouble())}.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ============================================================ HISTÓRICO

@Composable
private fun HistoricoVista(b: Base) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp)) {
        items(b.quinielas.asReversed().take(400)) { q ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${q.fecha.substring(8, 10)}/${q.fecha.substring(5, 7)}/${q.fecha.take(4)}", Modifier.width(86.dp), style = MaterialTheme.typography.labelSmall)
                Text(q.signos.chunked(1).joinToString(" "), fontFamily = Maquina, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(Mascara.de(q.signos).toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Suppress("unused") private fun Modifier.caja() = this.border(1.dp, Verde, RoundedCornerShape(6.dp)).size(10.dp)
