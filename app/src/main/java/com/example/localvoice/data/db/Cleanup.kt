package com.example.localvoice.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cleanups")
data class Cleanup(
    @PrimaryKey val recordingId: String,
    val cleanedText: String,
    val createdAt: Long,
)
