package com.example.localvoice.data

import android.content.Context
import com.example.localvoice.data.db.AppDatabase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)

    val recordingRepository = RecordingRepository(
        recordingDao = database.recordingDao(),
        appContext = appContext,
    )
    val transcriptDao = database.transcriptDao()
    val cleanupDao = database.cleanupDao()
    val modelManager = ModelManager(appContext)
}
