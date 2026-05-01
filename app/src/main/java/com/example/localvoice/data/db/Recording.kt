package com.example.localvoice.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String,
    val filePath: String,
    val durationMs: Long,
    val createdAt: Long,
    val status: String,
) {
    companion object {
        const val STATUS_RECORDED = "RECORDED"
        const val STATUS_TRANSCRIBING = "TRANSCRIBING"
        const val STATUS_CLEANING = "CLEANING"
        const val STATUS_READY = "READY"
        const val STATUS_FAILED = "FAILED"
    }
}
