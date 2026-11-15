package io.github.itsjanghoon.shortstop.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** One past check. Stored only on this phone, and only the masked text. */
@Entity(tableName = "history")
data class Check(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val verdict: String,
    val action: String,
    val masked: String,
    val decidedBy: String,
    val signals: String,
)

@Dao
interface CheckDao {
    @Query("SELECT * FROM history ORDER BY at DESC LIMIT 200")
    fun all(): Flow<List<Check>>

    @Insert
    suspend fun add(check: Check)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Database(entities = [Check::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun checks(): CheckDao

    companion object {
        fun open(context: Context): HistoryDb = Room.databaseBuilder(context, HistoryDb::class.java, "history.db").build()
    }
}
