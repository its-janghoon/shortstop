package io.github.itsjanghoon.shortstop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.itsjanghoon.shortstop.cloud.Backend
import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.data.Check
import io.github.itsjanghoon.shortstop.engine.Deps
import io.github.itsjanghoon.shortstop.engine.Example
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Input
import io.github.itsjanghoon.shortstop.engine.NeedsOnDeviceException
import io.github.itsjanghoon.shortstop.engine.Result
import io.github.itsjanghoon.shortstop.engine.Verdict
import io.github.itsjanghoon.shortstop.engine.registrable
import io.github.itsjanghoon.shortstop.ui.Screen
import io.github.itsjanghoon.shortstop.ui.UiState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val shortstop = app as ShortstopApp
    private val _state = MutableStateFlow(UiState(examples = shortstop.shared.examples, credits = credits(shortstop.shared.wordsNotice)))
    val state: StateFlow<UiState> = _state
    private var backend: Backend? = null
    private var job: Job? = null

    /** A failed job shows an error line and is kept in last-crash.txt; it never closes the app. */
    private val handler = CoroutineExceptionHandler { _, e ->
        Crash.record(app, "view model", e)
        _state.update {
            it.copy(
                screen = if (it.screen == Screen.CHECKING) Screen.COMPOSE else it.screen,
                error = "Something went wrong: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    init {
        _state.update { it.copy(lastCrash = shortstop.lastCrash) }
        viewModelScope.launch(handler) { shortstop.problem.collect { p -> if (p != null) _state.update { it.copy(error = p) } } }
        viewModelScope.launch(handler) {
            backend = shortstop.backend.await()
            val device = backend?.device ?: flowOf(DeviceStatus.RULES)
            device.collect { d -> _state.update { it.copy(device = d) } }
        }
        viewModelScope.launch(handler) { onlineFlow(app).collect { on -> _state.update { it.copy(online = on) } } }
        viewModelScope.launch(handler) { shortstop.history.checks().all().collect { h -> _state.update { it.copy(history = h) } } }
    }

    fun draft(text: String) {
        shortstop.clearProblem()
        _state.update { it.copy(draft = text, error = null) }
    }

    fun setImage(image: Image?) = _state.update { it.copy(image = image, error = null) }

    fun example(e: Example) {
        _state.update { it.copy(draft = e.text, image = null) }
        check("Example · ${e.label}")
    }

    /** A1: text or a screenshot shared from another app. */
    fun shared(text: String?, image: Image?) {
        _state.update { it.copy(draft = text.orEmpty(), image = image, screen = Screen.COMPOSE) }
        check(if (image != null) "Shared screenshot" else "Shared to Shortstop")
    }

    fun check(from: String? = null) {
        val s = _state.value
        if (s.draft.isBlank() && s.image == null) return
        job?.cancel()
        _state.update { it.copy(screen = Screen.CHECKING, error = null, reported = false) }
        job = viewModelScope.launch(handler) {
            // Wait a little for Firebase on a cold start (share sheet), then go on with what is ready.
            val b = backend ?: withTimeoutOrNull(8000) { shortstop.backend.await() }
            try {
                val r = shortstop.engine.analyze(
                    Input(text = s.draft, image = s.image),
                    if (b != null) Deps(models = b.models, config = b.config.engine, isReported = b.isReported) else Deps(),
                )
                _state.update {
                    it.copy(
                        screen = Screen.RESULT,
                        result = r,
                        from = from ?: if (s.image != null) "Your screenshot" else "Your message",
                        canReport = b != null && r.verdict == Verdict.SCAM && r.links.any { l -> l.reasons.isNotEmpty() },
                    )
                }
                shortstop.history.checks().add(
                    Check(
                        at = System.currentTimeMillis(),
                        verdict = r.verdict.wire,
                        action = remask(r.action, r),
                        masked = r.masked,
                        decidedBy = r.decidedBy.wire,
                        signals = r.signals.joinToString("; ") { remask(it, r) },
                    ),
                )
            } catch (e: NeedsOnDeviceException) {
                _state.update { it.copy(screen = Screen.COMPOSE, error = e.message) }
            } catch (e: Throwable) {
                // Errors too (a class that failed to load, a missing method): show them, never close the app.
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e !is Exception) Crash.record(getApplication(), "check", e)
                _state.update { it.copy(screen = Screen.COMPOSE, error = "Could not check: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun again() = _state.update { it.copy(screen = Screen.COMPOSE, image = null, result = null, error = null) }

    fun report() {
        val r = _state.value.result ?: return
        val b = backend ?: return
        viewModelScope.launch(handler) {
            runCatching { for (l in r.links) if (l.reasons.isNotEmpty()) b.report(registrable(l.host)) }
                .onSuccess { _state.update { it.copy(reported = true) } }
                .onFailure { e -> _state.update { it.copy(error = "Could not report: ${e.message}") } }
        }
    }

    fun openHistory() = _state.update { it.copy(screen = Screen.HISTORY, error = null) }

    fun back() = _state.update { it.copy(screen = if (it.result != null) Screen.RESULT else Screen.COMPOSE) }

    /** Tests: run a check against a given backend. */
    @androidx.annotation.VisibleForTesting
    internal fun checkWith(b: Backend) {
        backend = b
        check()
    }

    fun dismissCrash() {
        shortstop.dismissCrash()
        _state.update { it.copy(lastCrash = null) }
    }

    fun clearHistory() {
        viewModelScope.launch(handler) { shortstop.history.checks().clear() }
    }
}

/** One credit line for the word list, built from the copyright line of words.NOTICE. */
fun credits(notice: String): String =
    notice.lines().firstOrNull { it.startsWith("Copyright") }?.let { "Name finder word list: SCOWL (wamerican), $it." } ?: ""
