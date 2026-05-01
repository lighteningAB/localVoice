package com.example.localvoice

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.localvoice.data.AppContainer

class LocalVoiceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createRecordingChannel()
    }

    private fun createRecordingChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_RECORDING,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a voice memo is being recorded"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID_RECORDING = "recording"
    }
}
