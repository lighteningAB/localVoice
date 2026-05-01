package com.example.localvoice.data

import android.content.Context
import com.example.localvoice.data.db.Recording
import com.example.localvoice.data.db.RecordingDao
import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val appContext: Context,
) {
    fun observeAll(): Flow<List<Recording>> = recordingDao.observeAll()

    suspend fun findById(id: String): Recording? = recordingDao.findById(id)

    suspend fun save(recording: Recording) = recordingDao.upsert(recording)

    suspend fun delete(id: String) {
        val rec = recordingDao.findById(id) ?: return
        runCatching { File(rec.filePath).delete() }
        recordingDao.deleteById(id)
    }

    fun newRecordingFile(id: String): File {
        val dir = File(appContext.filesDir, "recordings").apply { mkdirs() }
        return File(dir, "$id.wav")
    }
}
