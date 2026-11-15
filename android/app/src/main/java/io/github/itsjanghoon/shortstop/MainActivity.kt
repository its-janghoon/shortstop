package io.github.itsjanghoon.shortstop

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.itsjanghoon.shortstop.engine.Example
import io.github.itsjanghoon.shortstop.engine.Image
import io.github.itsjanghoon.shortstop.engine.Verdict
import io.github.itsjanghoon.shortstop.ui.MainScreen
import io.github.itsjanghoon.shortstop.ui.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch { readImage(uri)?.let(vm::setImage) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val actions = object : UiActions {
            override fun draft(text: String) = vm.draft(text)
            override fun pickImage() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            override fun clearImage() = vm.setImage(null)
            override fun example(example: Example) = vm.example(example)
            override fun check() = vm.check()
            override fun again() = vm.again()
            override fun act() = this@MainActivity.act()
            override fun sendToFamily() = this@MainActivity.sendToFamily()
            override fun report() = vm.report()
            override fun openHistory() = vm.openHistory()
            override fun clearHistory() = vm.clearHistory()
            override fun back() = vm.back()
        }
        setContent {
            val state = vm.state.collectAsStateWithLifecycle()
            MainScreen(state.value, actions)
        }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A1: "Share" from a messages app (text) or a screenshot (image). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (intent.type?.startsWith("image/") == true && uri != null) {
            lifecycleScope.launch { vm.shared(text, readImage(uri)) }
        } else if (!text.isNullOrBlank()) {
            vm.shared(text, null)
        }
    }

    private suspend fun readImage(uri: Uri): Image? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = contentResolver.getType(uri) ?: "image/png"
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_IMAGE_BYTES) null else Image(bytes, mime)
            }
        }.getOrNull()
    }

    /** A3: the one action button. Scam or careful: open the dialer empty, so the user dials the number on their card. */
    private fun act() {
        val r = vm.state.value.result ?: return
        if (r.verdict == Verdict.SAFE) {
            vm.again()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Call the company using the number on their card or official website.", Toast.LENGTH_LONG).show()
        }
    }

    /** A4: share the masked message and the verdict. */
    private fun sendToFamily() {
        val r = vm.state.value.result ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Shortstop check")
            putExtra(Intent.EXTRA_TEXT, familyText(r))
        }
        startActivity(Intent.createChooser(send, "Send to family"))
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
    }
}
