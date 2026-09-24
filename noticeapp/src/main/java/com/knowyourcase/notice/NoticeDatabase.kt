package com.knowyourcase.notice

import android.content.Context
import androidx.room.*

@Entity(tableName = "notices")
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cnr: String,
    val caseNumber: String = "",
    val caseType: String = "",
    val caseTitle: String = "",
    val courtName: String = "",
    val judge: String = "",
    val petitioner: String = "",
    val respondent: String = "",
    val petitionerAdvocate: String = "",
    val respondentAdvocate: String = "",
    val nextHearing: String = "",
    val caseStage: String = "",
    val processServer: String = "",
    val serviceStatus: String = "NOT_SERVED",
    val fetchedState: String = "FETCHING",
    val lastError: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface NoticeDao {
    @Insert suspend fun insert(notice: NoticeEntity): Long
    @Update suspend fun update(notice: NoticeEntity)
    @Query("SELECT * FROM notices ORDER BY CASE WHEN nextHearing='' THEN 1 ELSE 0 END, nextHearing ASC, scannedAt DESC")
    suspend fun all(): List<NoticeEntity>
    @Query("SELECT * FROM notices WHERE id=:id LIMIT 1")
    suspend fun byId(id: Long): NoticeEntity?
    @Query("SELECT * FROM notices WHERE cnr=:cnr ORDER BY scannedAt DESC")
    suspend fun byCnr(cnr: String): List<NoticeEntity>
}

@Database(entities = [NoticeEntity::class], version = 1, exportSchema = false)
abstract class NoticeDatabase : RoomDatabase() {
    abstract fun notices(): NoticeDao
    companion object {
        @Volatile private var INSTANCE: NoticeDatabase? = null
        fun get(context: Context): NoticeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoticeDatabase::class.java,
                    "notice-tracker.db"
                ).build().also { INSTANCE = it }
            }
    }
}
