package com.example.localvoice.cleanup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.localvoice.LocalVoiceApp
import com.example.localvoice.data.db.Cleanup
import com.example.localvoice.data.db.Recording
import com.example.localvoice.llm.ChatTemplate
import com.example.localvoice.llm.LlamaContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recordingId = inputData.getString(KEY_RECORDING_ID) ?: return Result.failure()
        val langCode = inputData.getString(KEY_LANGUAGE)?.ifBlank { null } ?: "en"
        val app = applicationContext as LocalVoiceApp
        val recordings = app.container.recordingRepository
        val transcripts = app.container.transcriptDao
        val cleanups = app.container.cleanupDao
        val models = app.container.modelManager

        val rec = recordings.findById(recordingId) ?: return Result.failure()
        val transcript = transcripts.findByRecordingId(recordingId) ?: run {
            Log.w(TAG, "no transcript for $recordingId")
            return Result.failure()
        }
        if (transcript.rawText.isBlank()) {
            Log.i(TAG, "transcript is blank, skipping cleanup")
            recordings.save(rec.copy(status = Recording.STATUS_READY))
            return Result.success()
        }

        if (!models.hasCleanupModel()) {
            Log.i(TAG, "cleanup model missing — downloading")
            recordings.save(rec.copy(status = Recording.STATUS_CLEANING))
            if (!models.ensureCleanupModel()) {
                recordings.save(rec.copy(status = Recording.STATUS_FAILED))
                return Result.failure()
            }
        }
        recordings.save(rec.copy(status = Recording.STATUS_CLEANING))

        return try {
            val cleaned = withContext(Dispatchers.Default) {
                LlamaContext.fromFile(models.cleanupModelFile().absolutePath, ctxSize = 4096).use { llm ->
                    val prompt = ChatTemplate.qwen3(
                        system = buildSystemPrompt(langCode),
                        user = "Clean this transcript:\n\n${transcript.rawText}\n\n/no_think",
                    )
                    Log.i(TAG, "cleanup lang=$langCode raw_chars=${transcript.rawText.length}")
                    llm.generate(prompt, maxTokens = 1024).trim().removeThinkBlock()
                }
            }

            cleanups.upsert(
                Cleanup(
                    recordingId = recordingId,
                    cleanedText = cleaned,
                    createdAt = System.currentTimeMillis(),
                )
            )
            recordings.save(rec.copy(status = Recording.STATUS_READY))
            Log.i(TAG, "cleaned $recordingId: ${cleaned.length} chars")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "cleanup failed for $recordingId", t)
            recordings.save(rec.copy(status = Recording.STATUS_FAILED))
            Result.failure()
        }
    }

    /** Strip any `<think>…</think>` block Qwen3 emits if `/no_think` was ignored. */
    private fun String.removeThinkBlock(): String {
        val end = indexOf("</think>")
        return if (end >= 0) substring(end + "</think>".length).trimStart() else this
    }

    private fun buildSystemPrompt(langCode: String): String {
        val langName = LANGUAGE_NAMES[langCode] ?: langCode
        return """
            You are a strict transcript copy editor for spoken-language voice memos.

            Language: this transcript is in $langName ($langCode). Your output MUST be in $langName. NEVER translate, even if these instructions are in English.

            Task: remove only filler words, false starts, and immediate word repetitions. Do NOT rephrase, reorder, summarize, or rewrite.

            What to remove:
            - Filler interjections: "uh", "um", "er", "ah", "hmm" — and equivalents in $langName.
            - Discourse markers used as verbal tics ("like", "you know", "I mean") only when clearly not meaningful.
            - Immediate word repetitions caused by speech disfluency: "the the cat" → "the cat".

            What to keep:
            - Every meaningful word, in the original order.
            - Slang, contractions, informal phrasing.
            - The speaker's tone and vocabulary.

            Rules:
            - Fix punctuation, capitalization, and obvious word-boundary errors from speech-to-text.
            - If unsure whether something is filler, KEEP it.
            - Output ONLY the cleaned transcript. No preamble. No quotes. No notes. No translation.

            Examples (the rules apply identically in any language):

            Input: I uh I think we should um maybe go to the store like later today
            Output: I think we should maybe go to the store later today.

            Input: yeah so the the meeting is at uh three pm and i need to bring you know the slides
            Output: Yeah, so the meeting is at three pm and I need to bring the slides.

            Input: ok so first we go and then we like turn left at the the intersection
            Output: Ok, so first we go and then we turn left at the intersection.
        """.trimIndent()
    }

    companion object {
        private const val TAG = "CleanupWorker"
        const val KEY_RECORDING_ID = "recording_id"
        const val KEY_LANGUAGE = "language"

        private val LANGUAGE_NAMES = mapOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
            "ru" to "Russian", "uk" to "Ukrainian", "tr" to "Turkish", "ar" to "Arabic",
            "hi" to "Hindi", "bn" to "Bengali", "ja" to "Japanese", "ko" to "Korean",
            "zh" to "Chinese", "vi" to "Vietnamese", "th" to "Thai", "id" to "Indonesian",
            "sv" to "Swedish", "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish",
            "cs" to "Czech", "el" to "Greek", "he" to "Hebrew", "ro" to "Romanian",
            "hu" to "Hungarian", "fa" to "Persian", "ta" to "Tamil", "te" to "Telugu",
        )

        fun enqueue(context: Context, recordingId: String, languageCode: String) {
            val request = OneTimeWorkRequestBuilder<CleanupWorker>()
                .setInputData(workDataOf(
                    KEY_RECORDING_ID to recordingId,
                    KEY_LANGUAGE to languageCode,
                ))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "cleanup-$recordingId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
