package com.knowyourcase.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class HistoryEntity(
    @PrimaryKey val cnr: String,
    val caseTitle: String,
    val searchedAt: Long = System.currentTimeMillis()
)
