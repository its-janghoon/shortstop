package io.github.itsjanghoon.shortstop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.itsjanghoon.shortstop.engine.Verdict

/** Ballpark colors, same as web/src/style.css. */
object Ball {
    val grass = Color(0xFF0F3D2A)
    val grassLight = Color(0xFF1D7A52)
    val grassDark = Color(0xFF07261A)
    val chalk = Color(0xFFF4F1E6)
    val paper = Color(0xFFE6E1D2)
    val dirt = Color(0xFFC98A52)
    val ink = Color(0xFF0B1A12)
    val muted = Color(0xFF6B6656)
    val line = Color(0xFFD9D3C2)
    val scam = Color(0xFFFF4D3D)
    val warn = Color(0xFFFFB020)
    val warnInk = Color(0xFFA86B00)
    val safe = Color(0xFF22B866)
    val cloud = Color(0xFF6CB6FF)
    val gold = Color(0xFFFFD84D)
    val ok = Color(0xFF43E08A)
    val off = Color(0xFFB7C2BB)
    val cell = Color(0xFF06150E)
    val cellLine = Color(0xFF1F4434)
}

fun verdictColor(v: Verdict) = when (v) {
    Verdict.SCAM -> Ball.scam
    Verdict.SUSPICIOUS -> Ball.warn
    Verdict.SAFE -> Ball.safe
}

fun verdictInk(v: Verdict) = when (v) {
    Verdict.SCAM -> Ball.scam
    Verdict.SUSPICIOUS -> Ball.warnInk
    Verdict.SAFE -> Ball.safe
}

/** Line icons from web/src/ui.ts, drawn on a 24x24 grid. */
enum class Glyph(val paths: List<String>, val circles: List<Triple<Float, Float, Float>> = emptyList(), val rects: List<FloatArray> = emptyList()) {
    LINK(listOf("M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7")),
    CLOCK(listOf("M12 7v5l3 2"), circles = listOf(Triple(12f, 12f, 9f))),
    LOCK(listOf("M8 11V8a4 4 0 0 1 8 0v3"), rects = listOf(floatArrayOf(5f, 11f, 14f, 10f, 2f))),
    HOME(listOf("M3 11l9-7 9 7", "M5 10v10h14V10")),
    CLOUD(listOf("M7 18h10a4 4 0 0 0 .5-8A6 6 0 0 0 6 9.5 4.3 4.3 0 0 0 7 18z")),
    ALERT(listOf("M12 3l10 18H2z", "M12 10v5M12 18v.5")),
    MONEY(emptyList(), circles = listOf(Triple(12f, 12f, 2.5f)), rects = listOf(floatArrayOf(3f, 6f, 18f, 12f, 2f))),
    CHECK(listOf("M5 12l4 4 10-10")),
    IMAGE(listOf("M21 17l-5-5-9 8"), circles = listOf(Triple(9f, 10f, 2f)), rects = listOf(floatArrayOf(3f, 4f, 18f, 16f, 2f))),
    BACK(listOf("M15 6l-6 6 6 6")),
    SEND(listOf("M4 12l16-8-6 16-3-7z", "M11 13l9-9")),
    PHONE(listOf("M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z")),
}

private val parsed = HashMap<String, androidx.compose.ui.graphics.Path>()

@Composable
fun Icon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        val stroke = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(k, pivot = Offset.Zero) {
            for (d in glyph.paths) drawPath(parsed.getOrPut(d) { PathParser().parsePathString(d).toPath() }, color, style = stroke)
            for ((x, y, r) in glyph.circles) drawCircle(color, r, Offset(x, y), style = stroke)
            for (r in glyph.rects) drawRoundRect(color, Offset(r[0], r[1]), Size(r[2], r[3]), CornerRadius(r[4]), style = stroke)
        }
    }
}

fun chipGlyph(reason: String): Glyph {
    val r = reason
    return when {
        Regex("link|site|look|alphabet|address|http|dash|ends in", RegexOption.IGNORE_CASE).containsMatchIn(r) -> Glyph.LINK
        Regex("hurry|rush|urgent|hours", RegexOption.IGNORE_CASE).containsMatchIn(r) -> Glyph.CLOCK
        Regex("lock|password|login|code|private", RegexOption.IGNORE_CASE).containsMatchIn(r) || r.contains("ID") -> Glyph.LOCK
        Regex("money|gift|fee|pay|profit|won", RegexOption.IGNORE_CASE).containsMatchIn(r) -> Glyph.MONEY
        else -> Glyph.ALERT
    }
}
