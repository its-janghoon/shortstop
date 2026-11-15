package io.github.itsjanghoon.shortstop.ui

import io.github.itsjanghoon.shortstop.cloud.DeviceStatus
import io.github.itsjanghoon.shortstop.data.Check
import io.github.itsjanghoon.shortstop.engine.Example
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Result

enum class Screen { COMPOSE, CHECKING, RESULT, HISTORY }

data class UiState(
    val screen: Screen = Screen.COMPOSE,
    val draft: String = "",
    val image: Image? = null,
    val result: Result? = null,
    /** "Your message", "Your screenshot", "Shared to Shortstop" or "Example · <label>". */
    val from: String = "",
    val error: String? = null,
    /** Null while Firebase is still starting. */
    val device: DeviceStatus? = null,
    val online: Boolean = true,
    val canReport: Boolean = false,
    val reported: Boolean = false,
    val history: List<Check> = emptyList(),
    val examples: List<Example> = emptyList(),
)

interface UiActions {
    fun draft(text: String) {}
    fun pickImage() {}
    fun clearImage() {}
    fun example(example: Example) {}
    fun check() {}
    fun again() {}
    /** The one action button on the verdict card. */
    fun act() {}
    fun sendToFamily() {}
    fun report() {}
    fun openHistory() {}
    fun clearHistory() {}
    fun back() {}
}
