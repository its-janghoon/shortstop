package io.github.itsjanghoon.shortstop.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.engine.Example
import io.github.itsjanghoon.shortstop.engine.Input
import io.github.itsjanghoon.shortstop.engine.TestShared
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val examples = TestShared.shared.examples

    @Test
    fun composeShowsSixExamplesStatusAndOfflineBanner() {
        var picked: Example? = null
        compose.setContent {
            MainScreen(
                UiState(examples = examples, device = DeviceStatus.UNAVAILABLE, online = false),
                object : UiActions {
                    override fun example(example: Example) {
                        picked = example
                    }
                },
            )
        }
        compose.onNodeWithText("No on-device model · cloud sees masked text only").assertIsDisplayed()
        compose.onNodeWithTag("offline").assertIsDisplayed()
        assertEquals(6, examples.size)
        for (e in examples) compose.onNodeWithTag("example-${e.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("example-grandkid").performScrollTo().performClick()
        assertEquals("grandkid", picked?.id)
    }

    @Test
    fun removeAnimationsJumpsStraightToTheEndOfThePlay() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        android.provider.Settings.Global.putFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val r = runBlocking { TestShared.engine.analyze(Input(examples.first { it.id == "grandkid" }.text)) }
        var seen = -1f
        compose.mainClock.autoAdvance = false
        compose.setContent { seen = rememberPlayClock(r).value }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(Play(r).end, seen, 1e-3f)
    }

    @Test
    fun resultShowsVerdictKeptListReceiptAndOneActionButton() {
        val ex = examples.first { it.id == "bank-locked" }
        val r = runBlocking { TestShared.engine.analyze(Input(ex.text)) }
        compose.setContent {
            MainScreen(UiState(screen = Screen.RESULT, result = r, from = "Example · ${ex.label}", device = DeviceStatus.RULES), object : UiActions {})
        }
        compose.onNodeWithText("SCAM.").assertIsDisplayed()
        compose.onNodeWithTag("act").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Call the real number").assertIsDisplayed()
        compose.onNodeWithTag("kept").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Kept on this device".uppercase()).assertIsDisplayed()
        compose.onNodeWithTag("family").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receipt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("NOTHING LEFT YOUR DEVICE").assertIsDisplayed()
    }
}
