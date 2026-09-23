package com.movementid.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MovementDao {

    @Query("SELECT * FROM movement_entries ORDER BY timestamp DESC")
    fun getAll(): Flow<List<MovementEntry>>

    @Query(
        """
        SELECT * FROM movement_entries
        WHERE title LIKE '%' || :query || '%'
           OR caliber LIKE '%' || :query || '%'
           OR movementFamily LIKE '%' || :query || '%'
           OR brandGuess LIKE '%' || :query || '%'
           OR userNotes LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        """
    )
    fun search(query: String): Flow<List<MovementEntry>>

    @Query("SELECT * FROM movement_entries WHERE id = :id")
    suspend fun getById(id: Long): MovementEntry?

    /** Snapshot for export — a one-shot list rather than an observing Flow. */
    @Query("SELECT * FROM movement_entries ORDER BY timestamp ASC")
    suspend fun getAllOnce(): List<MovementEntry>

    /** Used by restore to skip entries already present. */
    @Query("SELECT timestamp FROM movement_entries")
    suspend fun allTimestamps(): List<Long>

    @Insert
    suspend fun insert(entry: MovementEntry): Long

    @Insert
    suspend fun insertAll(entries: List<MovementEntry>)

    @Query("DELETE FROM movement_entries")
    suspend fun deleteAll()

    @Update
    suspend fun update(entry: MovementEntry)

    @Delete
    suspend fun delete(entry: MovementEntry)
}
