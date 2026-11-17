package com.euromillones.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.euromillones.app.data.Draw
import com.euromillones.app.data.Level
import com.euromillones.app.data.MaskKind
import com.euromillones.app.data.MaskStat
import com.euromillones.app.data.stats
import com.euromillones.app.ui.components.Ball
import com.euromillones.app.ui.components.pretty
import com.euromillones.app.ui.theme.HitGreen
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortFmt = DateTimeFormatter.ofPattern("dd/MM/yy")
private val LevelL = Color(0xFF0EA5E9)
private val LevelM = Color(0xFF8B5CF6)
private val LevelH = Color(0xFFEF4444)

private fun Level.color() = when (this) {
    Level.L -> LevelL
    Level.M -> LevelM
    Level.H -> LevelH
}

private fun pct(x: Double) = String.format(Locale.forLanguageTag("es-ES"), "%.1f%%", x * 100)

@Composable
fun MasksScreen(draws: List<Draw>, modifier: Modifier = Modifier) {
    var kind by rememberSaveable { mutableStateOf(MaskKind.DECENAS) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val stats = remember(draws, kind) { kind.stats(draws) }
    val selectedDraws = remember(draws, kind, selected) {
        if (selected == null) emptyList() else draws.filter { kind.maskOf(it).label == selected }
    }

    if (draws.isEmpty()) {
        Text("Sin datos todavía. Pulsa ⟳ para actualizar.", modifier.padding(24.dp))
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Último sorteo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        item { MaskedDrawCard(draws.first()) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaskKind.entries.forEach { k ->
                    FilterChip(
                        selected = k == kind,
                        onClick = { kind = k; selected = null },
                        label = { Text(k.title) },
                    )
                }
            }
        }
        item {
            Text(
                "${kind.rule}\nEsperado = probabilidad teórica exacta. Pulsa una máscara para ver sus sorteos.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item { StatHeader() }
        items(stats, key = { "${kind.name}-${it.key.label}" }) { s ->
            StatRow(
                s = s,
                selected = s.key.label == selected,
                onClick = { selected = if (selected == s.key.label) null else s.key.label },
            )
        }

        selected?.let { label ->
            item {
                Text(
                    "Sorteos con $label en ${kind.title.lowercase()} · ${selectedDraws.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = HitGreen,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(selectedDraws, key = { "sel-${it.date}" }) { d -> MaskedDrawCard(d) }
        }
    }
}

@Composable
private fun StatHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        HeaderCell("Máscara", 1.3f, TextAlign.Start)
        HeaderCell("Veces", 1f)
        HeaderCell("Real", 1f)
        HeaderCell("Esperado", 1.1f)
        HeaderCell("Última", 1.1f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(
    text: String,
    w: Float,
    align: TextAlign = TextAlign.End,
) {
    Text(
        text,
        modifier = Modifier.weight(w),
        textAlign = align,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun StatRow(s: MaskStat, selected: Boolean, onClick: () -> Unit) {
    val diff = s.observed - s.expected
    val diffColor = when {
        s.expected == 0.0 -> MaterialTheme.colorScheme.onSurface
        diff / s.expected > 0.10 -> HitGreen
        diff / s.expected < -0.10 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1.3f)) {
            LevelText("${s.key.l}L", Level.L)
            Spacer(Modifier.width(3.dp))
            LevelText("${s.key.m}M", Level.M)
            Spacer(Modifier.width(3.dp))
            LevelText("${s.key.h}H", Level.H)
        }
        Cell(s.count.toString(), 1f, bold = true)
        Cell(pct(s.observed), 1f, color = diffColor)
        Cell(pct(s.expected), 1.1f)
        Cell(s.last?.format(shortFmt) ?: "nunca", 1.1f)
    }
}

@Composable
private fun LevelText(text: String, level: Level) {
    Text(text, color = level.color(), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    w: Float,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
) {
    Text(
        text,
        modifier = Modifier.weight(w),
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = color,
    )
}

/** Sorteo con la letra de decena y unidad debajo de cada número (y de nivel en estrellas). */
@Composable
fun MaskedDrawCard(draw: Draw) {
    val dec = MaskKind.DECENAS.maskOf(draw)
    val uni = MaskKind.UNIDADES.maskOf(draw)
    val est = MaskKind.ESTRELLAS.maskOf(draw)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(draw.date.pretty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                draw.numbers.forEach { n ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ball(n, isStar = false, size = 36.dp)
                        Row {
                            LevelText(MaskKind.DECENAS.level(n).name, MaskKind.DECENAS.level(n))
                            LevelText(MaskKind.UNIDADES.level(n).name, MaskKind.UNIDADES.level(n))
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                draw.stars.forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ball(s, isStar = true, size = 36.dp)
                        LevelText(MaskKind.ESTRELLAS.level(s).name, MaskKind.ESTRELLAS.level(s))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Dec: ${dec.label} · Uni: ${uni.label} · Est: ${est.label}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
