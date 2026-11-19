package com.geoloteria.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp

private val esL = Locale.forLanguageTag("es-ES")
private val Red = Color(0xFFDC2626)
private val Ok = Color(0xFF16A34A)
private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private fun n0(x: Number) = String.format(esL, "%,d", x.toLong())
private fun d1(x: Double) = String.format(esL, "%.1f", x)
private fun d2(x: Double) = String.format(esL, "%.2f", x)
private fun pc(x: Double) = String.format(esL, "%.2f %%", x * 100)
private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)

private fun modeText(r: BlockReport) = when (r.mode) {
    GenMode.CIUDADES.name -> "En ciudades · " + (runCatching { CityPick.valueOf(r.pick).title }.getOrDefault(""))
    GenMode.ESPANA.name -> GenMode.ESPANA.title
    else -> "Configuración desconocida"
}

/** Recuadro de la pantalla principal: generar, importar y ver los bloques guardados. */
@Composable
fun BlocksCard(
    s: GeoState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onExcel: (String) -> Unit,
    onCsv: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenLive: () -> Unit,
    onCloseLive: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().then(Modifier), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggle, modifier = Modifier.weight(1f)) {
                    Text(
                        "Bloques de ${n0(BLOCK_SIZE)} números · ${s.blocks.size} guardado${if (s.blocks.size == 1) "" else "s"}",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(if (expanded) "▲" else "▼")
            }
            if (!expanded && s.block == null) return@Column

            s.block?.let { b ->
                val frac = if (b.total > 0) b.done.toFloat() / b.total else 0f
                val elapsed = System.currentTimeMillis() - b.startedAt
                val eta = if (b.done > 0) (elapsed.toDouble() / b.done * (b.total - b.done) / 1000).toLong() else -1
                Text("${b.label}…", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                Text(
                    "${n0(b.done)} de ${n0(b.total)}" + if (eta >= 0) " · quedan ~${if (eta < 60) "$eta s" else "${eta / 60} min"}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (b.label != "Exportando a Excel") TextButton(onClick = onCancel) { Text("Cancelar") }
                Spacer(Modifier.height(6.dp))
            }
            if (!expanded) return@Column

            // ---- Bloque en tiempo real ----
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("Bloque en tiempo real", fontWeight = FontWeight.Bold)
                    Text(
                        "Todo lo que generas en automático o de uno en uno se va acumulando aquí. " +
                            "Al llegar a ${n0(BLOCK_SIZE)} se guarda con su informe y empieza otro.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { s.liveN.toFloat() / BLOCK_SIZE },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text("${n0(s.liveN)} de ${n0(BLOCK_SIZE)}", style = MaterialTheme.typography.bodySmall)
                    s.liveReport?.let { r ->
                        Text(
                            if (r.anomalies == 0) "✓ Sin anomalías por ahora" else "⚠ ${r.anomalies} anomalía${if (r.anomalies == 1) "" else "s"} en curso",
                            color = if (r.anomalies == 0) Ok else Red,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${n0(r.distinct)} números distintos · el informe se actualiza cada 10 s",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Row {
                        TextButton(enabled = s.liveReport != null, onClick = onOpenLive) { Text("Informe en vivo") }
                        TextButton(enabled = s.liveN > 0, onClick = onCloseLive) { Text("Cerrar bloque ahora") }
                    }
                }
            }

            Text(
                "Cada bloque genera ${n0(BLOCK_SIZE)} números seguidos (salen ≈98,2 % de los 100.000 posibles), " +
                    "se guarda en el móvil y se analiza: números, cifras, ciudades y comunidades frente a lo esperado por azar.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = s.ready && s.block == null, onClick = onGenerate) { Text("Generar bloque") }
                OutlinedButton(enabled = s.ready && s.block == null, onClick = onImport) { Text("Importar bloque") }
            }
            if (s.blocks.isEmpty()) {
                Text("Aún no hay bloques guardados.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            s.blocks.forEach { r ->
                var confirm by remember(r.id) { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            (if (r.imported) "Importado " else if (r.id.startsWith("tiemporeal")) "Tiempo real · " else "") + date(r.created),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${n0(r.n)} números · ${n0(r.distinct)} distintos (${pc(r.distinct / 100_000.0)}) · ${modeText(r)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (r.anomalies == 0) "✓ Sin anomalías estadísticas" else "⚠ ${r.anomalies} anomalía${if (r.anomalies == 1) "" else "s"}",
                            color = if (r.anomalies == 0) Ok else Red,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { onOpen(r.id) }) { Text("Informe") }
                            TextButton(onClick = { onExcel(r.id) }) { Text("Excel") }
                            TextButton(onClick = { onCsv(r.id) }) { Text("CSV") }
                            TextButton(onClick = { if (confirm) onDelete(r.id) else confirm = true }) {
                                Text(if (confirm) "¿Seguro?" else "Borrar", color = if (confirm) Red else Color.Unspecified)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Informe estadístico de un bloque
// ---------------------------------------------------------------------------------------------

@Composable
fun BlockReportScreen(r: BlockReport, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ---- Resumen ----
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (r.anomalies == 0) Ok.copy(alpha = 0.12f) else Red.copy(alpha = 0.12f),
            )) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        if (r.anomalies == 0) "✓ Todo dentro de lo normal" else "⚠ ${r.anomalies} anomalía${if (r.anomalies == 1) "" else "s"} detectada${if (r.anomalies == 1) "" else "s"}",
                        fontWeight = FontWeight.Bold,
                        color = if (r.anomalies == 0) Ok else Red,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Se marca como anómalo solo lo que el azar no produciría ni 1 vez de cada 20 bloques, " +
                            "teniendo en cuenta que se hacen miles de comprobaciones a la vez.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Info("Bloque", (if (r.imported) "importado · " else "") + date(r.created))
                    Info("Números", n0(r.n))
                    Info("Modo", modeText(r))
                    if (r.durationMs > 0) Info("Tiempo", "${d1(r.durationMs / 1000.0)} s")
                }
            }
        }
        item {
            Section("Origen del azar (hash solar)")
            r.seeds.forEach { sd ->
                Text(
                    "${sd.hex.take(20)}…  ${sd.dataTime?.let { "dato ${it.take(16).replace('T', ' ')} UTC · " } ?: ""}${n0(sd.count)} números",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // ---- Números ----
        item {
            Section("Números (00000-99999)")
            val zerosOk = abs(r.zerosZ) <= 3.3
            val chiOk = abs(r.chi2Z) <= 3.3
            Info("Distintos que salieron", "${n0(r.distinct)} · esperados ${n0(Math.round(r.expectedDistinct))}")
            Verdict("Números que no salieron", "z = ${d2(r.zerosZ)}", zerosOk)
            Verdict("Reparto uniforme (chi²)", "z = ${d2(r.chi2Z)}", chiOk)
            Info("Media por número", "${d2(r.expectedPerNumber)} veces · máximo ${r.maxCount}")
            Info("Anómalo a partir de", "${r.highThreshold} veces")
            Spacer(Modifier.height(4.dp))
            if (r.anomalousNumbers.isEmpty()) {
                Text("✓ Ningún número ha salido de forma anómala.", color = Ok, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("⚠ Números que salieron demasiadas veces:", color = Red, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("Los 20 que más han salido", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Row { H("Número", 80.dp); H("Veces", 70.dp); H("Esperado", 80.dp); H("z", 60.dp) }
            (r.anomalousNumbers + r.topNumbers.filter { t -> r.anomalousNumbers.none { it.label == t.label } }).forEach { x ->
                Row {
                    C(x.label, 80.dp, x.anomalous, mono = true)
                    C("${x.count}", 70.dp, x.anomalous)
                    C(d1(x.expected), 80.dp, false)
                    C(d1(x.z), 60.dp, x.anomalous)
                }
            }
        }
        item {
            Text("Cuántos números salieron N veces", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Row { H("Veces", 70.dp); H("Números", 90.dp); H("Esperado", 90.dp) }
            val lam = r.expectedPerNumber
            var term = exp(-lam)
            r.countHistogram.forEachIndexed { k, c ->
                val e = 100_000 * term
                Row { C("$k", 70.dp, false); C(n0(c), 90.dp, false); C(n0(Math.round(e)), 90.dp, false) }
                term *= lam / (k + 1)
            }
        }

        // ---- Cifras ----
        item {
            Section("Cifras por posición (z)")
            Text(
                "Cada cifra debería salir el 10 % en cada posición. Anómalo si |z| > ${d2(r.digitCrit)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row { H("", 34.dp); (0..9).forEach { H("$it", 30.dp) } }
            (1..5).forEach { p ->
                Row {
                    H("D$p", 34.dp)
                    r.digitRows.filter { it.label == "D$p" }.forEach { x -> C(d1(x.z), 30.dp, x.anomalous) }
                }
            }
        }

        // ---- Ciudades ----
        item {
            Section("Ciudades")
            val noExp = r.cityRows.firstOrNull()?.expected?.isNaN() ?: true
            Text(
                if (noExp) "En «Cualquier punto de España» no hay una frecuencia esperada sencilla: solo se cuentan."
                else "Comparadas con su probabilidad (${n0(r.cityTests)} ciudades). Anómalo si |z| > ${d2(r.cityCrit)}. " +
                    "Se muestran las más desviadas.",
                style = MaterialTheme.typography.bodySmall,
            )
            val bad = r.cityRows.count { it.anomalous }
            if (!noExp) {
                Text(
                    if (bad == 0) "✓ Ninguna ciudad fuera de lo normal." else "⚠ $bad ciudad${if (bad == 1) "" else "es"} fuera de lo normal",
                    color = if (bad == 0) Ok else Red,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(r.cityRows, key = { "c-" + it.label + it.detail }) { x -> CityLine(x) }

        // ---- Comunidades ----
        item {
            Section("Comunidades autónomas")
            if (!(r.regionRows.firstOrNull()?.expected?.isNaN() ?: true)) {
                Text("Anómalo si |z| > ${d2(r.regionCrit)}.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(r.regionRows, key = { "r-" + it.label }) { x -> CityLine(x) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CityLine(x: StatRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(x.label, fontWeight = FontWeight.SemiBold, color = if (x.anomalous) Red else Color.Unspecified,
                style = MaterialTheme.typography.bodySmall)
            if (x.detail.isNotEmpty()) Text(x.detail, style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${n0(x.count)}" + if (!x.expected.isNaN()) " / esp. ${n0(Math.round(x.expected))}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!x.expected.isNaN()) {
                Text("z = ${d2(x.z)}" + if (x.anomalous) "  ANÓMALO" else "",
                    color = if (x.anomalous) Red else Color.Unspecified, fontWeight = if (x.anomalous) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Verdict(label: String, value: String, ok: Boolean) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text("$value · " + if (ok) "normal" else "ANÓMALO", style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold, color = if (ok) Ok else Red)
    }
}

@Composable
private fun H(t: String, w: Dp) {
    Box(Modifier.width(w).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(vertical = 3.dp),
        contentAlignment = Alignment.Center) {
        Text(t, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun C(t: String, w: Dp, bad: Boolean, mono: Boolean = false) {
    Box(Modifier.width(w).background(if (bad) Red.copy(alpha = 0.15f) else Color.Transparent).padding(vertical = 3.dp),
        contentAlignment = Alignment.Center) {
        Text(t, style = MaterialTheme.typography.labelSmall, color = if (bad) Red else Color.Unspecified,
            fontWeight = if (bad) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}
