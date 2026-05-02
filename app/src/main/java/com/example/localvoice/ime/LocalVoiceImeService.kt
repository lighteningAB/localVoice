package com.example.localvoice.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.localvoice.LocalVoiceApp
import com.example.localvoice.R
import com.example.localvoice.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class LocalVoiceImeService : InputMethodService() {

    private enum class State { IDLE, LISTENING, TRANSCRIBING }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Lazily-loaded whisper context (kept alive across utterances).
    private var whisper: WhisperContext? = null

    // View references — rebuilt each onCreateInputView.
    private var rootView: View? = null
    private var micButton: ImageButton? = null
    private var spinner: ProgressBar? = null
    private var statusText: TextView? = null
    private var switchKeyboardButton: ImageButton? = null

    // Per-utterance state.
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing = false
    private val chunks = ArrayList<ShortArray>()
    private var timeoutJob: Job? = null
    private var listeningStartedNs: Long = 0L
    private var stopTappedNs: Long = 0L

    private var state: State = State.IDLE

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_view, null)
        rootView = view
        micButton = view.findViewById(R.id.ime_mic_button)
        spinner = view.findViewById(R.id.ime_spinner)
        statusText = view.findViewById(R.id.ime_status)
        switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard)

        micButton?.setOnClickListener {
            when (state) {
                State.IDLE -> startListening()
                State.LISTENING -> stopListeningAndTranscribe()
                State.TRANSCRIBING -> { /* ignore taps while transcribing */ }
            }
        }

        switchKeyboardButton?.setOnClickListener {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }

        applyIdleUi()
        return view
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // If the user navigates away mid-utterance, abort capture cleanly.
        if (state == State.LISTENING) {
            abortListening()
        }
        super.onFinishInputView(finishingInput)
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setStatus("Open localVoice and grant microphone access")
            return
        }

        val modelManager = (application as LocalVoiceApp).container.modelManager
        if (!modelManager.hasWhisperModel()) {
            setStatus("Model not downloaded — open localVoice")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (t: Throwable) {
            setStatus("Microphone unavailable")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            setStatus("Microphone unavailable")
            return
        }

        synchronized(chunks) { chunks.clear() }
        audioRecord = record
        capturing = true
        state = State.LISTENING
        listeningStartedNs = System.nanoTime()
        Log.i(TAG, "startListening tapped")
        applyListeningUi()

        val buffer = ShortArray(minBuf)
        captureThread = thread(name = "LocalVoiceIme-capture", isDaemon = true) {
            try {
                record.startRecording()
            } catch (t: Throwable) {
                capturing = false
                mainHandler.post {
                    setStatus("Microphone failed to start")
                    releaseAudioRecord()
                    state = State.IDLE
                    applyIdleUi()
                }
                return@thread
            }

            try {
                while (capturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val copy = buffer.copyOf(read)
                        synchronized(chunks) { chunks.add(copy) }
                    }
                }
            } finally {
                runCatching { record.stop() }
            }
        }

        timeoutJob = serviceScope.launch {
            delay(MAX_LISTEN_MS)
            mainHandler.post {
                if (state == State.LISTENING) {
                    stopListeningAndTranscribe()
                }
            }
        }
    }

    private fun stopListeningAndTranscribe() {
        if (state != State.LISTENING) return

        stopTappedNs = System.nanoTime()
        val captureMs = (stopTappedNs - listeningStartedNs) / 1_000_000
        Log.i(TAG, "stopTapped after ${captureMs}ms of listening")

        capturing = false
        captureThread?.join(500)
        captureThread = null
        timeoutJob?.cancel()
        timeoutJob = null
        releaseAudioRecord()

        state = State.TRANSCRIBING
        applyTranscribingUi()

        // Capture the InputConnection now — it can change between input fields.
        val ic: InputConnection? = currentInputConnection

        val captured: List<ShortArray> = synchronized(chunks) { chunks.toList() }

        serviceScope.launch {
            try {
                val totalLen = captured.sumOf { it.size }
                val audioSec = totalLen.toDouble() / SAMPLE_RATE
                val samples = FloatArray(totalLen)
                var idx = 0
                for (c in captured) {
                    for (s in c) {
                        samples[idx++] = s.toFloat() / 32768f
                    }
                }

                val whisperPreloaded = whisper != null
                val loadStartNs = System.nanoTime()
                val ctx = if (samples.isEmpty()) null else ensureWhisper()
                val loadMs = (System.nanoTime() - loadStartNs) / 1_000_000
                if (!whisperPreloaded) Log.i(TAG, "whisper model load: ${loadMs}ms")

                val transcribeStartNs = System.nanoTime()
                val text = ctx?.transcribe(samples, language = "auto", nThreads = 4)?.trim().orEmpty()
                val transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000
                val rt = if (audioSec > 0) transcribeMs / 1000.0 / audioSec else 0.0
                Log.i(
                    TAG,
                    "transcribe: ${transcribeMs}ms for ${"%.2f".format(audioSec)}s audio " +
                        "(${"%.2fx".format(rt)} realtime), ${text.length} chars",
                )

                withContext(Dispatchers.Main) {
                    val commitStartNs = System.nanoTime()
                    if (text.isNotEmpty()) {
                        ic?.commitText("$text ", 1)
                    }
                    val commitMs = (System.nanoTime() - commitStartNs) / 1_000_000
                    val totalMs = (System.nanoTime() - stopTappedNs) / 1_000_000
                    Log.i(TAG, "commitText: ${commitMs}ms; total stop→commit: ${totalMs}ms")
                    state = State.IDLE
                    applyIdleUi()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "transcription failed", t)
                withContext(Dispatchers.Main) {
                    setStatus("Transcription failed")
                    state = State.IDLE
                    applyIdleUi(keepStatus = true)
                }
            }
        }
    }

    private fun abortListening() {
        capturing = false
        captureThread?.join(200)
        captureThread = null
        timeoutJob?.cancel()
        timeoutJob = null
        releaseAudioRecord()
        synchronized(chunks) { chunks.clear() }
        state = State.IDLE
        applyIdleUi()
    }

    override fun onDestroy() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
        timeoutJob?.cancel()
        timeoutJob = null
        releaseAudioRecord()
        runCatching { whisper?.close() }
        whisper = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- helpers ----------

    private fun releaseAudioRecord() {
        val rec = audioRecord ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
        audioRecord = null
    }

    private fun ensureWhisper(): WhisperContext {
        whisper?.let { return it }
        val modelFile = (application as LocalVoiceApp).container.modelManager.whisperModelFile()
        val ctx = WhisperContext.fromFile(modelFile.absolutePath)
        whisper = ctx
        return ctx
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    private fun applyIdleUi(keepStatus: Boolean = false) {
        micButton?.visibility = View.VISIBLE
        micButton?.setBackgroundResource(R.drawable.bg_ime_mic_idle)
        spinner?.visibility = View.GONE
        if (!keepStatus) setStatus("Tap to dictate")
    }

    private fun applyListeningUi() {
        micButton?.visibility = View.VISIBLE
        micButton?.setBackgroundResource(R.drawable.bg_ime_mic_active)
        spinner?.visibility = View.GONE
        setStatus("Listening…")
    }

    private fun applyTranscribingUi() {
        micButton?.visibility = View.GONE
        spinner?.visibility = View.VISIBLE
        setStatus("Transcribing…")
    }

    companion object {
        private const val TAG = "LocalVoiceIme"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_LISTEN_MS = 60_000L
    }
}
