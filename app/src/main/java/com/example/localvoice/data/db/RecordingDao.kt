package com.example.localvoice.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun findById(id: String): Recording?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)
}
