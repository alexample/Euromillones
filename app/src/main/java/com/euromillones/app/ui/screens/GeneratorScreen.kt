package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.GenTarget
import com.euromillones.app.data.GeneratedCombo
import com.euromillones.app.data.HotCold
import com.euromillones.app.data.Mask
import com.euromillones.app.data.MaskPool
import com.euromillones.app.data.Temp
import com.euromillones.app.data.buildPool
import com.euromillones.app.data.currentHotCold
import com.euromillones.app.data.generate
import com.euromillones.app.data.historicTempMasks
import com.euromillones.app.data.pack
import com.euromillones.app.ui.components.BallsWithLetters
import com.euromillones.app.ui.components.DrawCard
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.TableCell
import com.euromillones.app.ui.components.TextCell
import com.euromillones.app.ui.components.TokenCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GW = 34.dp

/** Codifica el filtro frío/caliente: 7 posiciones (5 números + 2 estrellas), '-' = cualquiera. */
private fun initialTemps(t: GenTarget): String {
    val n = t.tempNum?.split(" ")?.joinToString("") ?: "-----"
    val s = t.tempStar ?: "--"
    return (n + s).padEnd(7, '-').take(7)
}

private fun Char.toTemp(): Temp? = when (this) {
    'C' -> Temp.C
    'T' -> Temp.T
    'F' -> Temp.F
    else -> null
}

private fun Char.next(): Char = when (this) {
    '-' -> 'C'
    'C' -> 'T'
    'T' -> 'F'
    else -> '-'
}

@Composable
fun GeneratorScreen(target: GenTarget, draws: List<Draw>, window: Int, modifier: Modifier = Modifier) {
    var temps by rememberSaveable(target) { mutableStateOf(initialTemps(target)) }
    var amount by rememberSaveable { mutableIntStateOf(5) }
    var avoidDrawn by rememberSaveable { mutableStateOf(true) }
    var combos by remember(target) { mutableStateOf<List<GeneratedCombo>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    val hc: HotCold = remember(draws, window) { currentHotCold(draws, window) }
    val drawnPacked = remember(draws) { draws.map { pack(it.numbers) }.toHashSet() }
    val historic = remember(draws, window) { historicTempMasks(draws, window) }

    // Grupo sin filtro frío/caliente (solo decena/unidad) para comparar
    val base by produceState<MaskPool?>(null, target, hc) {
        value = withContext(Dispatchers.Default) {
            buildPool(target.numMask, target.starMask, hc, List(5) { null }, List(2) { null })
        }
    }
    // Grupo con todos los filtros
    val pool by produceState<MaskPool?>(null, target, hc, temps) {
        value = null
        value = withContext(Dispatchers.Default) {
            buildPool(
                target.numMask,
                target.starMask,
                hc,
                temps.take(5).map { it.toTemp() },
                temps.drop(5).map { it.toTemp() },
            )
        }
    }
    val matches = remember(draws, target, temps, historic) {
        draws.filter { d ->
            (target.numMask == null || Mask.numbers(d) == target.numMask) &&
                (target.starMask == null || Mask.stars(d) == target.starMask) &&
                run {
                    if (temps.all { it == '-' }) return@run true
                    val h = historic[d.date] ?: return@run false
                    val tn = h.first.split(" ")
                    (0 until 5).all { i -> temps[i] == '-' || tn.getOrNull(i) == temps[i].toString() } &&
                        (0 until 2).all { i -> temps[5 + i] == '-' || h.second.getOrNull(i) == temps[5 + i] }
                }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Máscara + filtro frío/caliente ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Máscara y filtros", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    val lmh = target.numMask?.split(" ")
                    Row {
                        HeaderCell("", 58.dp)
                        (1..5).forEach { HeaderCell("N$it", GW) }
                        HeaderCell("★1", GW)
                        HeaderCell("★2", GW)
                    }
                    Row {
                        TextCell("Dec/Uni", 58.dp, bold = true)
                        (0 until 5).forEach { i -> TokenCell(lmh?.getOrNull(i) ?: "–", false, GW) }
                        (0 until 2).forEach { i ->
                            TokenCell(target.starMask?.getOrNull(i)?.toString() ?: "–", true, GW)
                        }
                    }
                    Row {
                        TextCell("Frío/Cal.", 58.dp, bold = true)
                        temps.forEachIndexed { i, ch ->
                            TableCell(
                                GW,
                                if (ch == '-') Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                Modifier.clickable { temps = temps.replaceRange(i, i + 1, ch.next().toString()) },
                            ) {
                                Text(
                                    if (ch == '-') "–" else ch.toString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    Text(
                        "Toca una casilla de Frío/Cal. para cambiarla: – (cualquiera) → C → T → F. " +
                            "Se usan los calientes y fríos de los últimos $window sorteos.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { temps = "-------" }) { Text("Quitar filtro frío/caliente") }

                    val b = base
                    val p = pool
                    if (b == null || p == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                            Text("  Calculando combinaciones…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        InfoLine("Solo decena/unidad", "${intStr(b.total)} combinaciones")
                        InfoLine("Con frío/caliente", "${intStr(p.total)} combinaciones")
                        if (b.total > 0 && p.total < b.total) {
                            InfoLine("Reducción", "quedan el ${pctStr(p.total.toDouble() / b.total)}")
                        }
                        InfoLine("Prob. próximo sorteo", "${pctStr(p.probability)}")
                        InfoLine(
                            "Ha salido",
                            "${matches.size} veces" + (matches.firstOrNull()?.let { " · última ${it.date.format(maskDateFmt)}" } ?: ""),
                        )
                    }
                }
            }
        }

        // ---- Calientes y fríos actuales ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Ahora (últimos $window sorteos)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    InfoLine("Calientes", hc.hot.sorted().joinToString(" "))
                    InfoLine("Fríos", hc.cold.sorted().joinToString(" "))
                    InfoLine("Estrellas C / F", "${hc.hotStars.sorted().joinToString(" ")} / ${hc.coldStars.sorted().joinToString(" ")}")
                }
            }
        }

        // ---- Generador ----
        item {
            Text("Generar combinaciones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cuántas:", style = MaterialTheme.typography.bodySmall)
                listOf(1, 5, 10, 20).forEach { n ->
                    FilterChip(selected = amount == n, onClick = { amount = n }, label = { Text("$n") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = avoidDrawn, onCheckedChange = { avoidDrawn = it })
                Text("No repetir 5 números que ya hayan salido", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = pool != null && (pool?.total ?: 0L) > 0,
                    onClick = {
                        pool?.let { combos = generate(it, amount, if (avoidDrawn) drawnPacked else emptySet()) }
                    },
                ) { Text("Generar") }
                OutlinedButton(
                    enabled = combos.isNotEmpty(),
                    onClick = {
                        val txt = combos.joinToString("\n") { c ->
                            c.numbers.joinToString(" ") { "%02d".format(it) } + "  ★ " + c.stars.joinToString(" ")
                        }
                        clipboard.setText(AnnotatedString(txt))
                    },
                ) { Text("Copiar") }
            }
            if (pool?.total == 0L) {
                Text(
                    "Ninguna combinación cumple todos los filtros. Quita alguno.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        itemsIndexed(combos) { i, c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                    BallsWithLetters(
                        c.numbers,
                        c.stars,
                        size = 32.dp,
                        tempOf = { n -> hc.temp(n).name },
                        starTempOf = { s -> hc.tempStar(s).name },
                    )
                }
            }
        }

        // ---- Sorteos donde salió ----
        item {
            Text(
                "Sorteos con esta máscara · ${matches.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(matches.take(100), key = { "m-${it.date}" }) { DrawCard(it) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}
