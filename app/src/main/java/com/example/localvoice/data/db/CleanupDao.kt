package com.example.localvoice.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanupDao {

    @Query("SELECT * FROM cleanups")
    fun observeAll(): Flow<List<Cleanup>>

    @Query("SELECT * FROM cleanups WHERE recordingId = :id")
    suspend fun findByRecordingId(id: String): Cleanup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cleanup: Cleanup)

    @Query("DELETE FROM cleanups WHERE recordingId = :id")
    suspend fun deleteByRecordingId(id: String)
}
