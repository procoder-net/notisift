package io.github.notisift.llm

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Whether the phone's own AI can be used. */
enum class NanoStatus { CHECKING, READY, DOWNLOADING, UNAVAILABLE }

/**
 * Gemini Nano through ML Kit (Android AICore). Phones without it (older ones, emulators, no Google Play services)
 * report [NanoStatus.UNAVAILABLE] and NotiSift keeps using the user's AI key or the built-in rules.
 */
class GeminiNano(private val scope: CoroutineScope) {
    private val _status = MutableStateFlow(NanoStatus.CHECKING)
    val status: StateFlow<NanoStatus> = _status.asStateFlow()

    private val client: GenerativeModel? by lazy {
        try {
            Generation.getClient()
        } catch (e: Exception) {
            Log.i(TAG, "On-phone AI not available: ${e.javaClass.simpleName}")
            null
        } catch (e: LinkageError) {
            Log.i(TAG, "On-phone AI not available: ${e.javaClass.simpleName}")
            null
        }
    }
    private val lock = Mutex()
    @Volatile private var downloading: Job? = null
    /** AICore answers one request at a time per app; overlapping calls (summaries and the second look) wait. */
    private val calls = Mutex()

    val model = OnPhoneModel(::generate)

    /**
     * Checks the model and, when the phone offers it, starts its download (once, over the network AICore picks).
     * Every call checks again, even while a download is being waited on, so the status never sticks at
     * "downloading" once the model is there. A check already running makes this a no-op.
     */
    suspend fun refresh(allowDownload: Boolean) {
        val c = client ?: return set(NanoStatus.UNAVAILABLE)
        if (!lock.tryLock()) return
        try {
            when (c.checkStatus()) {
                FeatureStatus.AVAILABLE -> set(NanoStatus.READY)
                FeatureStatus.DOWNLOADING -> set(NanoStatus.DOWNLOADING)
                FeatureStatus.DOWNLOADABLE -> if (allowDownload) startDownload(c) else set(NanoStatus.UNAVAILABLE)
                else -> set(NanoStatus.UNAVAILABLE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "On-phone AI check failed: ${e.javaClass.simpleName} ${(e as? GenAiException)?.errorCode ?: ""}")
            set(NanoStatus.UNAVAILABLE)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs outside [lock] so later checks still happen. Gives up waiting after [DOWNLOAD_WAIT_MS]; AICore keeps
     * downloading (it often waits for wifi and charging) and the next check sees how it went.
     */
    private fun startDownload(c: GenerativeModel) {
        set(NanoStatus.DOWNLOADING)
        if (downloading?.isActive == true) return
        downloading = scope.launch {
            try {
                withTimeoutOrNull(DOWNLOAD_WAIT_MS) { collectDownload(c) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "On-phone AI download stopped: ${e.javaClass.simpleName} ${(e as? GenAiException)?.errorCode ?: ""}")
            }
        }
    }

    private suspend fun collectDownload(c: GenerativeModel) {
        c.download().collect { d ->
            when (d) {
                DownloadStatus.DownloadCompleted -> set(NanoStatus.READY)
                is DownloadStatus.DownloadFailed -> {
                    Log.i(TAG, "On-phone AI download failed: ${d.e.errorCode}")
                    set(NanoStatus.UNAVAILABLE)
                }
                else -> Unit
            }
        }
    }

    private fun set(s: NanoStatus) {
        if (_status.value != s) Log.i(TAG, "On-phone AI: $s")
        _status.value = s
    }

    private suspend fun generate(system: String, user: String, maxTokens: Int): String {
        val c = client?.takeIf { status.value == NanoStatus.READY } ?: throw LlmException("On-phone AI is not ready")
        // Instructions go first in the one prompt; this ML Kit release has no separate system prompt.
        val request = generateContentRequest(TextPart("$system\n\n$user")) {
            temperature = 0.1f
            topK = 10
            maxOutputTokens = maxTokens.coerceAtMost(MAX_OUTPUT)
        }
        return try {
            calls.withLock { c.generateContent(request) }.candidates.firstOrNull()?.text.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Why Android turned it down (in the background, busy, over quota, prompt too long), without any text.
            Log.i(TAG, "On-phone AI request failed: ${e.javaClass.simpleName} ${(e as? GenAiException)?.errorCode ?: ""} (${system.length + user.length} chars)")
            throw e
        }
    }

    companion object {
        private const val TAG = "NotiSift"
        private const val MAX_OUTPUT = 256
        private const val DOWNLOAD_WAIT_MS = 15L * 60 * 1000
    }
}
