package com.quinielas.app

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ESTILO QUINIELA DE LOS 80: boleto de papel color crema, tinta roja de imprenta, letra de máquina de escribir
 * y las cruces hechas a bolígrafo azul.
 */
val Papel = Color(0xFFEFE3B4)
val PapelOscuro = Color(0xFFE4D49C)
val Tinta = Color(0xFFE2532F)          // naranja-rojo de imprenta del boleto
val TintaBoleto = Color(0xFFE4572E)
val TintaSuave = Color(0xFFF6C9B4)
val Boli = Color(0xFF1F3A93)           // bolígrafo azul
val Texto = Color(0xFF2B2118)

/** Fuentes del boleto (Google Fonts, licencias OFL/Apache): máquina de escribir, imprenta estrecha y lápiz. */
val Maquina = FontFamily(androidx.compose.ui.text.font.Font(R.font.maquina))
val Imprenta = FontFamily(androidx.compose.ui.text.font.Font(R.font.imprenta))
val Estrecha = FontFamily(
    androidx.compose.ui.text.font.Font(R.font.estrecha),
    androidx.compose.ui.text.font.Font(R.font.estrecha_negrita, FontWeight.Bold),
    androidx.compose.ui.text.font.Font(R.font.estrecha_negrita, FontWeight.SemiBold),
)
val Lapiz = FontFamily(androidx.compose.ui.text.font.Font(R.font.lapiz))

@Composable
fun TemaRetro(content: @Composable () -> Unit) {
    val colores = lightColorScheme(
        primary = Tinta, onPrimary = Papel, primaryContainer = TintaSuave, onPrimaryContainer = Texto,
        secondary = Boli, secondaryContainer = TintaSuave, onSecondaryContainer = Texto,
        background = Papel, onBackground = Texto, surface = Papel, onSurface = Texto,
        surfaceVariant = PapelOscuro, onSurfaceVariant = Texto, outline = Tinta,
        surfaceContainer = PapelOscuro, surfaceContainerHigh = PapelOscuro, surfaceContainerHighest = PapelOscuro,
        surfaceContainerLow = Papel, surfaceContainerLowest = Papel,
    )
    val t = Typography()
    fun TextStyle.f(ff: FontFamily, extra: Float = 0f) = copy(fontFamily = ff, fontSize = fontSize * (1f + extra))
    val tipos = Typography(
        displayLarge = t.displayLarge.f(Imprenta), displayMedium = t.displayMedium.f(Imprenta), displaySmall = t.displaySmall.f(Imprenta),
        headlineLarge = t.headlineLarge.f(Imprenta), headlineMedium = t.headlineMedium.f(Imprenta), headlineSmall = t.headlineSmall.f(Imprenta),
        titleLarge = t.titleLarge.f(Imprenta), titleMedium = t.titleMedium.f(Imprenta), titleSmall = t.titleSmall.f(Imprenta),
        bodyLarge = t.bodyLarge.f(Estrecha, 0.12f), bodyMedium = t.bodyMedium.f(Estrecha, 0.15f), bodySmall = t.bodySmall.f(Estrecha, 0.18f),
        labelLarge = t.labelLarge.f(Maquina), labelMedium = t.labelMedium.f(Maquina), labelSmall = t.labelSmall.f(Maquina),
    )
    MaterialTheme(colorScheme = colores, typography = tipos, content = content)
}

// --- Boleto de 1984 dibujado con Boleto80 (papel, tinta de imprenta y cruces a lápiz) ---

/** Lienzo de Android para dibujar el boleto. */
class LienzoAndroid(private val c: android.graphics.Canvas, private val ctx: android.content.Context) : Lienzo {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val path = android.graphics.Path()

    override fun papel(l: Float, t: Float, r: Float, b: Float, semilla: Int) {
        val bmp = Texturas.papel(ctx, (r - l).toInt(), (b - t).toInt())
        c.drawBitmap(bmp, l, t, null)
    }
    private fun trazo(color: Int, ancho: Float) { p.style = android.graphics.Paint.Style.STROKE; p.color = color; p.strokeWidth = ancho; p.shader = null }
    override fun linea(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, ancho: Float) { trazo(color, ancho); c.drawLine(x1, y1, x2, y2, p) }
    override fun curva(x0: Float, y0: Float, cx: Float, cy: Float, x1: Float, y1: Float, color: Int, ancho: Float) {
        trazo(color, ancho); path.reset(); path.moveTo(x0, y0); path.quadTo(cx, cy, x1, y1); c.drawPath(path, p)
    }
    override fun rect(l: Float, t: Float, r: Float, b: Float, color: Int, relleno: Boolean, ancho: Float) {
        if (relleno) { p.style = android.graphics.Paint.Style.FILL; p.color = color; p.shader = null } else trazo(color, ancho)
        c.drawRect(l, t, r, b, p)
    }
    override fun circulo(cx: Float, cy: Float, r: Float, color: Int) { p.style = android.graphics.Paint.Style.FILL; p.color = color; p.shader = null; c.drawCircle(cx, cy, r, p) }
    override fun mancha(cx: Float, cy: Float, r: Float, color: Int) {
        p.style = android.graphics.Paint.Style.FILL
        p.shader = android.graphics.RadialGradient(cx, cy, r, color, 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
    }
    private fun fuente(f: Lienzo.Fuente) = Texturas.fuente(ctx, f)
    override fun texto(s: String, x: Float, y: Float, f: Lienzo.Fuente, tam: Float, color: Int, alinear: Int, espaciado: Float, giro: Float) {
        p.style = android.graphics.Paint.Style.FILL; p.shader = null; p.color = color
        p.typeface = fuente(f); p.textSize = tam; p.letterSpacing = espaciado / tam
        p.textAlign = when (alinear) { 1 -> android.graphics.Paint.Align.CENTER; 2 -> android.graphics.Paint.Align.RIGHT; else -> android.graphics.Paint.Align.LEFT }
        if (giro != 0f) { c.save(); c.rotate(giro, x, y); c.drawText(s, x, y, p); c.restore() } else c.drawText(s, x, y, p)
        p.letterSpacing = 0f
    }
    override fun medir(s: String, f: Lienzo.Fuente, tam: Float): Float { p.typeface = fuente(f); p.textSize = tam; p.letterSpacing = 0f; return p.measureText(s) }
}

/** Fuentes del boleto y textura del papel (se generan una vez). */
object Texturas {
    private var papelBmp: android.graphics.Bitmap? = null
    private val fuentes = HashMap<Lienzo.Fuente, android.graphics.Typeface>()

    fun fuente(ctx: android.content.Context, f: Lienzo.Fuente): android.graphics.Typeface = fuentes.getOrPut(f) {
        val id = when (f) {
            Lienzo.Fuente.MAQUINA -> R.font.maquina; Lienzo.Fuente.IMPRENTA -> R.font.imprenta
            Lienzo.Fuente.ESTRECHA -> R.font.estrecha; Lienzo.Fuente.ESTRECHA_NEGRITA -> R.font.estrecha_negrita
            Lienzo.Fuente.LAPIZ -> R.font.lapiz
        }
        runCatching { androidx.core.content.res.ResourcesCompat.getFont(ctx, id) }.getOrNull() ?: android.graphics.Typeface.DEFAULT
    }

    /** Papel amarillento con grano, oscurecido en los bordes y con fibras. */
    @Synchronized
    fun papel(ctx: android.content.Context, w: Int, h: Int): android.graphics.Bitmap {
        papelBmp?.let { if (it.width == w && it.height == h) return it }
        val px = IntArray(w * h)
        val rnd = kotlin.random.Random(1984)
        for (y in 0 until h) for (x in 0 until w) {
            val n = (rnd.nextFloat() - 0.5f) * 18f
            val vx = x.toFloat() / w - 0.5f; val vy = y.toFloat() / h - 0.5f
            val dark = (vx * vx + vy * vy) * 30f
            val r = (239 + n - dark).toInt().coerceIn(0, 255); val g = (227 + n - dark).toInt().coerceIn(0, 255)
            val b = (182 + n * 0.8f - dark * 1.3f).toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 1f }
        val path = android.graphics.Path()
        repeat(260) {
            p.color = android.graphics.Color.argb(18 + rnd.nextInt(20), 150, 120, 70)
            val x = rnd.nextInt(w).toFloat(); val y = rnd.nextInt(h).toFloat()
            path.reset(); path.moveTo(x, y); path.quadTo(x + rnd.nextFloat() * 10, y + rnd.nextFloat() * 6, x + rnd.nextFloat() * 18, y + rnd.nextFloat() * 4)
            c.drawPath(path, p)
        }
        papelBmp = bmp
        return bmp
    }
}

/** El boleto completo, como el de la temporada 83/84. */
@Composable
fun Boleto(
    numero: Int, primera: Int, partidos: List<PartidoProx>, columnas: List<Int>, jornada: Int?,
    fecha: String = "", margen: String? = null, real: String? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val datos = remember(numero, columnas, partidos, margen, real) {
        DatosBoleto(numero, primera, partidos.take(14).map { it.local to it.visit }, partidos.getOrNull(14)?.let { it.local to it.visit },
            columnas.map { Generador.texto(it) }, jornada, fecha, margen, real)
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().aspectRatio(Boleto80.ANCHO / Boleto80.ALTO)) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            val k = size.width / Boleto80.ANCHO
            nc.scale(k, k)
            Boleto80.dibujar(LienzoAndroid(nc, ctx), datos)
            nc.restore()
        }
    }
}

/** Casilla de boleto que se tacha a lápiz al tocarla (para elegir los signos de cada partido). */
@Composable
fun CasillaLapiz(signo: Char, marcada: Boolean, semilla: Int, onClick: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 1.dp)) {
        Text(signo.toString(), fontFamily = Imprenta, color = TintaBoleto, fontSize = 16.sp)
        androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
            val k = size.width / 30f
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas; nc.save(); nc.scale(k, k)
                val l = LienzoAndroid(nc, ctx)
                l.linea(3f, 3f, 27f, 3f, TintaBoleto.toArgb(), 1.6f); l.linea(3f, 27f, 27f, 27f, TintaBoleto.toArgb(), 1.6f)
                l.linea(3f, 3f, 3f, 27f, TintaBoleto.toArgb(), 1.6f); l.linea(27f, 3f, 27f, 27f, TintaBoleto.toArgb(), 1.6f)
                if (marcada) Boleto80.cruz(l, 15f, 15f, 26f, kotlin.random.Random(semilla), 0xFF3C3C44.toInt(), pasadas = 3, grosor = 1.8f, intensidad = 1.3f)
                nc.restore()
            }
        }
    }
}
