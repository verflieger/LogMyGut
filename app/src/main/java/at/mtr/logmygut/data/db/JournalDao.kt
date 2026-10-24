package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Logged entries (meals, bowel movements, symptoms, medication, check-ins). Ranges are [from, to). */
@Dao
interface JournalDao {
    // Meals
    @Insert suspend fun insertMeal(meal: MealEntity): Long
    @Update suspend fun updateMeal(meal: MealEntity)
    @Query("DELETE FROM meal WHERE id = :id") suspend fun deleteMeal(id: Long)
    @Insert suspend fun insertMealItem(item: MealItemEntity): Long
    @Insert suspend fun insertMealItemIngredients(rows: List<MealItemIngredientEntity>)
    @Query("DELETE FROM meal_item WHERE mealId = :mealId") suspend fun deleteMealItems(mealId: Long)
    @Query("UPDATE food SET lastUsedAt = :at WHERE id IN (:foodIds) AND (lastUsedAt IS NULL OR lastUsedAt < :at)")
    suspend fun markFoodsUsed(foodIds: Collection<Long>, at: Instant)

    @Query("SELECT * FROM meal WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeMeals(from: Instant, to: Instant): Flow<List<MealEntity>>
    @Query("SELECT * FROM meal WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    suspend fun getMeals(from: Instant, to: Instant): List<MealEntity>
    @Query("SELECT * FROM meal_item WHERE mealId IN (:mealIds)") suspend fun getMealItems(mealIds: Collection<Long>): List<MealItemEntity>
    @Query("SELECT * FROM meal_item_ingredient WHERE mealItemId IN (:itemIds)")
    suspend fun getMealItemIngredients(itemIds: Collection<Long>): List<MealItemIngredientEntity>

    /**
     * Saves a meal and its items atomically. For dish items, [dishIngredients] maps the item's index
     * in [items] to the ingredient ids actually eaten. Replaces existing items when the meal exists.
     */
    @Transaction
    suspend fun saveMeal(meal: MealEntity, items: List<MealItemEntity>, dishIngredients: Map<Int, List<Long>>): Long {
        val mealId = if (meal.id == 0L) insertMeal(meal) else meal.id.also { updateMeal(meal); deleteMealItems(it) }
        items.forEachIndexed { index, item ->
            val itemId = insertMealItem(item.copy(id = 0, mealId = mealId))
            dishIngredients[index]?.distinct()?.takeIf { it.isNotEmpty() }?.let { ids ->
                insertMealItemIngredients(ids.map { MealItemIngredientEntity(itemId, it) })
            }
        }
        markFoodsUsed(items.map { it.foodId }, meal.timestamp)
        return mealId
    }

    // Bowel movements
    @Insert suspend fun insertBowelMovement(entry: BowelMovementEntity): Long
    @Update suspend fun updateBowelMovement(entry: BowelMovementEntity)
    @Delete suspend fun deleteBowelMovement(entry: BowelMovementEntity)
    @Query("SELECT * FROM bowel_movement WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeBowelMovements(from: Instant, to: Instant): Flow<List<BowelMovementEntity>>

    // Symptoms
    @Insert suspend fun insertSymptoms(entries: List<SymptomEntryEntity>): List<Long>
    @Update suspend fun updateSymptom(entry: SymptomEntryEntity)
    @Delete suspend fun deleteSymptom(entry: SymptomEntryEntity)
    @Query("SELECT * FROM symptom_entry WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeSymptoms(from: Instant, to: Instant): Flow<List<SymptomEntryEntity>>

    // Medication
    @Insert suspend fun insertMedication(entry: MedicationIntakeEntity): Long
    @Update suspend fun updateMedication(entry: MedicationIntakeEntity)
    @Delete suspend fun deleteMedication(entry: MedicationIntakeEntity)
    @Query("SELECT * FROM medication_intake WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeMedication(from: Instant, to: Instant): Flow<List<MedicationIntakeEntity>>
    /** Newest first; the repository de-duplicates by name for autocomplete and effect pre-fill. */
    @Query("SELECT * FROM medication_intake ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMedication(limit: Int = 200): List<MedicationIntakeEntity>

    // Daily check-in
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCheckIn(entry: DailyCheckInEntity)
    @Query("SELECT * FROM daily_check_in WHERE date = :date") fun observeCheckIn(date: LocalDate): Flow<DailyCheckInEntity?>
}
