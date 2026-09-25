package com.knowyourcase.notice

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

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
    val serviceStatus: String = "PENDING",
    val fetchedState: String = "FETCHING",
    val lastError: String = "",
    val scannedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface NoticeDao {
    @Insert suspend fun insert(notice: NoticeEntity): Long
    @Update suspend fun update(notice: NoticeEntity)
    @Delete suspend fun delete(notice: NoticeEntity)
    @Query("SELECT * FROM notices ORDER BY CASE WHEN nextHearing='' THEN 1 ELSE 0 END, nextHearing ASC, scannedAt DESC")
    suspend fun all(): List<NoticeEntity>
    @Query("SELECT * FROM notices WHERE id=:id LIMIT 1")
    suspend fun byId(id: Long): NoticeEntity?
    @Query("SELECT * FROM notices WHERE cnr=:cnr ORDER BY scannedAt DESC")
    suspend fun byCnr(cnr: String): List<NoticeEntity>

    @Query("UPDATE notices SET fetchedState = 'QUEUED' WHERE fetchedState = 'FETCHING'")
    suspend fun recoverInterruptedFetches()
}

@Database(entities = [NoticeEntity::class], version = 4, exportSchema = false)
abstract class NoticeDatabase : RoomDatabase() {
    abstract fun notices(): NoticeDao
    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Older debug build normalized NOT_SERVED to PENDING.
                db.execSQL("UPDATE notices SET serviceStatus = 'PENDING' WHERE serviceStatus = 'NOT_SERVED'")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE notices SET serviceStatus = 'NOT_SERVED' WHERE serviceStatus != 'SERVED'")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Two-state debug builds used NOT_SERVED for all incomplete work.
                db.execSQL("UPDATE notices SET serviceStatus = 'PENDING' WHERE serviceStatus = 'NOT_SERVED'")
            }
        }

        @Volatile private var INSTANCE: NoticeDatabase? = null
        fun get(context: Context): NoticeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoticeDatabase::class.java,
                    "notice-tracker.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
