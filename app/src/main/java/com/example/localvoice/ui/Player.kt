package com.example.localvoice.ui

import android.media.MediaPlayer
import java.io.IOException

class Player {
    private var mediaPlayer: MediaPlayer? = null
    private var currentId: String? = null

    fun play(id: String, filePath: String, onCompletion: () -> Unit) {
        stop()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnCompletionListener {
                    currentId = null
                    onCompletion()
                }
                prepare()
                start()
            }
            currentId = id
        } catch (e: IOException) {
            mediaPlayer?.release()
            mediaPlayer = null
            currentId = null
        }
    }

    fun stop() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        currentId = null
    }

    fun isPlaying(id: String): Boolean = currentId == id && mediaPlayer?.isPlaying == true
}
