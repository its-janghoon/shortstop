package io.github.itsjanghoon.shortstop.cloud

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import io.github.itsjanghoon.shortstop.engine.AppConfig
import io.github.itsjanghoon.shortstop.engine.EngineConfig
import io.github.itsjanghoon.shortstop.engine.ModelCall
import io.github.itsjanghoon.shortstop.engine.Models
import io.github.itsjanghoon.shortstop.engine.Shared
import io.github.itsjanghoon.shortstop.engine.Where
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.Locale

/** On-device model state, shown as the status chip. */
enum class DeviceStatus(val label: String) {
    AVAILABLE("Gemini Nano on this device"),
    DOWNLOADING("On-device model downloading"),
    UNAVAILABLE("No on-device model · cloud sees masked text only"),
    RULES("AI not set up · rules only"),
}

class Backend(
    val models: Models,
    val config: AppConfig,
    val device: StateFlow<DeviceStatus>,
    val isReported: suspend (String) -> Boolean,
    val report: suspend (String) -> Unit,
)

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.lowercase(Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }

/** Cloud response schema built from shared/verdict.schema.json, so every platform asks for the same shape. */
fun schemaFrom(json: JsonObject): Schema {
    val required = json["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    val props = json.getValue("properties").jsonObject
    fun one(o: JsonObject): Schema {
        val desc = o["description"]?.jsonPrimitive?.content
        return when (o["type"]?.jsonPrimitive?.content) {
            "string" -> o["enum"]?.jsonArray?.let { e -> Schema.enumeration(e.map { it.jsonPrimitive.content }, desc) } ?: Schema.string(desc)
            "number" -> Schema.double(desc)
            "array" -> Schema.array(one(o.getValue("items").jsonObject), desc, maxItems = o["maxItems"]?.jsonPrimitive?.int)
            else -> Schema.string(desc)
        }
    }
    return Schema.obj(props.mapValues { one(it.value.jsonObject) }, optionalProperties = props.keys.filter { it !in required })
}

/**
 * Firebase wiring, the Android twin of engine/src/firebase.ts.
 * Returns null when the app was built without google-services.json (rules-only mode).
 */
@OptIn(PublicPreviewAPI::class)
object FirebaseBackend {
    private const val TAG = "Shortstop"

    /** [foreground] is true while the app is visible: ML Kit GenAI refuses calls from the background. */
    suspend fun connect(context: Context, shared: Shared, scope: CoroutineScope, foreground: Flow<Boolean>): Backend? {
        val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context) ?: return null

        // App Check: Play Integrity in release, debug provider in debug builds. Never block the app on it.
        runCatching { AppCheckSetup.install(app) }.onFailure { Log.w(TAG, "App Check not installed", it) }

        withTimeoutOrNull(6000) { runCatching { Firebase.auth.signInAnonymously().await() } }

        val rc = Firebase.remoteConfig
        val defaults = shared.defaultsMap.mapValues { (_, v) -> if (v is Double && v % 1.0 == 0.0) v.toLong() else v }
        runCatching {
            rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 })
            rc.setDefaultsAsync(defaults).await()
        }
        withTimeoutOrNull(6000) { runCatching { rc.fetchAndActivate().await() } }
        val d = shared.defaults
        val config = runCatching {
            AppConfig(
                engine = EngineConfig(
                    escalateBelowConfidence = rc.getDouble("escalate_below_confidence"),
                    onDeviceMaxChars = rc.getLong("on_device_max_chars").toInt(),
                    strictOnDevice = rc.getBoolean("strict_on_device"),
                ),
                cloudModel = rc.getString("cloud_model").ifBlank { d.cloudModel },
                reportMinCount = rc.getLong("report_min_count").toInt(),
            )
        }.getOrDefault(d)

        val ai = Firebase.ai(backend = GenerativeBackend.googleAI())
        val cloudJson = generationConfig {
            responseMimeType = "application/json"
            responseSchema = schemaFrom(shared.schema)
        }
        val triageModel = ai.generativeModel(
            modelName = config.cloudModel,
            onDeviceConfig = OnDeviceConfig(
                mode = if (config.engine.strictOnDevice) InferenceMode.ONLY_ON_DEVICE else InferenceMode.PREFER_ON_DEVICE,
            ),
        )
        // Photos stay on the device: this model never falls back to the cloud.
        val photoModel = ai.generativeModel(modelName = config.cloudModel, onDeviceConfig = OnDeviceConfig(mode = InferenceMode.ONLY_ON_DEVICE))
        val deepModel = ai.generativeModel(modelName = config.cloudModel, generationConfig = cloudJson)

        val device = MutableStateFlow(DeviceStatus.UNAVAILABLE)
        runCatching { triageModel.onDeviceExtension }.getOrNull()?.let { ext -> scope.launch { watchDevice(ExtensionModel(ext), device, foreground) } }

        val models = HybridModels(
            device = device,
            strict = config.engine.strictOnDevice,
            onDevice = { prompt -> structured(triageModel, content { text(prompt) }) },
            onDevicePhoto = { prompt, image ->
                val bitmap = decode(image.bytes) ?: error("could not read the picture")
                structured(photoModel, content {
                    image(bitmap)
                    text(prompt)
                })
            },
            cloud = { prompt -> plain(deepModel, prompt) },
        )

        val db = Firebase.firestore
        return Backend(
            models = models,
            config = config,
            device = device,
            isReported = { domain ->
                val snap = runCatching { db.collection("reports").document(sha256(domain)).get().await() }.getOrNull()
                (snap?.getLong("count") ?: 0) >= config.reportMinCount
            },
            report = { domain ->
                // Only the hash of the site name is stored. No message text, no user data.
                db.collection("reports").document(sha256(domain))
                    .set(mapOf("count" to FieldValue.increment(1), "last" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
            },
        )
    }

    private fun source(s: InferenceSource?) = if (s == InferenceSource.ON_DEVICE) Where.ON_DEVICE else Where.IN_CLOUD

    /** Hybrid call with the @Generable schema, so Gemini Nano is held to the answer shape too. */
    private suspend fun structured(model: GenerativeModel, prompt: com.google.firebase.ai.type.Content): ModelCall {
        val r = model.generateObject(Answer.firebaseAISchema(), prompt)
        return ModelCall(r.response.text.orEmpty(), source(r.response.inferenceSource))
    }

    private suspend fun plain(model: GenerativeModel, prompt: String): ModelCall {
        val r = model.generateContent(prompt)
        return ModelCall(r.text.orEmpty(), source(r.inferenceSource))
    }

    private fun decode(bytes: ByteArray): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
