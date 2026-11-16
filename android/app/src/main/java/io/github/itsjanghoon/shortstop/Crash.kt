package io.github.itsjanghoon.shortstop

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash on the phone (filesDir/last-crash.txt), so it survives when
 * nobody was watching logcat. Shown once on the next launch; the user can copy it or dismiss it.
 */
object Crash {
    const val TAG = "ShortstopCrash"
    const val FILE = "last-crash.txt"

    fun file(context: Context) = File(context.filesDir, FILE)

    fun text(where: String, e: Throwable): String = buildString {
        append("Shortstop crash, ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android SDK ").append(Build.VERSION.SDK_INT).append('\n')
        append("Where: ").append(where).append('\n')
        append(e.stackTraceToString())
    }

    /** Write the trace. Never throws: this runs while the app is already failing. */
    fun record(context: Context, where: String, e: Throwable) {
        runCatching { file(context).writeText(text(where, e)) }
        runCatching { Log.e(TAG, "Recorded crash ($where)", e) }
    }

    /** Record uncaught exceptions on any thread, then hand them to the previous handler (which closes the app). */
    fun install(context: Context) {
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            record(app, "uncaught on thread ${thread.name}", e)
            previous?.uncaughtException(thread, e)
        }
    }

    fun read(context: Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** The first lines shown on the card: the exception and where it was thrown. */
    fun summary(text: String, lines: Int = 6): String =
        text.lines().filter { it.isNotBlank() }.drop(3).take(lines).joinToString("\n") { it.trim() }
}
