package io.github.itsjanghoon.shortstop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.text.SpannableString
import androidx.lifecycle.ViewModelProvider
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import androidx.test.core.app.ApplicationProvider
import io.github.itsjanghoon.shortstop.cloud.Backend
import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.Models
import io.github.itsjanghoon.shortstop.ui.Screen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app without Firebase: [TestApp.backend] decides what the "cloud" does. */
class TestApp : ShortstopApp() {
    companion object {
        var models: Models? = null
    }

    override fun foreground(): Flow<Boolean> = flowOf(true)

    override suspend fun connect(): Backend? {
        val m = models ?: return null
        return Backend(m, shared.defaults, MutableStateFlow(DeviceStatus.UNAVAILABLE), { false }, {})
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class AppTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** Animations off, as with "Remove animations": endless Compose animations would keep Robolectric's looper busy. */
    @org.junit.Before
    fun noAnimations() {
        android.provider.Settings.Global.putFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After
    fun reset() {
        TestApp.models = null
        Crash.clear(ctx)
    }

    private fun send(text: CharSequence?) = Intent(Intent.ACTION_SEND).apply {
        setClassName(ctx, MainActivity::class.java.name)
        type = "text/plain"
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
    }

    private fun start(intent: Intent): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java, intent).setup()

    private fun settle(c: ActivityController<MainActivity>, until: (MainViewModel) -> Boolean): MainViewModel {
        val vm = ViewModelProvider(c.get())[MainViewModel::class.java]
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (until(vm)) return vm
            Thread.sleep(20)
        }
        return vm
    }

    private fun <T> ActivityController<MainActivity>.use(block: (ActivityController<MainActivity>) -> T): T =
        try { block(this) } finally { pause().stop().destroy() }

    @Test
    fun shareOfIgnoresWhatIsNotAShare() {
        assertNull(shareOf(null))
        assertNull(shareOf(Intent(Intent.ACTION_VIEW)))
        assertNull(shareOf(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertNull(shareOf(send("   ")))
        // An image share without the image.
        assertNull(shareOf(Intent(Intent.ACTION_SEND).setType("image/png")))
    }

    @Test
    fun shareOfReadsStyledTextAndImages() {
        assertEquals("Pay now", shareOf(send(SpannableString("Pay now")))?.text)
        val uri = Uri.parse("content://media/1")
        val img = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, shareOf(img)?.image)
    }

    @Test
    fun sendWithNullTextOpensTheEmptyScreen() {
        start(send(null)).use { s ->
            val vm = settle(s) { true }
            assertEquals(Screen.COMPOSE, vm.state.value.screen)
            assertNull(vm.state.value.error)
        }
    }

    @Test
    fun sendWithEmptyTextOpensTheEmptyScreen() {
        start(send("")).use { s ->
            assertEquals(Screen.COMPOSE, settle(s) { true }.state.value.screen)
        }
    }

    @Test
    fun coldStartShareIsChecked() {
        start(send("Acme Parcel: pay the unpaid fee within 24 hours at acme-parcel-redelivery.co/pay")).use { s ->
            val vm = settle(s) { it.state.value.screen == Screen.RESULT }
            assertEquals(Screen.RESULT, vm.state.value.screen)
            assertEquals("Shared to Shortstop", vm.state.value.from)
        }
    }

    @Test
    fun shareIntoTheRunningActivityGoesThroughOnNewIntent() {
        val main = Intent(Intent.ACTION_MAIN).setClassName(ctx, MainActivity::class.java.name)
        start(main).use { s ->
            settle(s) { true }
            // Robolectric pauses, delivers onNewIntent and resumes, like singleTop on a phone.
            s.newIntent(send("Grandma it's me, buy gift cards and send me the codes, don't tell mom"))
            val vm = settle(s) { it.state.value.screen == Screen.RESULT }
            assertEquals(Screen.RESULT, vm.state.value.screen)
            assertTrue(vm.state.value.draft.startsWith("Grandma"))
            // A second share with no text keeps the result instead of crashing.
            s.newIntent(send(null))
            assertEquals(Screen.RESULT, settle(s) { true }.state.value.screen)
        }
    }

    @Test
    fun anErrorFromTheModelsShowsAnErrorInsteadOfClosingTheApp() {
        val failing = object : Models {
            override suspend fun triage(prompt: String, image: Image?): ModelCall = throw ExceptionInInitializerError("model class failed to load")
            override suspend fun deep(prompt: String): ModelCall = throw ExceptionInInitializerError("model class failed to load")
        }
        val app = ctx as TestApp
        val main = Intent(Intent.ACTION_MAIN).setClassName(ctx, MainActivity::class.java.name)
        start(main).use { s ->
            var vm = settle(s) { true }
            // Run the check with failing models through the same path the app uses.
            vm.draft("Verify your account at acrnebank.com now")
            vm.checkWith(Backend(failing, app.shared.defaults, MutableStateFlow(DeviceStatus.UNAVAILABLE), { false }, {}))
            vm = settle(s) { it.state.value.screen != Screen.CHECKING }
            assertEquals(Screen.COMPOSE, vm.state.value.screen)
            assertTrue(vm.state.value.error.orEmpty(), vm.state.value.error.orEmpty().startsWith("Could not check: model class failed to load"))
        }
    }

    @Test
    fun crashFileIsWrittenReadAndCleared() {
        Crash.record(ctx, "test", IllegalStateException("boom"))
        val text = Crash.read(ctx)!!
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(Crash.summary(text).startsWith("java.lang.IllegalStateException: boom"))
        (ctx as TestApp).dismissCrash()
        assertNull(Crash.read(ctx))
    }

    @Test
    fun uncaughtHandlerWritesTheFileAndChainsToThePreviousOne() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        var chained: Throwable? = null
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> chained = e }
            Crash.install(ctx)
            val e = RuntimeException("on a worker thread")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), e)
            assertEquals(e, chained)
            assertTrue(Crash.read(ctx)!!.contains("on a worker thread"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun failedBackgroundJobBecomesAnErrorLine() {
        val app = ctx as TestApp
        app.handler.handleException(kotlin.coroutines.EmptyCoroutineContext, IllegalStateException("watch failed"))
        assertTrue(app.problem.value!!.contains("watch failed"))
        assertTrue(Crash.read(ctx)!!.contains("watch failed"))
        app.clearProblem()
        assertFalse(app.problem.value != null)
    }
}
