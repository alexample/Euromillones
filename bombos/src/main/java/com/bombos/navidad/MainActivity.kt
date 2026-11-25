package com.bombos.navidad

import android.Manifest
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val Rojo = Color(0xFF9F1239)
private val Oro = Color(0xFFC9A227)
private val Boj = Color(0xFFE9CF9B)
private val Verde = Color(0xFF15803D)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsOk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sorteo.init(this)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
                tts?.setSpeechRate(1.35f)
                tts?.setPitch(1.5f)    // voz aguda, como la de los niños
                ttsOk = true
            }
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Rojo, secondary = Oro)) {
                App(speak = { s -> if (ttsOk) tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "canto") })
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")
private val FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-ES"))
private fun hora(t: Long) = LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC).format(HORA)
private fun fecha(t: Long) = LocalDateTime.ofEpochSecond(t, 0, ZoneOffset.UTC).format(FECHA)
private fun miles(n: Int) = "%,d".format(Locale.forLanguageTag("es-ES"), n)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(speak: (String) -> Unit) {
    val e by Sorteo.estado.collectAsState()
    val running by Sorteo.running.collectAsState()
    val vel by Sorteo.velocidad.collectAsState()
    val msg by Sorteo.mensaje.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var voz by rememberSaveable { mutableStateOf(true) }
    var verSorteo by rememberSaveable { mutableLongStateOf(0L) }
    val snack = remember { SnackbarHostState() }

    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permiso.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); Sorteo.limpiarMensaje() } }
    LaunchedEffect(voz, vel) {
        if (voz && vel != Velocidad.MAX && vel <= 2f) Sorteo.cantos.collect { speak(it) }
    }
    var exportId by remember { mutableLongStateOf(0L) }
    val exportar = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri -> if (uri != null) Sorteo.exportar(exportId, uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bombos de Navidad", fontWeight = FontWeight.Bold)
                        Text("${fecha(e.reloj)} · ${hora(e.reloj)} · ${Velocidad.texto(vel)}", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Rojo, titleContentColor = Color.White),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Sorteo", "Tabla", "Premios", "Buscar", "Sorteos").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 12.sp) })
                }
            }
            val sid = if (verSorteo == 0L) e.sorteo else verSorteo
            when (tab) {
                0 -> Escenario(e, running, vel, voz, onVoz = { voz = it })
                1 -> TablaVista(sid, e)
                2 -> PremiosVista(sid, e)
                3 -> Buscar(sid, e.sorteo)
                4 -> Sorteos(e.sorteo, verSorteo, onVer = { verSorteo = it; tab = 3 }, onExcel = { id ->
                    exportId = id; exportar.launch("sorteo_navidad_$id.xlsx")
                })
            }
        }
    }
}

// ============================================================ ESCENARIO

@Composable
private fun Escenario(e: Estado, running: Boolean, vel: Float, voz: Boolean, onVoz: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Bombos(e) }
        item {
            // Lo que se está cantando
            Card(colors = CardDefaults.cardColors(containerColor = if (e.cantoPremio) Color(0xFFFFF7E6) else Color(0xFFF1F5F9))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val last = e.ultimas.firstOrNull()
                    Text(
                        if (e.canto.isEmpty()) "—" else e.canto,
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                        color = if (e.cantoPremio) Rojo else Color.Unspecified, fontWeight = FontWeight.SemiBold,
                    )
                    if (last != null) Text(
                        "Última: ${Canto.n5(last.numero)} → ${last.cat.nombre} · a las ${hora(last.t)} · tabla ${last.tabla}, alambre ${last.alambre}, bola ${last.pos}",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(e.fase.titulo, fontWeight = FontWeight.Bold, color = Rojo)
                    Text(e.fase.texto, style = MaterialTheme.typography.bodySmall)
                    if (e.fase == Fase.RECUENTO) Text("Liras revisadas: ${e.liras} de ${SorteoCore.LIRAS_NUMEROS + SorteoCore.LIRAS_PREMIOS}",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (e.fase == Fase.EXTRACCION || e.fase == Fase.CIERRE_TABLA) {
                        Text("Tabla ${e.tabla} de 10 · alambre ${e.alambre} de 10 · bola ${e.pos} de 20 · ${miles(e.extraidas)} de 1.807 bolas",
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        e.paso?.let { Text("Ahora: " + pasoTexto(it), style = MaterialTheme.typography.labelSmall) }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { e.progreso }, modifier = Modifier.fillMaxWidth())
                    if (!e.fisica && (e.fase == Fase.EXTRACCION)) Text(
                        "A esta velocidad la bola de premios se elige al azar entre las que quedan (mezcla perfecta). " +
                            "Hasta 2× la decide la física del bombo.", style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = Sorteo::alternar, enabled = e.fase != Fase.TERMINADO) { Text(if (running) "⏸ Pausar" else "▶ Seguir") }
                OutlinedButton(onClick = Sorteo::saltar, enabled = e.fase != Fase.TERMINADO) {
                    Text(if (e.fase == Fase.EXTRACCION) "Saltar tabla" else "Saltar fase")
                }
                TextButton(onClick = Sorteo::nuevo) { Text("Nuevo") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Velocidad.LISTA.forEach { v ->
                    FilterChip(selected = vel == v, onClick = { Sorteo.setVelocidad(v) }, label = { Text(Velocidad.texto(v)) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = voz, onCheckedChange = onVoz)
                Text("  Cantar con voz (hasta 2×)", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Fisica3D(e) }
        item { Calendario(e) }
        item {
            Text("Bombo de premios: quedan", fontWeight = FontWeight.SemiBold)
            Text(Cat.entries.joinToString(" · ") { "${corto(it)} ${e.quedan[it.ordinal]}" }, style = MaterialTheme.typography.bodySmall)
        }
        if (e.mayores.isNotEmpty()) {
            item { Text("Premios mayores", fontWeight = FontWeight.SemiBold) }
            items(e.mayores.sortedBy { it.cat.ordinal }) { x -> FilaBola(x, grande = true) }
        }
        item { Text("Últimas bolas", fontWeight = FontWeight.SemiBold) }
        items(e.ultimas.take(30)) { x -> FilaBola(x) }
        if (!e.modo3d) item {
            Text(
                "Física: sección vertical de cada bombo con bolas de boj de 18,8 mm y 3 g; el grande (1,58 m) muestra " +
                    "${miles(SorteoCore.NUM_VISIBLES)} bolas que representan las 100.000 y el de premios simula las 1.807 una a una. " +
                    "Semilla del azar: ${e.semilla.take(16)}… (rayos X del Sol, NOAA)",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun Calendario(e: Estado) {
    val on by Sorteo.calendario.collectAsState()
    var ver by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Premios a la hora de 2025", fontWeight = FontWeight.Bold)
            Text(
                if (e.calendario) "Este sorteo saca cada premio mayor a la misma hora que en el sorteo real de 2025. " +
                    "El número premiado es el que salga en ese momento del bombo grande."
                else "Este sorteo usa el bombo de premios normal.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = on, onCheckedChange = Sorteo::setCalendario)
                Text("  Usar las horas de 2025 en el próximo sorteo nuevo", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { ver = !ver }) { Text(if (ver) "Ocultar horas de 2025" else "Ver horas de 2025") }
            if (ver) Calendario2025.HITOS.forEach { h ->
                Text("${Calendario2025.hora(h.segundo)} · ${h.nota}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Fisica3D(e: Estado) {
    val m3 by Sorteo.modo3d.collectAsState()
    val prueba by Sorteo.prueba.collectAsState()
    val probando by Sorteo.probando.collectAsState()
    Card {
        Column(Modifier.padding(12.dp)) {
            Text("Física completa 3D", fontWeight = FontWeight.Bold)
            Text(
                if (e.modo3d) "Este sorteo usa el bombo completo: las 100.000 bolas una a una, cada una con su número, y la física decide qué bola sale de los dos bombos."
                else "Este sorteo usa el corte 2D (rápido).",
                style = MaterialTheme.typography.bodySmall,
            )
            if (e.esperandoFisica) Text("⏳ El móvil va más despacio que el tiempo real: el reloj del sorteo espera a la física.",
                style = MaterialTheme.typography.bodySmall, color = Rojo)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = m3, onCheckedChange = Sorteo::setModo3d)
                Text("  Usar 3D (100.000 bolas) en el próximo sorteo nuevo", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = Sorteo::probar, enabled = !probando) { Text(if (probando) "Midiendo…" else "Prueba de rendimiento") }
            prueba?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Con física completa la velocidad útil es 1×: si el móvil no llega, el sorteo dura más. Conviene tenerlo enchufado.",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun pasoTexto(p: Paso) = when (p) {
    Paso.MEZCLA -> "giran los bombos"
    Paso.ASIENTO -> "se paran los bombos"
    Paso.SACANDO -> "sacan las bolas (bajan por la trompeta a la copa)"
    Paso.CANTA_NUMERO -> "canta el número"
    Paso.CANTA_PREMIO -> "canta el premio"
    Paso.CELEBRA -> "¡premio mayor! se repite y se comprueba"
    Paso.INSERTA -> "ensartan las bolas en el alambre"
}

private fun corto(c: Cat) = when (c) {
    Cat.GORDO -> "Gordo"; Cat.SEGUNDO -> "2º"; Cat.TERCERO -> "3º"; Cat.CUARTO -> "4º"; Cat.QUINTO -> "5º"; Cat.PEDREA -> "pedrea"
}

@Composable
private fun FilaBola(x: Extraccion, grande: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Canto.n5(x.numero), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp),
            color = if (x.cat.grande) Rojo else Color.Unspecified)
        Text(if (grande) x.cat.nombre else "${x.cat.bola} €", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            fontWeight = if (x.cat.grande) FontWeight.Bold else FontWeight.Normal)
        Column(horizontalAlignment = Alignment.End) {
            Text(hora(x.t), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text("T${x.tabla}·A${x.alambre}·${x.pos}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ============================================================ DIBUJO DE LOS BOMBOS

@Composable
private fun Bombos(e: Estado) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { tick = it } }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Boj.toArgb() } }
    val text = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true } }
    val bufA = remember { Array(3) { FloatArray(200_400) } }
    val bufB = remember { Array(3) { FloatArray(4_000) } }
    Canvas(Modifier.fillMaxWidth().height(330.dp).background(Color(0xFF3B0A1A), RoundedCornerShape(12.dp))) {
        if (tick < 0L) return@Canvas
        val f = Sorteo.frame ?: return@Canvas
        val w = size.width
        val h = size.height
        val rb = min(w * 0.245f, h * 0.36f)
        val s = rb / f.numR
        val rs = f.preR * s
        val cb = Offset(w * 0.27f, h * 0.40f)
        val cs = Offset(w * 0.76f, h * 0.40f + (rb - rs) * 0.4f)
        bombo(cb, rb, f.numAngle, f.numGate, f.numXY, f.numN, f.stride, f.numR, s, f.ballR, paint, bufA)
        bombo(cs, rs, f.preAngle, f.preGate, f.preXY, f.preN, f.stride, f.preR, s, f.ballR, paint, bufB)
        // Tolvas durante la carga
        if (f.tolva > 0f) {
            tolva(Offset(cb.x - rb * 0.35f, cb.y - rb * 1.05f), rb * 0.28f, f.tolva)
            tolva(Offset(cb.x + rb * 0.35f, cb.y - rb * 1.05f), rb * 0.28f, f.tolva)
            tolva(Offset(cs.x, cs.y - rs * 1.1f), rs * 0.3f, f.tolva)
        }
        // Trompetas y copas
        trompetaYCopa(cb, rb, h, f.copaNum, f.fly, s * f.ballR, text, izquierda = false)
        trompetaYCopa(cs, rs, h, f.copaPre?.let { "$it €" }, f.fly, s * f.ballR, text, izquierda = true)
        drawIntoCanvas { c ->
            text.color = Color.White.toArgb(); text.textSize = 11.dp.toPx(); text.isFakeBoldText = false
            c.nativeCanvas.drawText("Números · ${miles(100_000 - e.extraidas)} bolas", cb.x, cb.y + rb + 14.dp.toPx(), text)
            c.nativeCanvas.drawText("Premios · ${miles(f.preN)} bolas", cs.x, cs.y + rs + 14.dp.toPx(), text)
            text.isFakeBoldText = true
        }
    }
}

private val CAPAS = intArrayOf(0xFF8A6E3E.toInt(), 0xFFC2A36B.toInt(), 0xFFE9CF9B.toInt())   // fondo, medio, delante

private fun DrawScope.bombo(c: Offset, r: Float, angle: Float, gate: Boolean, xy: FloatArray, n: Int, stride: Int, R: Float, s: Float, ballR: Float,
                            paint: Paint, buf: Array<FloatArray>) {
    // Patas
    drawLine(Oro.copy(alpha = 0.7f), Offset(c.x - r * 0.6f, c.y + r * 1.25f), Offset(c.x, c.y + r * 0.2f), strokeWidth = 3.dp.toPx())
    drawLine(Oro.copy(alpha = 0.7f), Offset(c.x + r * 0.6f, c.y + r * 1.25f), Offset(c.x, c.y + r * 0.2f), strokeWidth = 3.dp.toPx())
    drawCircle(Color(0x22FFFFFF), r, c)
    // Bolas (en 3D, por capas de profundidad: las de detrás más oscuras)
    paint.strokeWidth = (ballR * 2f * s).coerceAtLeast(2f)
    if (stride == 2) {
        val k = min(n, buf[2].size / 2)
        for (i in 0 until k) { buf[2][2 * i] = c.x + xy[2 * i] * s; buf[2][2 * i + 1] = c.y - xy[2 * i + 1] * s }
        paint.color = CAPAS[2]
        drawIntoCanvas { it.nativeCanvas.drawPoints(buf[2], 0, k * 2, paint) }
    } else {
        val cnt = IntArray(3)
        val lim = min(n, xy.size / 3)
        for (i in 0 until lim) {
            val zz = xy[3 * i + 2]
            val capa = if (zz < -R * 0.33f) 0 else if (zz < R * 0.33f) 1 else 2
            val b = buf[capa]
            val k = cnt[capa]
            if (2 * k + 1 >= b.size) continue
            b[2 * k] = c.x + xy[3 * i] * s; b[2 * k + 1] = c.y - xy[3 * i + 1] * s
            cnt[capa] = k + 1
        }
        for (capa in 0..2) {
            paint.color = CAPAS[capa]
            drawIntoCanvas { it.nativeCanvas.drawPoints(buf[capa], 0, cnt[capa] * 2, paint) }
        }
    }
    // Jaula de latón que gira
    for (j in 0 until 8) {
        val a = angle + j * (PI / 4).toFloat()
        drawLine(Oro.copy(alpha = 0.45f), c, Offset(c.x + cos(a) * r, c.y - sin(a) * r), strokeWidth = 1.5.dp.toPx())
    }
    drawCircle(Oro.copy(alpha = 0.5f), r * 0.55f, c, style = Stroke(1.dp.toPx()))
    drawCircle(Oro, r, c, style = Stroke(3.dp.toPx()))
    drawCircle(Oro, r * 0.07f, c)
    // Compuerta abajo
    val gw = ballR * 2.2f * s
    drawLine(if (gate) Color(0xFF22C55E) else Color(0xFF111827), Offset(c.x - gw, c.y + r), Offset(c.x + gw, c.y + r), strokeWidth = 4.dp.toPx())
}

private fun DrawScope.tolva(top: Offset, w: Float, nivel: Float) {
    val p = Path().apply {
        moveTo(top.x - w, top.y - w * 0.6f); lineTo(top.x + w, top.y - w * 0.6f); lineTo(top.x + w * 0.2f, top.y + w * 0.6f)
        lineTo(top.x - w * 0.2f, top.y + w * 0.6f); close()
    }
    drawPath(p, Color(0x55BFDBFE))
    val fill = Path().apply {
        val y0 = top.y + w * 0.6f - w * 1.2f * nivel
        val half = 0.2f * w + (w - 0.2f * w) * nivel
        moveTo(top.x - half, y0); lineTo(top.x + half, y0); lineTo(top.x + w * 0.2f, top.y + w * 0.6f); lineTo(top.x - w * 0.2f, top.y + w * 0.6f); close()
    }
    drawPath(fill, Boj.copy(alpha = 0.85f))
    drawPath(p, Color(0xFFBFDBFE), style = Stroke(1.5.dp.toPx()))
}

/** Trompeta desde la compuerta hasta la copa; la bola baja mientras fly va de 0 a 1. */
private fun DrawScope.trompetaYCopa(c: Offset, r: Float, h: Float, label: String?, fly: Float, ballPx: Float, text: Paint, izquierda: Boolean): Offset {
    val start = Offset(c.x, c.y + r)
    val dir = if (izquierda) -1f else 1f
    val copa = Offset(c.x + dir * r * 0.95f, min(h - 26.dp.toPx(), c.y + r * 1.35f))
    val ctrl = Offset(c.x + dir * r * 0.1f, copa.y)
    val p = Path().apply { moveTo(start.x, start.y); quadraticBezierTo(ctrl.x, ctrl.y, copa.x, copa.y - 6.dp.toPx()) }
    drawPath(p, Oro, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
    // Copa de cristal
    val cw = 22.dp.toPx()
    val cup = Path().apply {
        moveTo(copa.x - cw, copa.y - 8.dp.toPx()); lineTo(copa.x + cw, copa.y - 8.dp.toPx())
        lineTo(copa.x + cw * 0.6f, copa.y + 14.dp.toPx()); lineTo(copa.x - cw * 0.6f, copa.y + 14.dp.toPx()); close()
    }
    drawPath(cup, Color(0x66BFDBFE))
    drawPath(cup, Color(0xFFDBEAFE), style = Stroke(1.5.dp.toPx()))
    val ballR = (ballPx * 3.2f).coerceAtLeast(9.dp.toPx())
    if (fly in 0f..1f) {
        val t = fly
        val x = (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * ctrl.x + t * t * copa.x
        val y = (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * ctrl.y + t * t * (copa.y - 6.dp.toPx())
        drawCircle(Boj, ballR, Offset(x, y))
    } else if (label != null) {
        drawCircle(Boj, ballR * 1.6f, Offset(copa.x, copa.y + 2.dp.toPx()))
        drawIntoCanvas {
            text.color = Color(0xFF3B0A1A).toArgb(); text.textSize = 9.5.dp.toPx()
            it.nativeCanvas.drawText(label, copa.x, copa.y + 5.dp.toPx(), text)
        }
    }
    return copa
}

// ============================================================ TABLA

@Composable
private fun TablaVista(sid: Long, e: Estado) {
    val ver by Sorteo.version.collectAsState()
    var n by rememberSaveable(sid) { mutableIntStateOf(0) }
    val tabla = if (n == 0) (if (sid == e.sorteo) e.tabla.coerceAtMost(10) else 1) else n
    val filas by produceState(emptyList<Extraccion>(), sid, tabla, ver, e.extraidas / 5) {
        value = withContext(Dispatchers.IO) { Sorteo.db.tabla(sid, tabla) }
    }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..10).forEach { t -> FilterChip(selected = tabla == t, onClick = { n = t }, label = { Text("T$t") }) }
        }
        Text(
            "Tabla $tabla · ${filas.size} de 200 bolas de cada clase (10 alambres de 20)" +
                if (filas.isNotEmpty()) " · de ${hora(filas.first().t)} a ${hora(filas.last().t)}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..10).forEach { a ->
                val al = filas.filter { it.alambre == a }
                if (al.isNotEmpty()) {
                    item(key = "a$a") {
                        Column(Modifier.fillMaxWidth().border(1.dp, Oro, RoundedCornerShape(8.dp)).padding(6.dp)) {
                            Text("Alambre $a", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            al.chunked(5).forEach { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    row.forEach { b ->
                                        Column(Modifier.weight(1f).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(Canto.n5(b.numero), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold, color = if (b.cat.grande) Rojo else Color.Unspecified)
                                            Text(b.cat.bola, fontSize = 10.sp, color = if (b.cat.grande) Rojo else Color.Gray)
                                            Text(hora(b.t), fontSize = 9.sp, color = Color.Gray)
                                        }
                                    }
                                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================ PREMIOS

@Composable
private fun PremiosVista(sid: Long, e: Estado) {
    val ver by Sorteo.version.collectAsState()
    val data by produceState<Pair<List<Extraccion>, Mayores>?>(null, sid, ver) {
        value = withContext(Dispatchers.IO) {
            Cat.entries.filter { it.grande }.flatMap { Sorteo.db.porPremio(sid, it) } to Sorteo.db.mayores(sid)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Sorteo nº $sid", fontWeight = FontWeight.Bold) }
        val d = data
        if (d == null) item { Text("Cargando…") } else {
            items(d.first.sortedBy { it.cat.ordinal }) { x -> FilaBola(x, grande = true) }
            item {
                val pedrea = if (sid == e.sorteo) Cat.PEDREA.count - e.quedan[Cat.PEDREA.ordinal] else null
                Text("Pedrea: ${pedrea?.let { "$it de " } ?: ""}1.794 números de 1.000 € (búscalos en «Buscar»)", style = MaterialTheme.typography.bodySmall)
            }
            item { Text("Premios derivados", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(Derivado.entries) { dv ->
                val nums = d.second.numeros(dv)
                Column(Modifier.fillMaxWidth()) {
                    Text("${dv.nombre} · ${Canto.euros(dv.importe)} por billete", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (nums.isEmpty()) "Aún no ha salido el premio del que depende"
                        else if (nums.size <= 4) nums.joinToString(" · ") { Canto.n5(it) }
                        else "${miles(nums.size)} números: ${Canto.n5(nums.first())} … ${Canto.n5(nums.last())}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

// ============================================================ BUSCAR

private sealed interface Filtro {
    data class Directo(val c: Cat) : Filtro
    data class Indirecto(val d: Derivado) : Filtro
}

@Composable
private fun Buscar(sid: Long, actual: Long) {
    val ver by Sorteo.version.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var filtro by remember { mutableStateOf<Filtro?>(null) }
    val digits = q.filter { it.isDigit() }.take(5)
    val resultado by produceState<List<String>?>(null, sid, digits, filtro, ver) {
        value = withContext(Dispatchers.IO) {
            val m = Sorteo.db.mayores(sid)
            when {
                digits.length == 5 -> {
                    val n = digits.toInt()
                    val dir = Sorteo.db.buscarNumero(sid, digits)
                    val out = ArrayList<String>()
                    dir.forEach { x -> out += "🎯 ${x.cat.nombre}: ${Canto.euros(x.cat.importe)} por billete (${Canto.euros(x.cat.importe / 10)} el décimo) · tabla ${x.tabla}, alambre ${x.alambre}, bola ${x.pos}, ${hora(x.t)}" }
                    m.derivados(n).forEach { dv -> out += "➕ ${dv.nombre}: ${Canto.euros(dv.importe)} por billete (${Canto.euros(dv.importe / 10)} el décimo)" }
                    val total = dir.sumOf { it.cat.importe } + m.derivados(n).sumOf { it.importe }
                    if (out.isEmpty()) listOf("El $digits no tiene premio (por ahora).")
                    else listOf("Total del $digits: ${Canto.euros(total)} por billete · ${Canto.euros(total / 10)} el décimo") + out
                }
                digits.isNotEmpty() -> Sorteo.db.buscarNumero(sid, digits).map { x -> "${Canto.n5(x.numero)} · ${x.cat.nombre} · T${x.tabla} A${x.alambre} · ${hora(x.t)}" }
                    .ifEmpty { listOf("Ninguna bola extraída contiene «$digits».") }
                else -> when (val f = filtro) {
                    is Filtro.Directo -> Sorteo.db.porPremio(sid, f.c).map { x -> "${Canto.n5(x.numero)} · T${x.tabla} A${x.alambre} bola ${x.pos} · ${hora(x.t)}" }
                        .ifEmpty { listOf("Todavía no ha salido ningún ${f.c.nombre.lowercase()}.") }
                    is Filtro.Indirecto -> m.numeros(f.d).map { Canto.n5(it) }.ifEmpty { listOf("Aún no ha salido el premio del que depende.") }
                    null -> null
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text(if (sid == actual) "Sorteo en curso (nº $sid)" else "Sorteo nº $sid", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = q, onValueChange = { q = it.filter { c -> c.isDigit() }.take(5); if (q.isNotEmpty()) filtro = null },
            label = { Text("Número (5 cifras = todos sus premios)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        Text("O busca por premio:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Cat.entries.forEach { c ->
                FilterChip(selected = filtro == Filtro.Directo(c), onClick = { q = ""; filtro = Filtro.Directo(c) }, label = { Text(corto(c)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Derivado.entries.forEach { d ->
                FilterChip(selected = filtro == Filtro.Indirecto(d), onClick = { q = ""; filtro = Filtro.Indirecto(d) },
                    label = { Text(derivCorto(d)) })
            }
        }
        val r = resultado
        if (r != null) {
            Text("${miles(r.size)} resultados", style = MaterialTheme.typography.labelSmall)
            LazyColumn { items(r) { line -> Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) } }
        }
    }
}

private fun derivCorto(d: Derivado) = when (d) {
    Derivado.APROX_1 -> "Aprox. 1º"; Derivado.APROX_2 -> "Aprox. 2º"; Derivado.APROX_3 -> "Aprox. 3º"
    Derivado.CENTENA_1 -> "Centena 1º"; Derivado.CENTENA_2 -> "Centena 2º"; Derivado.CENTENA_3 -> "Centena 3º"
    Derivado.CENTENA_4 -> "Centenas 4º"; Derivado.TERM_1 -> "2 cifras 1º"; Derivado.TERM_2 -> "2 cifras 2º"
    Derivado.TERM_3 -> "2 cifras 3º"; Derivado.REINTEGRO -> "Reintegro"
}

// ============================================================ SORTEOS

@Composable
private fun Sorteos(actual: Long, visto: Long, onVer: (Long) -> Unit, onExcel: (Long) -> Unit) {
    val ver by Sorteo.version.collectAsState()
    val lista by produceState(emptyList<SorteoInfo>(), ver) { value = withContext(Dispatchers.IO) { Sorteo.db.sorteos() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Button(onClick = Sorteo::nuevo) { Text("Nuevo sorteo") } }
        items(lista, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().clickable { onVer(s.id) }) {
                Column(Modifier.padding(10.dp)) {
                    Text("Sorteo nº ${s.id}" + if (s.id == actual) " · en curso" else if (s.id == visto) " · viendo" else "", fontWeight = FontWeight.Bold)
                    Text(
                        "${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(java.util.Date(s.creado))} · " +
                            "${s.extraidas} de 1.807 bolas · ${if (s.terminado) "terminado" else runCatching { Fase.valueOf(s.fase).titulo }.getOrDefault(s.fase)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = { onVer(s.id) }) { Text("Buscar en él") }
                        TextButton(onClick = { onExcel(s.id) }, enabled = s.extraidas > 0) { Text("Excel") }
                        if (s.id != actual) TextButton(onClick = { Sorteo.borrar(s.id) }) { Text("Borrar") }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.bola(c: Color) = this.size(10.dp).background(c, RoundedCornerShape(5.dp))

@Suppress("unused")
private val sz = Size.Zero
