package io.github.itsjanghoon.shortstop.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.itsjanghoon.shortstop.engine.Result
import io.github.itsjanghoon.shortstop.engine.Where
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin

/** Timing of "the play", in seconds, the same numbers as plan() in web/src/ui.ts. */
class Play(r: Result) {
    val tokenStart = 0.85f
    val tokenStep = 0.3f
    val maskDone = tokenStart + r.mask.size * tokenStep
    val cloud = r.receipt.any { it.sent != null }

    /** No model on device: the first look went to the cloud. */
    val skippedShortstop = r.firstLook?.where == Where.IN_CLOUD
    val throwAt = max(maskDone, 1.2f) + 0.2f
    val verdictAt = if (cloud) throwAt + 1.3f else throwAt
    val end = verdictAt + 1.8f + r.signals.size * 0.1f

    fun tokenAt(i: Int) = tokenStart + i * tokenStep
}

/** 0..1 progress of a step that starts at [start] and lasts [dur] seconds. */
fun prog(t: Float, start: Float, dur: Float) = ((t - start) / dur).coerceIn(0f, 1f)

fun easeOut(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)

fun easeInOut(x: Float) = if (x < .5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2

/** Overshoots a little and settles, like the CSS cubic-bezier(.3,1.6,.5,1) slam. */
fun backOut(x: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1
    val y = x - 1
    return 1 + c3 * y * y * y + c1 * y * y
}

/** Shake offset in dp for the SCAM word: a few decaying swings. */
fun shake(t: Float, at: Float): Float {
    val x = prog(t, at, .5f)
    return if (x <= 0f || x >= 1f) 0f else (sin(x * PI * 4).toFloat() * 8f * (1 - x))
}

/** True when the user turned animations off (Settings > Accessibility > Remove animations). */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** Seconds since this result appeared. With animations off it jumps straight to the end. */
@Composable
fun rememberPlayClock(r: Result?): State<Float> {
    val reduced = reducedMotion()
    val play = remember(r) { r?.let(::Play) }
    val clock = remember(r) { Animatable(if (play == null || reduced) play?.end ?: 0f else 0f) }
    LaunchedEffect(r, reduced) {
        if (play != null && !reduced) clock.animateTo(play.end, tween((play.end * 1000).toInt(), easing = LinearEasing))
    }
    return clock.asState()
}

/** Fade and rise in, like the web `up` keyframe. */
fun Modifier.appear(t: Float, at: Float): Modifier = graphicsLayer {
    val p = easeOut(prog(t, at, .4f))
    alpha = p
    translationY = (1 - p) * 12.dp.toPx()
}

/** Reveal of a placeholder chip: how many letters of the label show at time [t]. */
fun wipeChars(label: String, t: Float, at: Float): Int = ceil(prog(t, at, .45f) * label.length).toInt()

// The field, in the 760 x 470 units of the web SVG.
private val HOME = Offset(380f, 440f)
private val SS = Offset(262f, 196f)
private val FIRST = Offset(618f, 236f)

private fun quad(a: Offset, c: Offset, b: Offset, s: Float): Offset {
    val u = 1 - s
    return Offset(u * u * a.x + 2 * u * s * c.x + s * s * b.x, u * u * a.y + 2 * u * s * c.y + s * s * b.y)
}

/**
 * The baseball diamond from web/src/ui.ts fieldHtml: the ball goes home -> shortstop (your device);
 * if the cloud was used it is thrown across the dashed "masked only" wall to first base.
 * [t] is null for the idle field on the compose screen.
 */
@Composable
fun Diamond(r: Result?, t: Float?, modifier: Modifier = Modifier) {
    val play = remember(r) { r?.let(::Play) }
    val measurer = rememberTextMeasurer()
    val reduced = reducedMotion()
    val dash by rememberInfiniteTransition(label = "wall").animateFloat(
        0f, if (reduced) 0f else -28f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "dash",
    )
    val desc = when {
        play == null -> "Field: your device on the left, the cloud on the right"
        play.cloud -> "The ball went to the shortstop on your device, then masked text was thrown to the cloud"
        else -> "The ball went to the shortstop on your device and stayed there"
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(760f / 470f)
            .semantics { contentDescription = desc }
            .testTag("diamond"),
    ) {
        val k = size.width / 760f
        fun p(o: Offset) = Offset(o.x * k, o.y * k)
        val white = Color.White

        // Infield and foul lines.
        val infield = Path().apply {
            moveTo(380f * k, 440f * k); lineTo(618f * k, 236f * k); lineTo(380f * k, 32f * k); lineTo(142f * k, 236f * k); close()
        }
        drawPath(infield, Ball.dirt.copy(alpha = .10f))
        drawPath(infield, white.copy(alpha = .3f), style = Stroke(3f * k))
        drawLine(white.copy(alpha = .12f), p(HOME), p(Offset(740f, 130f)), 3f * k)
        drawLine(white.copy(alpha = .12f), p(HOME), p(Offset(20f, 130f)), 3f * k)

        // The wall between your device and the cloud.
        drawLine(
            Ball.cloud, p(Offset(470f, 40f)), p(Offset(470f, 430f)), 3f * k,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f * k * 2, 8f * k * 2), dash * k),
        )
        label(measurer, "YOUR DEVICE", Offset(24f, 40f), k, Ball.chalk.copy(alpha = .8f), size = 28f)
        label(measurer, "CLOUD", Offset(740f, 40f), k, Ball.cloud, alignEnd = true, size = 28f)
        label(measurer, "MASKED ONLY", Offset(482f, 396f), k, Ball.cloud, size = 26f)
        label(measurer, "Message in", Offset(368f, 396f), k, Ball.chalk.copy(alpha = .8f), alignEnd = true, size = 26f, spacing = 0f)

        // Home plate.
        rotate(45f, p(HOME)) { drawRect(Ball.chalk, p(HOME) - Offset(9f * k, 9f * k), Size(18f * k, 18f * k)) }

        val ssDim = play?.skippedShortstop == true
        val cloudDim = play != null && !play.cloud
        val tt = t ?: 0f

        // Shortstop: the small model on your device.
        if (play != null) glow(p(SS), prog(tt, 1.1f, .8f), Ball.chalk, k)
        drawCircle(Ball.chalk.copy(alpha = if (ssDim) .35f else 1f), 34f * k, p(SS))
        label(measurer, "SS", SS, k, Ball.ink.copy(alpha = if (ssDim) .35f else 1f), center = true, middle = true, size = 30f, weight = FontWeight.Black, spacing = 0f)

        // First base: the big model in the cloud.
        if (play != null) glow(p(FIRST), prog(tt, play.verdictAt - .1f, .8f), Ball.cloud, k)
        drawCircle(Ball.cloud.copy(alpha = if (cloudDim) .35f else 1f), 30f * k, p(FIRST))
        label(measurer, "1B", FIRST, k, Ball.cell.copy(alpha = if (cloudDim) .35f else 1f), center = true, middle = true, size = 28f, weight = FontWeight.Black, spacing = 0f)

        if (play == null) return@Canvas
        // Ball 1: home -> shortstop (or straight to first base when there is no on-device model).
        val to = if (play.skippedShortstop) FIRST else SS
        val via = if (play.skippedShortstop) Offset(520f, 360f) else Offset(300f, 300f)
        ball(p(quad(HOME, via, to, easeInOut(prog(tt, .2f, 1f)))), 1 - prog(tt, 1.25f, .3f), tt >= .2f, k)
        // Ball 2: the throw across the wall, masked text only.
        if (play.cloud && !play.skippedShortstop) {
            ball(p(quad(SS, Offset(450f, 110f), FIRST, easeInOut(prog(tt, play.throwAt, 1.1f)))), 1 - prog(tt, play.throwAt + 1.15f, .3f), tt >= play.throwAt, k)
        }
    }
}

private fun DrawScope.glow(c: Offset, g: Float, color: Color, k: Float) {
    if (g <= 0f || g >= 1f) return
    drawCircle(color.copy(alpha = .9f * (1 - g)), 70f * k * g, c)
}

private fun DrawScope.ball(at: Offset, alpha: Float, show: Boolean, k: Float) {
    if (!show || alpha <= 0f) return
    drawCircle(Color.White.copy(alpha = alpha), 10f * k, at)
    drawCircle(Ball.scam.copy(alpha = alpha), 10f * k, at, style = Stroke(3f * k))
}

private fun DrawScope.label(
    m: TextMeasurer,
    text: String,
    at: Offset,
    k: Float,
    color: Color,
    alignEnd: Boolean = false,
    center: Boolean = false,
    middle: Boolean = false,
    size: Float = 20f,
    weight: FontWeight = FontWeight.ExtraBold,
    spacing: Float = 2f,
) {
    val layout = m.measure(
        text,
        TextStyle(color = color, fontSize = (size * k).toSp(), fontWeight = weight, letterSpacing = (spacing * k).toSp()),
    )
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val x = at.x * k - when {
        alignEnd -> w
        center -> w / 2
        else -> 0f
    }
    val y = at.y * k - if (middle) h / 2 else 0f
    drawText(layout, topLeft = Offset(x, y))
}

/** Captions under the diamond: what each base did. */
@Composable
fun DiamondLegend(r: Result?, t: Float?) {
    val play = remember(r) { r?.let(::Play) }
    val fl = r?.firstLook
    val device = r?.receipt?.firstOrNull { it.where == Where.ON_DEVICE && it.what.startsWith("First look") }
    val cloudMs = r?.receipt?.filter { it.where == Where.IN_CLOUD }?.sumOf { it.ms } ?: 0
    val n = r?.mask?.size ?: 0
    val secrets = "hid $n secret${if (n == 1) "" else "s"}"
    val (ssTitle, ssSub) = when {
        r == null -> "Small model on your device" to "catches it first, hides your secrets"
        fl == null -> "Rules on this device" to "$secrets · checked links"
        play!!.skippedShortstop -> "No on-device model" to "rules hid the secrets instead"
        else -> "Gemini Nano · ${fmtMs(device?.ms ?: 0)}" to "$secrets · ${fl.verdict.wire} (${String.format(Locale.US, "%.2f", fl.confidence)})"
    }
    val (cTitle, cSub) = when {
        r == null -> "Big model in the cloud" to "only when unsure, only masked text"
        play!!.cloud -> "Gemini in the cloud · ${fmtMs(cloudMs)}" to "${r.verdict.wire} (${String.format(Locale.US, "%.2f", r.confidence)}) · saw placeholders only"
        else -> "Cloud not needed" to "stayed on the bench"
    }
    val tt = t ?: 99f
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Caption("SS", ssTitle, ssSub, Ball.chalk, play?.skippedShortstop == true, Modifier.weight(1f).appear(tt, 1.1f))
        Caption("1B", cTitle, cSub, Ball.cloud, play != null && !play.cloud, Modifier.weight(1f).appear(tt, play?.verdictAt?.minus(.1f) ?: 0f))
    }
}

@Composable
private fun Caption(base: String, title: String, sub: String, color: Color, dim: Boolean, modifier: Modifier) {
    Column(modifier.graphicsLayer { if (dim) alpha *= .5f }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(base, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Text(title, color = Ball.chalk, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = Ball.chalk.copy(alpha = .75f), fontSize = 12.sp, lineHeight = 16.sp)
    }
}
