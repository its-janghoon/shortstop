@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.itsjanghoon.shortstop.cloud

import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.OnDeviceModelStatus
import io.github.itsjanghoon.shortstop.engine.Deps
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Input
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.TestShared
import io.github.itsjanghoon.shortstop.engine.Where
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phones without Gemini Nano (the Galaxy S26 FE in the Remote Test Lab): ML Kit throws GenAiException
 * 606 FEATURE_NOT_FOUND, or 601 BINDING_FAILURE before an AICore update. None of that may close the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceTest {
    /** The real ML Kit exception class, built by reflection (it is only on the runtime classpath). */
    private fun genAi(code: Int, msg: String): Exception =
        Class.forName("com.google.mlkit.genai.common.GenAiException")
            .getConstructor(String::class.java, Throwable::class.java, Int::class.javaPrimitiveType)
            .newInstance(msg, null, code) as Exception

    private val e606 get() = genAi(606, "Feature 636 is not available")
    private val e601 get() = genAi(601, "BINDING_FAILURE")

    private class Fake(
        val status: () -> OnDeviceModelStatus,
        val downloads: Flow<DownloadStatus> = flow { },
        val warm: () -> Unit = {},
    ) : DeviceModel {
        var checks = 0
        override suspend fun checkStatus(): OnDeviceModelStatus {
            checks++
            return status()
        }
        override fun download() = downloads
        override suspend fun warmUp() = warm()
    }

    private val fg = MutableStateFlow(true)

    @Test
    fun checkStatus606MeansNoOnDeviceModel() = runTest {
        val state = MutableStateFlow(DeviceStatus.DOWNLOADING)
        watchDevice(Fake({ throw e606 }), state, fg)
        assertEquals(DeviceStatus.UNAVAILABLE, state.value)
    }

    @Test
    fun checkStatus601MeansNoOnDeviceModel() = runTest {
        val state = MutableStateFlow(DeviceStatus.DOWNLOADING)
        watchDevice(Fake({ throw e601 }), state, fg)
        assertEquals(DeviceStatus.UNAVAILABLE, state.value)
    }

    @Test
    fun anErrorFromCheckStatusIsCaughtToo() = runTest {
        val state = MutableStateFlow(DeviceStatus.DOWNLOADING)
        watchDevice(Fake({ throw NoClassDefFoundError("com/google/mlkit/genai/prompt/Generation") }), state, fg)
        assertEquals(DeviceStatus.UNAVAILABLE, state.value)
    }

    @Test
    fun downloadThatThrowsMeansNoOnDeviceModel() = runTest {
        val state = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        watchDevice(Fake({ OnDeviceModelStatus.DOWNLOADABLE }, flow { throw e606 }), state, fg)
        assertEquals(DeviceStatus.UNAVAILABLE, state.value)
    }

    @Test
    fun warmUpThatThrowsMeansNoOnDeviceModel() = runTest {
        val state = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        watchDevice(Fake({ OnDeviceModelStatus.AVAILABLE }, warm = { throw e601 }), state, fg)
        assertEquals(DeviceStatus.UNAVAILABLE, state.value)
    }

    @Test
    fun availableAndWarmMeansAvailable() = runTest {
        val state = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        watchDevice(Fake({ OnDeviceModelStatus.AVAILABLE }), state, fg)
        assertEquals(DeviceStatus.AVAILABLE, state.value)
    }

    @Test
    fun noOnDeviceCallsWhileTheAppIsInTheBackground() = runTest {
        // Started with the screen off: ML Kit GenAI refuses background calls, so wait for the foreground.
        fg.value = false
        val state = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        val fake = Fake({ OnDeviceModelStatus.AVAILABLE })
        val job = launch { watchDevice(fake, state, fg) }
        runCurrent()
        assertEquals(0, fake.checks)
        fg.value = true
        advanceUntilIdle()
        assertEquals(1, fake.checks)
        assertEquals(DeviceStatus.AVAILABLE, state.value)
        job.cancel()
    }

    private val good = """{"verdict":"scam","confidence":0.9,"signals":[],"action":""}"""

    @Test
    fun onDeviceGenerate606FallsBackToTheCloudWithMaskedText() = runTest {
        val device = MutableStateFlow(DeviceStatus.AVAILABLE)
        val sent = mutableListOf<String>()
        val models = HybridModels(
            device = device,
            strict = false,
            onDevice = { throw e606 },
            onDevicePhoto = { _, _ -> throw e606 },
            cloud = { p -> sent.add(p); ModelCall(good, Where.IN_CLOUD) },
        )
        val r = TestShared.engine.analyze(Input("Hi Jane Doe, call 555-201-3344 now"), Deps(models = models))
        assertEquals(DeviceStatus.UNAVAILABLE, device.value)
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.none { it.contains("555-201-3344") })
        assertEquals(Where.IN_CLOUD, r.decidedBy)
    }

    @Test
    fun photoOnAPhoneWithoutGeminiNanoAsksForTextInstead() = runTest {
        val device = MutableStateFlow(DeviceStatus.AVAILABLE)
        val models = HybridModels(device, false, { throw e606 }, { _, _ -> throw e606 }, { ModelCall(good, Where.IN_CLOUD) })
        try {
            TestShared.engine.analyze(Input(image = Image(byteArrayOf(1))), Deps(models = models))
            fail("expected NeedsOnDeviceException")
        } catch (_: io.github.itsjanghoon.shortstop.engine.NeedsOnDeviceException) {
        }
        assertEquals(DeviceStatus.UNAVAILABLE, device.value)
    }

    @Test
    fun cloudErrorsBecomeExceptionsSoTheEngineFallsBack() = runTest {
        val device = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        val models = HybridModels(device, false, { error("unused") }, { _, _ -> error("unused") }, { throw NoClassDefFoundError("io/ktor/Client") })
        // The engine catches Exceptions only; an Error from the SDK must not escape as an Error.
        val r = TestShared.engine.analyze(Input("Pay the fee at acme-parcel-redelivery.co now"), Deps(models = models))
        assertEquals(Where.RULES, r.decidedBy)
    }
}
