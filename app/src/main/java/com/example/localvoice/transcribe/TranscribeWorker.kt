package com.example.localvoice.transcribe

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.localvoice.LocalVoiceApp
import com.example.localvoice.cleanup.CleanupWorker
import com.example.localvoice.data.db.Recording
import com.example.localvoice.data.db.Transcript
import com.example.localvoice.whisper.WavReader
import com.example.localvoice.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TranscribeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val app = applicationContext as LocalVoiceApp
        val recordings = app.container.recordingRepository
        val transcripts = app.container.transcriptDao
        val models = app.container.modelManager

        val rec = recordings.findById(recordingId) ?: run {
            Log.e(TAG, "no recording for id=$recordingId")
            return Result.failure()
        }

        if (!models.hasWhisperModel()) {
            Log.i(TAG, "model missing — downloading")
            recordings.save(rec.copy(status = Recording.STATUS_TRANSCRIBING))
            if (!models.ensureWhisperModel()) {
                recordings.save(rec.copy(status = Recording.STATUS_FAILED))
                return Result.failure()
            }
        }

        recordings.save(rec.copy(status = Recording.STATUS_TRANSCRIBING))

        return try {
            val (text, lang) = withContext(Dispatchers.Default) {
                val samples = WavReader.read16kMono(File(rec.filePath))
                WhisperContext.fromFile(models.whisperModelFile().absolutePath).use { ctx ->
                    val t = ctx.transcribe(samples, language = "auto", nThreads = pickThreadCount())
                    t to ctx.detectedLanguage()
                }
            }
            transcripts.upsert(
                Transcript(
                    recordingId = recordingId,
                    rawText = text.trim(),
                    createdAt = System.currentTimeMillis(),
                )
            )
            Log.i(TAG, "transcribed $recordingId: ${text.length} chars (lang=$lang)")
            // Chain cleanup; CleanupWorker will set the recording status to READY when done.
            CleanupWorker.enqueue(applicationContext, recordingId, lang.ifBlank { "en" })
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed for $recordingId", t)
            recordings.save(rec.copy(status = Recording.STATUS_FAILED))
            Result.failure()
        }
    }

    private fun pickThreadCount(): Int {
        // big.LITTLE: cap so we don't overschedule onto efficiency cores.
        return Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
    }

    companion object {
        private const val TAG = "TranscribeWorker"
        const val KEY_RECORDING_ID = "recording_id"

        fun enqueue(context: Context, recordingId: String) {
            val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "transcribe-$recordingId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
