package com.example.localvoice.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class Transcript(
    @PrimaryKey val recordingId: String,
    val rawText: String,
    val createdAt: Long,
)
