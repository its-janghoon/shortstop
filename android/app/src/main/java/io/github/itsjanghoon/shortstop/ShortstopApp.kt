package io.github.itsjanghoon.shortstop

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.itsjanghoon.shortstop.cloud.Backend
import io.github.itsjanghoon.shortstop.cloud.FirebaseBackend
import io.github.itsjanghoon.shortstop.data.HistoryDb
import io.github.itsjanghoon.shortstop.engine.Engine
import io.github.itsjanghoon.shortstop.engine.Shared
import io.github.itsjanghoon.shortstop.engine.Words
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

open class ShortstopApp : Application() {
    private val _problem = MutableStateFlow<String?>(null)

    /** A background job failed. Shown as an error line instead of closing the app. */
    val problem: StateFlow<String?> = _problem

    /** Records a failed background job and shows it, instead of closing the app. */
    val handler = CoroutineExceptionHandler { _, e ->
        Crash.record(this, "background job", e)
        _problem.value = "Something went wrong in the background: ${e.message ?: e.javaClass.simpleName}"
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
    lateinit var shared: Shared
        private set
    lateinit var engine: Engine
        private set
    val history: HistoryDb by lazy { HistoryDb.open(this) }

    /** Text of the crash from the last run, if any (filesDir/last-crash.txt). */
    var lastCrash: String? = null
        private set

    /** Firebase connects in the background; checks run with rules until it is ready. Null means rules-only mode. */
    lateinit var backend: Deferred<Backend?>
        private set

    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        lastCrash = Crash.read(this)?.also { Log.e(Crash.TAG, "Shortstop closed last time:\n$it") }
        shared = Shared.load { name -> assets.open("shared/$name").bufferedReader().use { it.readText() } }
        engine = Engine(shared)
        // Read the 64k-word list off the main thread so the first check does not wait for it.
        scope.launch(Dispatchers.IO) { Words.set }
        backend = scope.async {
            runCatching { connect() }
                .onFailure { Log.w("Shortstop", "Firebase not available, rules only", it) }
                .getOrNull()
        }
    }

    /** True while any activity of the app is started. ML Kit GenAI only runs in the foreground. */
    open fun foreground(): Flow<Boolean> =
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }

    protected open suspend fun connect(): Backend? = FirebaseBackend.connect(this, shared, scope, foreground())

    fun dismissCrash() {
        lastCrash = null
        Crash.clear(this)
    }

    fun clearProblem() {
        _problem.value = null
    }
}
