package io.github.itsjanghoon.shortstop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.data.Check
import io.github.itsjanghoon.shortstop.engine.Result
import io.github.itsjanghoon.shortstop.engine.Verdict
import io.github.itsjanghoon.shortstop.engine.Where
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class Word(val big: String, val stamp: String)

private fun word(v: Verdict) = when (v) {
    Verdict.SCAM -> Word("SCAM.", "CAUGHT")
    Verdict.SUSPICIOUS -> Word("CAREFUL.", "CHECK IT")
    Verdict.SAFE -> Word("SAFE.", "CLEAR")
}

/** Label of the one action button on the verdict card. */
fun actionLabel(v: Verdict) = if (v == Verdict.SAFE) "Done" else "Call the real number"

private val TOKEN_SPLIT = Regex("""\[[A-Z]+_\d+]""")

fun fmtMs(ms: Long) = when {
    ms >= 1000 -> String.format(Locale.US, "%.2fs", ms / 1000.0)
    ms < 1 -> "<1 ms"
    else -> "$ms ms"
}

@Composable
fun MainScreen(state: UiState, actions: UiActions) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ball.grassLight, Ball.grass, Ball.grassDark)))
            .drawBehind {
                // Mowing stripes.
                val w = 60.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = .035f), Offset(x, size.height), Offset(x + size.height * .47f, 0f), strokeWidth = w)
                    x += w * 2
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, actions)
            if (!state.online) OfflineBanner(state.device)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(34.dp))
                    .background(Ball.chalk)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                state.error?.let { ErrorBox(it) }
                when (state.screen) {
                    Screen.COMPOSE -> ComposeCard(state, actions)
                    Screen.CHECKING -> CheckingCard(state)
                    Screen.RESULT -> state.result?.let { ResultCard(it, state, actions) }
                    Screen.HISTORY -> HistoryCard(state.history, actions)
                }
            }
            if (state.screen == Screen.RESULT) state.result?.let { Receipt(it) }
        }
    }
}

@Composable
private fun Header(state: UiState, actions: UiActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Ball.chalk), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(16.dp).rotate(45f).clip(RoundedCornerShape(3.dp)).background(Ball.dirt)
                        .border(2.dp, Ball.ink, RoundedCornerShape(3.dp)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Shortstop", color = Ball.chalk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                Text("Small model first. Your secrets stay home.", color = Ball.chalk.copy(alpha = .7f), fontSize = 13.sp)
            }
            if (state.screen != Screen.HISTORY) {
                Pill("History", Ball.chalk, Color.White.copy(alpha = .08f), Modifier.clickable { actions.openHistory() }.testTag("history"))
            }
        }
        StatusChip(state.device)
    }
}

@Composable
fun StatusChip(device: DeviceStatus?) {
    val (text, tone) = when (device) {
        null -> "Starting…" to Ball.off
        DeviceStatus.AVAILABLE -> device.label to Ball.ok
        DeviceStatus.DOWNLOADING, DeviceStatus.UNAVAILABLE -> device.label to Ball.gold
        DeviceStatus.RULES -> device.label to Ball.off
    }
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, .3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = .13f))
            .border(1.dp, tone, CircleShape)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag("status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(tone))
        Text(text, color = tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OfflineBanner(device: DeviceStatus?) {
    val text = if (device == DeviceStatus.AVAILABLE) {
        "You are offline. Gemini Nano on this device still checks your messages."
    } else {
        "You are offline. Shortstop checks with its rules on this device."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Ball.gold)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("offline"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Glyph.CLOUD, Ball.ink)
        Text(text, color = Ball.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ErrorBox(text: String) {
    Text(
        text,
        color = Color(0xFF9B1C12),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFE4E0)).padding(10.dp),
    )
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, glyph: Glyph? = null, border: Color = fg.copy(alpha = .4f)) {
    Row(
        modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        glyph?.let { Icon(it, fg) }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Ghost(text: String, glyph: Glyph?, modifier: Modifier = Modifier, tint: Color = Ball.ink, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.White)
            .border(1.5.dp, Ball.line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        glyph?.let { Icon(it, tint) }
        Text(text, color = Ball.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BigButton(text: String, glyph: Glyph?, modifier: Modifier = Modifier, bg: Color = Ball.ink, fg: Color = Ball.chalk, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(17.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let { Icon(it, fg, size = 18.dp) }
        Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SmallHead(text: String) {
    Text(text.uppercase(Locale.US), color = Ball.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeCard(state: UiState, actions: UiActions) {
    Text("Check a message", color = Ball.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    BasicTextField(
        value = state.draft,
        onValueChange = actions::draft,
        textStyle = TextStyle(color = Ball.ink, fontSize = 17.sp, lineHeight = 25.sp),
        cursorBrush = SolidColor(Ball.dirt),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 170.dp)
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(Ball.paper)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "Message to check" }
            .testTag("input"),
        decorationBox = { inner ->
            if (state.draft.isEmpty()) Text("Paste a text, e-mail or link.", color = Ball.muted, fontSize = 17.sp)
            inner()
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ghost(if (state.image != null) "Screenshot added" else "Add screenshot", Glyph.IMAGE) { actions.pickImage() }
        if (state.image != null) Ghost("Remove", null) { actions.clearImage() }
    }
    SmallHead("Or try one")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        for (e in state.examples) {
            val (glyph, tint) = when (e.kind) {
                "safe" -> Glyph.CHECK to Ball.safe
                "mask" -> Glyph.LOCK to Ball.ink
                else -> Glyph.ALERT to Ball.scam
            }
            Ghost(e.label, glyph, Modifier.testTag("example-${e.id}"), tint) { actions.example(e) }
        }
    }
    BigButton("Check it", null, Modifier.testTag("check")) { actions.check() }
}

@Composable
private fun CheckingCard(state: UiState) {
    Text("Checking…", color = Ball.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (state.draft.isNotBlank()) {
        Text(
            state.draft.take(400),
            color = Ball.ink.copy(alpha = .7f),
            fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(Ball.paper).padding(14.dp),
        )
    }
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Ball.dirt, trackColor = Ball.paper, strokeWidth = 5.dp, modifier = Modifier.size(54.dp))
    }
}

/** The message as Shortstop keeps it: secrets shown as their placeholders, bad links in red. */
fun maskedMessage(r: Result, tokenBg: Color = Ball.ink, tokenFg: Color = Ball.chalk): AnnotatedString = buildAnnotatedString {
    val bad = r.links.filter { it.reasons.isNotEmpty() }.map { it.url }
    val known = r.mask.map { it.token }.toSet()
    var i = 0
    fun plain(s: String) {
        var rest = s
        while (rest.isNotEmpty()) {
            val hit = bad.mapNotNull { u -> rest.indexOf(u).takeIf { it >= 0 }?.let { it to u } }.minByOrNull { it.first }
            if (hit == null) {
                append(rest)
                return
            }
            append(rest.substring(0, hit.first))
            withStyle(SpanStyle(color = Ball.scam, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)) { append(hit.second) }
            rest = rest.substring(hit.first + hit.second.length)
        }
    }
    for (m in TOKEN_SPLIT.findAll(r.masked)) {
        plain(r.masked.substring(i, m.range.first))
        if (m.value in known) {
            withStyle(SpanStyle(background = tokenBg, color = tokenFg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)) {
                append("\u00a0${m.value.drop(1).dropLast(1)}\u00a0")
            }
        } else {
            append(m.value)
        }
        i = m.range.last + 1
    }
    plain(r.masked.substring(i))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(r: Result, state: UiState, actions: UiActions) {
    Text(state.from, color = Ball.muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    if (r.masked.isNotBlank()) {
        Text(
            maskedMessage(r),
            color = Ball.ink,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(Ball.paper).padding(14.dp)
                .testTag("message"),
        )
    }
    VerdictCard(r)
    if (r.signals.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            for (s in r.signals) Ghost(s, chipGlyph(s), tint = verdictInk(r.verdict)) {}
        }
    }
    if (r.mask.isNotEmpty()) Kept(r)
    BigButton(
        actionLabel(r.verdict),
        if (r.verdict == Verdict.SAFE) Glyph.CHECK else Glyph.PHONE,
        Modifier.testTag("act"),
        bg = verdictColor(r.verdict),
        fg = if (r.verdict == Verdict.SUSPICIOUS) Ball.ink else Color.White,
    ) { actions.act() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ghost("Send to family", Glyph.SEND, modifier = Modifier.weight(1f).testTag("family")) { actions.sendToFamily() }
        Ghost("Check another", Glyph.BACK, modifier = Modifier.weight(1f).testTag("again")) { actions.again() }
    }
    if (state.canReport) {
        Ghost(if (state.reported) "Reported. Thank you" else "Report this site", if (state.reported) Glyph.CHECK else Glyph.ALERT, Modifier.fillMaxWidth(), Ball.scam) {
            if (!state.reported) actions.report()
        }
    }
}

@Composable
private fun VerdictCard(r: Result) {
    val w = word(r.verdict)
    val bg = verdictColor(r.verdict)
    val fg = if (r.verdict == Verdict.SUSPICIOUS) Ball.ink else Color.White
    val slam = remember(r) { Animatable(0f) }
    val stamp = remember(r) { Animatable(0f) }
    val shake = remember(r) { Animatable(0f) }
    LaunchedEffect(r) {
        slam.animateTo(1f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessMediumLow))
        stamp.animateTo(1f, spring(dampingRatio = .4f, stiffness = Spring.StiffnessMedium))
        if (r.verdict == Verdict.SCAM) {
            for (x in listOf(-8f, 7f, -4f, 2f, 0f)) shake.animateTo(x, tween(60, easing = FastOutSlowInEasing))
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = slam.value
                    if (r.verdict == Verdict.SAFE) {
                        translationY = (1 - p) * 20.dp.toPx()
                    } else {
                        scaleX = 1.5f - .5f * p
                        scaleY = 1.5f - .5f * p
                        rotationZ = -5f * (1 - p)
                    }
                    alpha = p.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(26.dp))
                .background(bg)
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("verdict"),
        ) {
            Text("SHORTSTOP SAYS", color = fg.copy(alpha = .85f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.9.sp)
            Text(
                w.big,
                color = fg,
                fontSize = 60.sp,
                lineHeight = 58.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-3).sp,
                modifier = Modifier.graphicsLayer { translationX = shake.value.dp.toPx() },
            )
            Spacer(Modifier.size(4.dp))
            Text(r.action, color = fg, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            w.stamp,
            color = verdictInk(r.verdict),
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    val p = stamp.value
                    translationY = (-14).dp.toPx()
                    translationX = 6.dp.toPx()
                    rotationZ = 9f + 21f * (1 - p)
                    scaleX = 2.4f - 1.4f * p
                    scaleY = 2.4f - 1.4f * p
                    alpha = p.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(10.dp))
                .background(Ball.chalk)
                .border(3.dp, verdictInk(r.verdict), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun Kept(r: Result) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    Color(0xFFCFC8B4),
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(18.dp.toPx()),
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("kept"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Glyph.HOME, Ball.muted, size = 14.dp)
            SmallHead("Kept on this device")
        }
        for (m in r.mask) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.token.drop(1).dropLast(1),
                    color = Ball.chalk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Ball.ink).padding(horizontal = 5.dp),
                )
                Text(m.value, color = Ball.ink, fontSize = 14.sp)
            }
        }
    }
}

/** E6: where each step ran, what left the device, and how long it took. */
@Composable
private fun Receipt(r: Result) {
    val sent = r.receipt.filter { it.sent != null }
    val leaked = r.mask.count { m -> sent.any { it.sent!!.contains(m.value) } }
    val total = r.receipt.sumOf { it.ms }
    val cloud = sent.isNotEmpty()
    val shown = sent.lastOrNull()?.sent?.let { s -> Regex("<msg>\n([\\s\\S]*?)\n</msg>").find(s)?.groupValues?.get(1) ?: s }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Black.copy(alpha = .2f))
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(30.dp))
            .padding(20.dp)
            .testTag("receipt"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("THE PLAY", color = Ball.chalk.copy(alpha = .7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(
            if (cloud) "Fielded on your device. Thrown to the cloud, masked." else "Fielded on your device. Nothing left it.",
            color = Ball.chalk,
            fontSize = 22.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        val tone = if (cloud) Ball.cloud else Ball.ok
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(tone.copy(alpha = .1f))
                .drawBehind {
                    drawRoundRect(
                        tone.copy(alpha = .7f),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                        cornerRadius = CornerRadius(16.dp.toPx()),
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (cloud) Glyph.CLOUD else Glyph.HOME, tone, size = 14.dp)
                Text(
                    if (cloud) "EXACTLY WHAT LEFT YOUR DEVICE" else "NOTHING LEFT YOUR DEVICE",
                    color = tone,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.4.sp,
                )
            }
            Text(
                if (shown != null) tokenized(shown) else AnnotatedString("The whole check ran here."),
                color = Ball.chalk,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                modifier = Modifier.testTag("sent"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cell("${r.mask.size}", "secrets kept home", Ball.gold, Modifier.weight(1f))
            Cell("$leaked", "secrets sent", if (leaked > 0) Ball.scam else Ball.gold, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Cell("${sent.size}", if (sent.size == 1) "cloud assist" else "cloud assists", Ball.cloud, Modifier.weight(1f))
            Cell(fmtMs(total), "total", Ball.gold, Modifier.weight(1f))
        }
        for (s in r.receipt) {
            val where = when (s.where) {
                Where.RULES -> "Rules on device"
                Where.ON_DEVICE -> "On device"
                Where.IN_CLOUD -> "Cloud"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(if (s.where == Where.IN_CLOUD) Glyph.CLOUD else Glyph.HOME, if (s.where == Where.IN_CLOUD) Ball.cloud else Ball.ok, Modifier.padding(top = 2.dp), 14.dp)
                Text("$where · ${s.what}", color = Ball.chalk.copy(alpha = .85f), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(fmtMs(s.ms), color = Ball.chalk.copy(alpha = .6f), fontSize = 13.sp)
            }
        }
    }
}

private fun tokenized(s: String) = buildAnnotatedString {
    var i = 0
    for (m in TOKEN_SPLIT.findAll(s)) {
        append(s.substring(i, m.range.first))
        withStyle(SpanStyle(background = Ball.cloud, color = Ball.cell, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)) {
            append("\u00a0${m.value.drop(1).dropLast(1)}\u00a0")
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
private fun Cell(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ball.cell)
            .border(BorderStroke(1.dp, Ball.cellLine), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(value, color = color, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(label.uppercase(Locale.US), color = Ball.chalk.copy(alpha = .7f), fontSize = 11.sp, letterSpacing = 0.9.sp)
    }
}

@Composable
private fun HistoryCard(history: List<Check>, actions: UiActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("History", color = Ball.ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Ghost("Back", Glyph.BACK) { actions.back() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Glyph.HOME, Ball.muted, size = 14.dp)
        Text("Stored only on this phone, with private details hidden.", color = Ball.muted, fontSize = 13.sp)
    }
    if (history.isEmpty()) {
        Text("No checks yet.", color = Ball.muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 20.dp))
        return
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US) }
    for (c in history) {
        val v = Verdict.of(c.verdict) ?: Verdict.SUSPICIOUS
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ball.paper).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    word(v).big,
                    color = if (v == Verdict.SUSPICIOUS) Ball.ink else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(CircleShape).background(verdictColor(v)).padding(horizontal = 10.dp, vertical = 2.dp),
                )
                Text(fmt.format(Date(c.at)), color = Ball.muted, fontSize = 12.sp)
            }
            Text(c.masked, color = Ball.ink, fontSize = 14.sp, maxLines = 3)
        }
    }
    BigButton("Clear history", null, Modifier.testTag("clear"), bg = Ball.scam) { actions.clearHistory() }
}
