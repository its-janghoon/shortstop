@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package io.github.itsjanghoon.shortstop.cloud

import android.util.Log
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.OnDeviceExtension
import com.google.firebase.ai.OnDeviceModelStatus
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.Models
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val TAG = "Shortstop"

/** The parts of Firebase's OnDeviceExtension the app uses, so tests can fake a phone without Gemini Nano. */
interface DeviceModel {
    suspend fun checkStatus(): OnDeviceModelStatus
    fun download(): Flow<DownloadStatus>
    suspend fun warmUp()
}

class ExtensionModel(private val ext: OnDeviceExtension) : DeviceModel {
    override suspend fun checkStatus() = ext.checkStatus()
    override fun download() = ext.download()
    override suspend fun warmUp() = ext.warmUp()
}

/**
 * Run [block]; any failure (ML Kit GenAiException 601 BINDING_FAILURE, 606 FEATURE_NOT_FOUND, a timeout,
 * even an Error from a missing class) comes back as a failed Result. Only a real cancellation is rethrown.
 */
suspend fun <T> safely(what: String, block: suspend () -> T): kotlin.Result<T> = try {
    kotlin.Result.success(block())
} catch (e: CancellationException) {
    if (e !is TimeoutCancellationException) throw e
    kotlin.Result.failure(e)
} catch (e: Throwable) {
    runCatching { Log.w(TAG, "$what failed: ${e.message}", e) }
    kotlin.Result.failure(e)
}

/**
 * Check the on-device model, start the download if it can be downloaded, warm it up, and keep [state] current.
 * ML Kit GenAI only works while the app is in the foreground, so every call waits for [foreground].
 * Any error means "no on-device model": the cloud then sees masked text only. It never throws.
 */
suspend fun watchDevice(model: DeviceModel, state: MutableStateFlow<DeviceStatus>, foreground: Flow<Boolean>, retryMs: Long = 10_000) {
    repeat(60) {
        foreground.first { it }
        val status = safely("on-device checkStatus") { withTimeout(5000) { model.checkStatus() } }.getOrElse {
            state.value = DeviceStatus.UNAVAILABLE
            return
        }
        when (status) {
            OnDeviceModelStatus.AVAILABLE -> {
                state.value = if (warm(model, foreground)) DeviceStatus.AVAILABLE else DeviceStatus.UNAVAILABLE
                return
            }
            OnDeviceModelStatus.DOWNLOADABLE -> {
                state.value = DeviceStatus.DOWNLOADING
                var done = false
                val ok = safely("on-device download") {
                    model.download().collect { d ->
                        when (d) {
                            is DownloadStatus.DownloadCompleted -> done = true
                            is DownloadStatus.DownloadFailed -> state.value = DeviceStatus.UNAVAILABLE
                            else -> state.value = DeviceStatus.DOWNLOADING
                        }
                    }
                }.isSuccess
                if (!ok) {
                    state.value = DeviceStatus.UNAVAILABLE
                    return
                }
                if (done) {
                    state.value = if (warm(model, foreground)) DeviceStatus.AVAILABLE else DeviceStatus.UNAVAILABLE
                    return
                }
                if (state.value == DeviceStatus.UNAVAILABLE) return
            }
            OnDeviceModelStatus.DOWNLOADING -> state.value = DeviceStatus.DOWNLOADING
            else -> {
                state.value = DeviceStatus.UNAVAILABLE
                return
            }
        }
        delay(retryMs)
    }
}

/** Load Gemini Nano into memory so the first check is fast. False if the model cannot run after all. */
private suspend fun warm(model: DeviceModel, foreground: Flow<Boolean>): Boolean {
    foreground.first { it }
    return safely("on-device warmUp") { model.warmUp() }.isSuccess
}

/**
 * Picks where each call runs. Text triage tries Gemini Nano only when it is ready; if it fails anyway, the chip
 * flips to "no on-device model" and the same masked prompt goes to the cloud. Photos never go to the cloud.
 * Errors leave as Exceptions, so the engine's own fallbacks handle them.
 */
class HybridModels(
    private val device: MutableStateFlow<DeviceStatus>,
    private val strict: Boolean,
    private val onDevice: suspend (String) -> ModelCall,
    private val onDevicePhoto: suspend (String, Image) -> ModelCall,
    private val cloud: suspend (String) -> ModelCall,
) : Models {
    override suspend fun triage(prompt: String, image: Image?): ModelCall {
        if (image != null) {
            check(device.value == DeviceStatus.AVAILABLE) { "on-device model not ready" }
            return safely("on-device photo check") { onDevicePhoto(prompt, image) }.getOrElse {
                device.value = DeviceStatus.UNAVAILABLE
                throw asException(it)
            }
        }
        if (device.value == DeviceStatus.AVAILABLE || strict) {
            safely("on-device check") { onDevice(prompt) }.onSuccess { return it }.onFailure {
                device.value = DeviceStatus.UNAVAILABLE
                if (strict) throw asException(it)
            }
        }
        // No on-device model ready: do not wait for a download; the cloud sees masked text only.
        return deep(prompt)
    }

    override suspend fun deep(prompt: String): ModelCall = safely("cloud check") { cloud(prompt) }.getOrElse { throw asException(it) }

    private fun asException(t: Throwable): Exception = when (t) {
        // A timeout must not look like a cancellation of the whole check.
        is CancellationException -> IllegalStateException("timed out", t)
        is Exception -> t
        else -> IllegalStateException(t.message ?: t.javaClass.simpleName, t)
    }
}
