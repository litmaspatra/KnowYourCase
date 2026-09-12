package com.knowyourcase.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 30")
    fun getAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 5")
    fun getRecent(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}
