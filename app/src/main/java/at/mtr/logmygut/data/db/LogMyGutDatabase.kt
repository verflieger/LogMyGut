package at.mtr.logmygut.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FoodEntity::class,
        FoodTagEntity::class,
        DishIngredientEntity::class,
        MealEntity::class,
        MealItemEntity::class,
        MealItemIngredientEntity::class,
        BowelMovementEntity::class,
        SymptomEntryEntity::class,
        MedicationIntakeEntity::class,
        DailyCheckInEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LogMyGutDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun journalDao(): JournalDao

    companion object {
        fun create(context: Context): LogMyGutDatabase =
            Room.databaseBuilder(context, LogMyGutDatabase::class.java, "logmygut.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
