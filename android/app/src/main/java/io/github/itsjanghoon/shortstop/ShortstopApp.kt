package io.github.itsjanghoon.shortstop

import android.app.Application
import android.util.Log
import io.github.itsjanghoon.shortstop.cloud.Backend
import io.github.itsjanghoon.shortstop.cloud.FirebaseBackend
import io.github.itsjanghoon.shortstop.data.HistoryDb
import io.github.itsjanghoon.shortstop.engine.Engine
import io.github.itsjanghoon.shortstop.engine.Shared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class ShortstopApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var shared: Shared
        private set
    lateinit var engine: Engine
        private set
    val history: HistoryDb by lazy { HistoryDb.open(this) }

    /** Firebase connects in the background; checks run with rules until it is ready. Null means rules-only mode. */
    lateinit var backend: Deferred<Backend?>
        private set

    override fun onCreate() {
        super.onCreate()
        shared = Shared.load { name -> assets.open("shared/$name").bufferedReader().use { it.readText() } }
        engine = Engine(shared)
        backend = scope.async {
            runCatching { FirebaseBackend.connect(this@ShortstopApp, shared, scope) }
                .onFailure { Log.w("Shortstop", "Firebase not available, rules only", it) }
                .getOrNull()
        }
    }
}
