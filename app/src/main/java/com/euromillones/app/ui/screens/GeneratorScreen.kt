package com.euromillones.app.ui.screens

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.GenTarget
import com.euromillones.app.data.GeneratedCombo
import com.euromillones.app.data.MaskPool
import com.euromillones.app.data.PatternStat
import com.euromillones.app.data.buildPool
import com.euromillones.app.data.generate
import com.euromillones.app.data.maskOf
import com.euromillones.app.data.pack
import com.euromillones.app.data.statOf
import com.euromillones.app.ui.components.BallsWithLetters
import com.euromillones.app.ui.components.DrawCard
import com.euromillones.app.ui.components.MaskCells
import com.euromillones.app.ui.components.MaskHeaderCells
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class GenData(val pool: MaskPool, val stat: PatternStat, val drawnPacked: Set<Int>, val matches: List<Draw>)

@Composable
fun GeneratorScreen(target: GenTarget, draws: List<Draw>, modifier: Modifier = Modifier) {
    val scope = target.scope
    var amount by rememberSaveable { mutableIntStateOf(5) }
    var avoidDrawn by rememberSaveable { mutableStateOf(true) }
    var combos by remember(target) { mutableStateOf<List<GeneratedCombo>>(emptyList()) }
    val clipboard = LocalClipboardManager.current

    val data by produceState<GenData?>(null, target, draws) {
        value = withContext(Dispatchers.Default) {
            GenData(
                pool = buildPool(target),
                stat = statOf(scope, target.mask, draws),
                drawnPacked = draws.map { pack(it.numbers) }.toHashSet(),
                matches = draws.filter { maskOf(scope, it) == target.mask },
            )
        }
    }

    val d = data
    if (d == null) {
        Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Primera tanda automática al entrar
    LaunchedEffect(d) { if (combos.isEmpty()) combos = generate(d.pool, amount, if (avoidDrawn) d.drawnPacked else emptySet()) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Máscara y sus datos ----
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Máscara", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row { MaskHeaderCells(scope) }
                    Row { MaskCells(target.mask, scope) }
                    Spacer(Modifier.height(8.dp))
                    val s = d.stat
                    InfoLine("Probabilidad próximo sorteo", "${pctStr(s.probability)} (1 de cada ${intStr(s.averageGap)})")
                    InfoLine("Veces que ha salido", "${s.count} (esperadas ${num1(s.expectedCount)})")
                    InfoLine(
                        "Última vez",
                        when (val n = s.drawsSince) {
                            null -> "nunca"
                            0 -> "${s.last?.format(maskDateFmt)} (último sorteo)"
                            else -> "${s.last?.format(maskDateFmt)} · hace $n sorteos"
                        },
                    )
                    InfoLine(
                        "Combinaciones posibles",
                        "${intStr(d.pool.numberCount)} de números × ${d.pool.stars.size} de estrellas = ${intStr(d.pool.total)}",
                    )
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
                Button(onClick = {
                    combos = generate(d.pool, amount, if (avoidDrawn) d.drawnPacked else emptySet())
                }) { Text("Generar") }
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
        }

        itemsIndexed(combos) { i, c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(22.dp))
                    BallsWithLetters(c.numbers, c.stars, size = 34.dp)
                }
            }
        }

        // ---- Sorteos donde salió ----
        item {
            Text(
                "Sorteos con esta máscara · ${d.matches.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(d.matches.take(100), key = { "m-${it.date}" }) { DrawCard(it) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}
