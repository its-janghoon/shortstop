package io.github.itsjanghoon.shortstop.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.data.Check
import io.github.itsjanghoon.shortstop.engine.Deps
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Input
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.Models
import io.github.itsjanghoon.shortstop.engine.Result
import io.github.itsjanghoon.shortstop.engine.TestShared
import io.github.itsjanghoon.shortstop.engine.Where
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens to PNG for visual QA. Runs only with `./gradlew testDebugUnitTest -Pshots=<dir>`;
 * otherwise every test is skipped. Phone-size frames (Pixel 7, 411x914 dp) plus tall "full" frames for long results.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xhdpi")
class ShotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = System.getProperty("shortstop.shots").orEmpty()
    private val shared = TestShared.shared
    private val engine = TestShared.engine

    private var current: androidx.compose.runtime.MutableState<Pair<Int, UiState>>? = null

    private fun shot(name: String, state: UiState, settleMs: Long = 6000) {
        assumeTrue("set -Pshots=<dir> to render screenshots", out.isNotEmpty())
        compose.mainClock.autoAdvance = false
        val holder = current
        if (holder == null) {
            val h = androidx.compose.runtime.mutableStateOf(0 to state)
            current = h
            compose.setContent { androidx.compose.runtime.key(h.value.first) { MainScreen(h.value.second, object : UiActions {}) } }
        } else {
            // A new key remounts the screen, so every shot starts its animations from zero.
            holder.value = (holder.value.first + 1) to state
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(settleMs)
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun rules(text: String): Result = runBlocking { engine.analyze(Input(text)) }

    /** On-device first look unsure, cloud says scam: the path where masked text leaves the phone. */
    private fun cloud(text: String): Result = runBlocking {
        val models = object : Models {
            override suspend fun triage(prompt: String, image: Image?) =
                ModelCall("""{"verdict":"suspicious","confidence":0.55,"signals":["fake bank site"],"action":"","names":["Jane Doe"]}""", Where.ON_DEVICE)

            override suspend fun deep(prompt: String) = ModelCall(
                """{"verdict":"scam","confidence":0.94,"signals":["link copies Acme Bank","pushes you to act now"],"action":"Do not call [PHONE_1]. Call Acme Bank using the number on your card."}""",
                Where.IN_CLOUD,
            )
        }
        var t = 0.0
        engine.analyze(Input(text), Deps(models = models, now = { t += 37.0; t }))
    }

    private fun result(r: Result, from: String, device: DeviceStatus = DeviceStatus.UNAVAILABLE) =
        UiState(screen = Screen.RESULT, result = r, from = from, device = device, examples = shared.examples)

    @Test
    fun home() = shot("01-home", UiState(examples = shared.examples, device = DeviceStatus.UNAVAILABLE, credits = io.github.itsjanghoon.shortstop.credits(shared.wordsNotice)))

    @Test
    @Config(qualifiers = "w411dp-h1900dp-mdpi")
    fun homeFull() = shot("01-home-full", UiState(examples = shared.examples, device = DeviceStatus.UNAVAILABLE, credits = io.github.itsjanghoon.shortstop.credits(shared.wordsNotice)))

    @Test
    fun examples() {
        for ((i, e) in shared.examples.withIndex()) {
            shot("%02d-example-%s".format(i + 2, e.id), result(rules(e.text), "Example · ${e.label}"))
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-mdpi")
    fun examplesFull() {
        for ((i, e) in shared.examples.withIndex()) {
            shot("%02d-example-%s-full".format(i + 2, e.id), result(rules(e.text), "Example · ${e.label}"))
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-mdpi")
    fun cloudPathFull() {
        val e = shared.examples.first { it.id == "bank-locked" }
        shot("08-cloud-path-full", result(cloud(e.text), "Example · ${e.label}", DeviceStatus.AVAILABLE))
    }

    @Test
    @Config(qualifiers = "w411dp-h1900dp-mdpi")
    fun cloudPathFrames() {
        val e = shared.examples.first { it.id == "bank-locked" }
        val r = cloud(e.text)
        for (ms in listOf(300L, 1200L, 2400L, 3600L)) shot("08-cloud-path-at-%04dms".format(ms), result(r, "Example · ${e.label}", DeviceStatus.AVAILABLE), ms)
    }

    @Test
    fun shared() {
        val text = "USPS: We could not deliver your parcel. Pay the redelivery fee of $2.49 within 24 hours at usps-redelivery-help.top/pay"
        shot("09-share-text", result(rules(text), "Shared to Shortstop"))
    }

    @Test
    fun offline() {
        shot("10-offline", UiState(examples = shared.examples, device = DeviceStatus.UNAVAILABLE, online = false))
        shot("10-offline-nano", UiState(examples = shared.examples, device = DeviceStatus.AVAILABLE, online = false))
    }

    @Test
    fun history() {
        val now = 1_794_700_000_000L
        val items = shared.examples.mapIndexed { i, e ->
            val r = rules(e.text)
            Check(id = i + 1L, at = now - i * 3_600_000L, verdict = r.verdict.wire, action = r.action, masked = r.masked, decidedBy = r.decidedBy.wire, signals = "")
        }
        shot("11-history", UiState(screen = Screen.HISTORY, history = items, device = DeviceStatus.UNAVAILABLE))
        shot("12-history-cleared", UiState(screen = Screen.HISTORY, history = emptyList(), device = DeviceStatus.UNAVAILABLE))
    }
}
