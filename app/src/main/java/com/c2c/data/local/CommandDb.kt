package com.c2c.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "commands")
data class CommandEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val cmd: String,
    val defaultArg: String,
    val icon: String,
    val category: String, 
    val isToggle: Boolean = false, 
    val toggledLabel: String = "", 
    val toggledCmd: String = "",   
    val toggledArg: String = ""    
)

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands ORDER BY category ASC, label ASC")
    fun getAllCommands(): Flow<List<CommandEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommand(command: CommandEntity)

    @Update // CRITICAL FIX: Explicit update to prevent duplication
    suspend fun updateCommand(command: CommandEntity)

    @Delete
    suspend fun deleteCommand(command: CommandEntity)
}

@Database(entities = [CommandEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun commandDao(): CommandDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "c2_database")
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}