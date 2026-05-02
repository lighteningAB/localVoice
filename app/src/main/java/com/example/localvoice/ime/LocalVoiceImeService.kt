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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class LocalVoiceImeService : InputMethodService() {

    private enum class State { IDLE, LISTENING, TRANSCRIBING }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Serializes whisper transcribe calls (warmup + real). whisper_full is not reentrant.
    private val transcribeMutex = Mutex()

    // Lazily-loaded whisper context (kept alive across utterances).
    private var whisper: WhisperContext? = null

    // Set once we've successfully run a dummy inference; prevents repeat warmups
    // across input-view rebuilds (which fire on every text-field focus change).
    @Volatile private var warmedUp = false

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

    override fun onCreate() {
        super.onCreate()
        // Warm up as early as possible — onCreate runs when the system first
        // binds the IME, before any input view is shown. By the time the user
        // taps the mic, the encoder + thread pool are already hot.
        warmUpWhisperOnce()
    }

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
            // Cycle directly to next IME — no dialog. Matches Gboard's globe.
            // (Note: when the host is a home-screen widget, switching IMEs may
            // dismiss the field; that's a launcher-side limitation, not ours.)
            if (!switchToNextInputMethod(false)) {
                getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            }
        }
        switchKeyboardButton?.setOnLongClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            true
        }

        applyIdleUi()
        // Defensive: if onCreate's warmup got skipped (model not yet downloaded),
        // try again on view creation in case the user just finished the download.
        warmUpWhisperOnce()
        return view
    }

    private fun warmUpWhisperOnce() {
        if (warmedUp) return
        val modelManager = (application as LocalVoiceApp).container.modelManager
        if (!modelManager.hasWhisperModel()) return
        warmedUp = true // mark optimistically — failure logs but won't loop
        serviceScope.launch {
            try {
                transcribeMutex.withLock {
                    val warmStartNs = System.nanoTime()
                    val ctx = ensureWhisper()
                    // 4 s of silence at 16 kHz, audio_ctx ≈ typical short utterance.
                    // Triggers encoder allocation + thread-pool spinup so the user's
                    // first real tap hits a hot path instead of paying ~3 s of init.
                    val dummy = FloatArray(WARMUP_SAMPLES)
                    // Tiny audio_ctx — we only need to trigger init, not process audio.
                    val warmAudioCtx = ((WARMUP_SAMPLES / 320) + 16).coerceAtMost(1500)
                    ctx.transcribe(
                        dummy,
                        language = "en",
                        nThreads = 4,
                        audioCtx = warmAudioCtx,
                    )
                    val ms = (System.nanoTime() - warmStartNs) / 1_000_000
                    Log.i(TAG, "warmup completed: ${ms}ms (audio_ctx=$warmAudioCtx)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "warmup failed", t)
            }
        }
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

                val mutexAcquireStartNs = System.nanoTime()
                val text = transcribeMutex.withLock {
                    val mutexWaitMs = (System.nanoTime() - mutexAcquireStartNs) / 1_000_000
                    if (mutexWaitMs > 5) Log.i(TAG, "waited ${mutexWaitMs}ms for warmup to finish")

                    val whisperPreloaded = whisper != null
                    val loadStartNs = System.nanoTime()
                    val ctx = if (samples.isEmpty()) null else ensureWhisper()
                    val loadMs = (System.nanoTime() - loadStartNs) / 1_000_000
                    if (!whisperPreloaded) Log.i(TAG, "whisper model load: ${loadMs}ms")

                    // whisper.cpp: 1 audio_ctx token = 320 samples @ 16 kHz (= 20 ms).
                    // Cap at 1500 (= full 30 s = whisper default). Add a small tail buffer
                    // so we don't truncate the last word.
                    val audioCtx = ((totalLen / 320) + 32).coerceAtMost(1500)

                    val transcribeStartNs = System.nanoTime()
                    val out = ctx?.transcribe(
                        samples,
                        language = "auto",
                        nThreads = 4,
                        audioCtx = audioCtx,
                    )?.trim().orEmpty()
                    val transcribeMs = (System.nanoTime() - transcribeStartNs) / 1_000_000
                    val rt = if (audioSec > 0) transcribeMs / 1000.0 / audioSec else 0.0
                    Log.i(
                        TAG,
                        "transcribe: ${transcribeMs}ms for ${"%.2f".format(audioSec)}s audio " +
                            "(${"%.2fx".format(rt)} realtime, audio_ctx=$audioCtx), ${out.length} chars",
                    )
                    out
                }

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
        // 0.5 s of silence — enough to trigger thread-pool init, ggml backend
        // setup, and decoder KV-cache allocation. Keep it small so the user
        // doesn't wait if they tap the mic immediately after switching keyboards.
        private const val WARMUP_SAMPLES = SAMPLE_RATE / 2
    }
}
