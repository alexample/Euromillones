package com.euromillones.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.euromillones.app.data.Draw
import com.euromillones.app.ui.theme.EuroBlue
import com.euromillones.app.ui.theme.EuroGold
import com.euromillones.app.ui.theme.HitGreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES"))

fun LocalDate.pretty(): String = format(dateFmt).replaceFirstChar { it.uppercase() }

/** Bola de número (azul) o estrella (dorada). */
@Composable
fun Ball(
    value: Int,
    isStar: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    highlighted: Boolean = false,
    dimmed: Boolean = false,
) {
    val bg = when {
        highlighted -> HitGreen
        isStar -> EuroGold
        else -> EuroBlue
    }
    val fg = if (isStar && !highlighted) Color.Black else Color.White
    Box(
        modifier = modifier
            .size(size)
            .background(if (dimmed) bg.copy(alpha = 0.35f) else bg, CircleShape)
            .then(if (highlighted) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            value.toString(),
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/**
 * Tarjeta de un sorteo. Si se pasan números/estrellas a resaltar,
 * los aciertos salen en verde y el resto atenuado.
 */
@Composable
fun DrawCard(
    draw: Draw,
    modifier: Modifier = Modifier,
    highlightNumbers: Set<Int> = emptySet(),
    highlightStars: Set<Int> = emptySet(),
    large: Boolean = false,
) {
    val highlighting = highlightNumbers.isNotEmpty() || highlightStars.isNotEmpty()
    val ballSize = if (large) 40.dp else 36.dp
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    draw.date.pretty(),
                    style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (highlighting) {
                    val n = draw.numbers.count { it in highlightNumbers }
                    val s = draw.stars.count { it in highlightStars }
                    Text(
                        "$n + $s ★",
                        color = HitGreen,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else if (draw.hasWinner == true) {
                    Text("¡Bote repartido!", color = HitGreen, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                draw.numbers.forEach { n ->
                    val hit = n in highlightNumbers
                    Ball(n, isStar = false, size = ballSize, highlighted = hit, dimmed = highlighting && !hit)
                }
                Spacer(Modifier.width(6.dp))
                draw.stars.forEach { s ->
                    val hit = s in highlightStars
                    Ball(s, isStar = true, size = ballSize, highlighted = hit, dimmed = highlighting && !hit)
                }
            }
        }
    }
}
