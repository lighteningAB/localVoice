package com.example.localvoice.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) :
        ModelDownloadState
    data object Ready : ModelDownloadState
    data class Failed(val message: String) : ModelDownloadState
}

class ModelManager(private val appContext: Context) {

    private val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }
    private val whisperMutex = Mutex()
    private val cleanupMutex = Mutex()

    private val _whisperState = MutableStateFlow<ModelDownloadState>(
        if (hasWhisperModel()) ModelDownloadState.Ready else ModelDownloadState.Idle
    )
    val whisperState: StateFlow<ModelDownloadState> = _whisperState.asStateFlow()

    private val _cleanupState = MutableStateFlow<ModelDownloadState>(
        if (hasCleanupModel()) ModelDownloadState.Ready else ModelDownloadState.Idle
    )
    val cleanupState: StateFlow<ModelDownloadState> = _cleanupState.asStateFlow()

    fun whisperModelFile(): File = File(modelsDir, WHISPER_FILE)
    fun cleanupModelFile(): File = File(modelsDir, CLEANUP_FILE)

    fun hasWhisperModel(): Boolean = whisperModelFile().let { it.exists() && it.length() > 50L * 1024 * 1024 }
    fun hasCleanupModel(): Boolean = cleanupModelFile().let { it.exists() && it.length() > 400L * 1024 * 1024 }

    suspend fun ensureWhisperModel(): Boolean = whisperMutex.withLock {
        ensureGeneric(_whisperState, ::hasWhisperModel, whisperModelFile(), WHISPER_URL)
    }

    suspend fun ensureCleanupModel(): Boolean = cleanupMutex.withLock {
        ensureGeneric(_cleanupState, ::hasCleanupModel, cleanupModelFile(), CLEANUP_URL)
    }

    private suspend fun ensureGeneric(
        state: MutableStateFlow<ModelDownloadState>,
        check: () -> Boolean,
        target: File,
        url: String,
    ): Boolean {
        if (check()) {
            state.value = ModelDownloadState.Ready
            return true
        }
        return try {
            downloadInto(target, url, state)
            state.value = ModelDownloadState.Ready
            true
        } catch (t: Throwable) {
            state.value = ModelDownloadState.Failed(t.message ?: "download failed")
            false
        }
    }

    private suspend fun downloadInto(
        target: File,
        url: String,
        state: MutableStateFlow<ModelDownloadState>,
    ) = withContext(Dispatchers.IO) {
        val partial = File(target.parentFile, "${target.name}.part")
        runCatching { partial.delete() }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            state.value = ModelDownloadState.Downloading(0f, 0L, total)

            partial.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            val pct = if (total > 0) downloaded.toFloat() / total else 0f
                            state.value = ModelDownloadState.Downloading(pct, downloaded, total)
                            lastEmit = now
                        }
                    }
                }
            }
            if (!partial.renameTo(target)) {
                throw RuntimeException("Failed to finalize download")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val WHISPER_FILE = "ggml-small-q5_1.bin"
        private const val WHISPER_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

        private const val CLEANUP_FILE = "Qwen3-1.7B-Q4_K_M.gguf"
        private const val CLEANUP_URL =
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    }
}
