package com.example.localvoice.audio

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.localvoice.LocalVoiceApp
import com.example.localvoice.MainActivity
import com.example.localvoice.R
import com.example.localvoice.data.db.Recording
import com.example.localvoice.transcribe.TranscribeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.concurrent.thread

class RecordingService : LifecycleService() {

    private var captureThread: Thread? = null
    @Volatile private var capturing = false
    private var captureJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startRecording() {
        if (capturing) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        startInForeground()

        val id = UUID.randomUUID().toString()
        val repository = (application as LocalVoiceApp).container.recordingRepository
        val outputFile = repository.newRecordingFile(id)
        val startedAt = System.currentTimeMillis()

        capturing = true
        _state.value = RecordingState.Recording(id, startedAt)

        captureThread = thread(name = "RecordingService-capture", isDaemon = true) {
            captureLoop(outputFile.absolutePath)
            val durationMs = System.currentTimeMillis() - startedAt
            captureJob = lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    repository.save(
                        Recording(
                            id = id,
                            filePath = outputFile.absolutePath,
                            durationMs = durationMs,
                            createdAt = startedAt,
                            status = Recording.STATUS_RECORDED,
                        )
                    )
                }
                TranscribeWorker.enqueue(applicationContext, id)
                _state.value = RecordingState.Idle
                stopSelf()
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureLoop(outputPath: String) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            capturing = false
            return
        }

        val buffer = ShortArray(minBuf)
        WavWriter(java.io.File(outputPath), SAMPLE_RATE, channels = 1).use { writer ->
            record.startRecording()
            try {
                while (capturing) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) writer.write(buffer, read)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
    }

    private fun stopRecording() {
        capturing = false
        // captureThread completes the file + DB row + state reset, then calls stopSelf().
    }

    private fun startInForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = NotificationCompat.Builder(this, LocalVoiceApp.CHANNEL_ID_RECORDING)
            .setContentTitle("Recording…")
            .setContentText("Tap to return to localVoice")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        capturing = false
        captureThread?.join(500)
        captureJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.localvoice.action.START"
        const val ACTION_STOP = "com.example.localvoice.action.STOP"
        private const val NOTIFICATION_ID = 4242
        private const val SAMPLE_RATE = 16_000

        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Recording(val id: String, val startedAt: Long) : RecordingState
}
