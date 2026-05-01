package com.example.localvoice.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts")
    fun observeAll(): Flow<List<Transcript>>

    @Query("SELECT * FROM transcripts WHERE recordingId = :id")
    suspend fun findByRecordingId(id: String): Transcript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcript: Transcript)

    @Query("DELETE FROM transcripts WHERE recordingId = :id")
    suspend fun deleteByRecordingId(id: String)
}
