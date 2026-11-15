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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val shortstop = app as ShortstopApp
    private val _state = MutableStateFlow(UiState(examples = shortstop.shared.examples))
    val state: StateFlow<UiState> = _state
    private var backend: Backend? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            backend = shortstop.backend.await()
            val device = backend?.device ?: flowOf(DeviceStatus.RULES)
            device.collect { d -> _state.update { it.copy(device = d) } }
        }
        viewModelScope.launch { onlineFlow(app).collect { on -> _state.update { it.copy(online = on) } } }
        viewModelScope.launch { shortstop.history.checks().all().collect { h -> _state.update { it.copy(history = h) } } }
    }

    fun draft(text: String) = _state.update { it.copy(draft = text, error = null) }

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
        job = viewModelScope.launch {
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
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(screen = Screen.COMPOSE, error = "Could not check: ${e.message}") }
            }
        }
    }

    fun again() = _state.update { it.copy(screen = Screen.COMPOSE, image = null, result = null, error = null) }

    fun report() {
        val r = _state.value.result ?: return
        val b = backend ?: return
        viewModelScope.launch {
            runCatching { for (l in r.links) if (l.reasons.isNotEmpty()) b.report(registrable(l.host)) }
                .onSuccess { _state.update { it.copy(reported = true) } }
                .onFailure { e -> _state.update { it.copy(error = "Could not report: ${e.message}") } }
        }
    }

    fun openHistory() = _state.update { it.copy(screen = Screen.HISTORY, error = null) }

    fun back() = _state.update { it.copy(screen = if (it.result != null) Screen.RESULT else Screen.COMPOSE) }

    fun clearHistory() {
        viewModelScope.launch { shortstop.history.checks().clear() }
    }
}
