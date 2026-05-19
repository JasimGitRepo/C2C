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
    val category: String, // New: For UI grouping
    val isToggle: Boolean = false, // New: For toggle commands
    val toggledLabel: String = "", // New: Label when toggled
    val toggledCmd: String = "",   // New: Command when toggled
    val toggledArg: String = ""    // New: Arg when toggled
)

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands ORDER BY category ASC, label ASC")
    fun getAllCommands(): Flow<List<CommandEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) // CRITICAL FIX: Handles updates for existing IDs
    suspend fun insertCommand(command: CommandEntity)

    @Delete
    suspend fun deleteCommand(command: CommandEntity)
    
    @Query("UPDATE commands SET defaultArg = :newArg WHERE id = :id")
    suspend fun updateArg(id: Int, newArg: String)
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