package com.euromillones.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.euromillones.app.data.Gordo
import com.euromillones.app.data.NAVIDAD_TOTAL
import com.euromillones.app.data.Navidad
import com.euromillones.app.data.currentDigitHotCold
import com.euromillones.app.data.historicDigitHotCold
import com.euromillones.app.data.navidadAllowed
import com.euromillones.app.data.navidadCount
import com.euromillones.app.data.navidadGenerate
import com.euromillones.app.data.SolarEntropy
import com.euromillones.app.ui.components.EntropyInfo
import com.euromillones.app.ui.components.HeaderCell
import com.euromillones.app.ui.components.TableCell
import com.euromillones.app.ui.components.TextCell

private val NW = 44.dp

private fun cycle(c: Char, order: String): Char {
    val seq = "-$order"
    return seq[(seq.indexOf(c).coerceAtLeast(0) + 1) % seq.length]
}

@Composable
fun NavidadGeneratorScreen(initLmh: String, initCtf: String, gordos: List<Gordo>, window: Int, modifier: Modifier = Modifier) {
    var lmh by rememberSaveable(initLmh, initCtf) { mutableStateOf(initLmh.padEnd(5, '-').take(5)) }
    var ctf by rememberSaveable(initLmh, initCtf) { mutableStateOf(initCtf.padEnd(5, '-').take(5)) }
    var amount by rememberSaveable { mutableIntStateOf(5) }
    var numbers by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    val hc = remember(gordos, window) { currentDigitHotCold(gordos, window) }
    val hist = remember(gordos, window) { historicDigitHotCold(gordos, window) }
    val allowed = remember(lmh, ctf, hc) { navidadAllowed(lmh, ctf, hc) }
    val count = remember(allowed) { navidadCount(allowed) }
    LaunchedEffect(allowed, amount) { numbers = navidadGenerate(allowed, amount, SolarEntropy.random()) }

    val matches = remember(gordos, hist, lmh, ctf) {
        gordos.filterIndexed { i, g ->
            val l = Navidad.lmh(g.number)
            val okL = (0 until 5).all { p -> lmh[p] == '-' || l[p] == lmh[p] }
            val okT = ctf.all { it == '-' } || hist[i]?.ctf(g.number)?.let { t ->
                (0 until 5).all { p -> ctf[p] == '-' || t[p] == ctf[p] }
            } == true
            okL && okT
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Máscara y filtros", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        HeaderCell("", 76.dp)
                        (1..5).forEach { HeaderCell("D$it", NW) }
                    }
                    Row {
                        TextCell("Dígito", 76.dp, bold = true)
                        (0 until 5).forEach { p ->
                            EditCell(lmh[p]) { lmh = lmh.replaceRange(p, p + 1, cycle(lmh[p], "LMH").toString()) }
                        }
                    }
                    Row {
                        TextCell("Frío/Cal.", 76.dp, bold = true)
                        (0 until 5).forEach { p ->
                            EditCell(ctf[p]) { ctf = ctf.replaceRange(p, p + 1, cycle(ctf[p], "CTF").toString()) }
                        }
                    }
                    Row {
                        TextCell("Dígitos ok", 76.dp, bold = true)
                        (0 until 5).forEach { p -> TextCell("${allowed[p].count { it }}", NW) }
                    }
                    Text(
                        "Toca una casilla para cambiarla: – (cualquiera) → L → M → H, y – → C → T → F.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = { lmh = "-----"; ctf = "-----" }) { Text("Quitar todos los filtros") }
                    InfoRow("Números posibles", "${intStr(count)} de ${intStr(NAVIDAD_TOTAL)}")
                    InfoRow(
                        "Prob. de que el Gordo caiga aquí",
                        if (count == 0L) "0" else "${pctStr(count.toDouble() / NAVIDAD_TOTAL)} (1 de cada ${intStr(NAVIDAD_TOTAL.toDouble() / count)})",
                    )
                    InfoRow("Gordos con esta máscara", "${matches.size}" + (matches.firstOrNull()?.let { " · último ${it.number} (${it.year})" } ?: ""))
                }
            }
        }

        item {
            Text("Calientes y fríos ahora (últimos $window Gordos)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            HotColdTable(hc)
        }

        item {
            Text("Generar números", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            EntropyInfo()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cuántos:", style = MaterialTheme.typography.bodySmall)
                listOf(1, 5, 10, 20).forEach { n ->
                    FilterChip(selected = amount == n, onClick = { amount = n }, label = { Text("$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = count > 0, onClick = { numbers = navidadGenerate(allowed, amount, SolarEntropy.random()) }) { Text("Generar") }
                OutlinedButton(
                    enabled = numbers.isNotEmpty(),
                    onClick = { clipboard.setText(AnnotatedString(numbers.joinToString("\n"))) },
                ) { Text("Copiar") }
            }
            if (count == 0L) {
                Text("Ningún número cumple todos los filtros. Quita alguno.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Busca estos números en tu administración o en la web de Loterías para comprar el décimo.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        itemsIndexed(numbers, key = { _, n -> "gen-$n" }) { i, n ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(26.dp))
                    BigNumber(n)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Navidad.lmh(n) + "  " + hc.ctf(n),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Text("Gordos con esta máscara · ${matches.size}", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        }
        items(matches.take(100), key = { "m-${it.year}-${it.number}" }) { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${g.year}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
                BigNumber(g.number)
            }
        }
    }
}

@Composable
private fun EditCell(c: Char, onClick: () -> Unit) {
    val active = c != '-'
    androidx.compose.foundation.layout.Box(Modifier.clickable(onClick = onClick)) {
        TableCell(NW, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent) {
            Text(if (active) c.toString() else "–", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}
