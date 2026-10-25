package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import at.mtr.logmygut.domain.SymptomType
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
    suspend fun getMeals(from: Instant, to: Instant): List<MealEntity>
    @Query("SELECT * FROM meal_item WHERE mealId IN (:mealIds)") suspend fun getMealItems(mealIds: Collection<Long>): List<MealItemEntity>
    @Query("UPDATE meal_item SET count = count + 1 WHERE id = :id") suspend fun incrementItemCount(id: Long)
    @Query("SELECT * FROM meal_item_ingredient WHERE mealItemId IN (:itemIds)")
    suspend fun getMealItemIngredients(itemIds: Collection<Long>): List<MealItemIngredientEntity>

    @Transaction
    @Query("SELECT * FROM meal WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeMealsWithItems(from: Instant, to: Instant): Flow<List<MealWithItems>>
    @Transaction
    @Query("SELECT * FROM meal WHERE id = :id")
    suspend fun getMealWithItems(id: Long): MealWithItems?

    /**
     * Saves a meal and its items atomically. For dish items, [dishIngredients] maps the item's index
     * in [items] to the ingredient ids actually eaten. Replaces existing items when the meal exists.
     */
    @Transaction
    suspend fun saveMeal(meal: MealEntity, items: List<MealItemEntity>, dishIngredients: Map<Int, List<Long>>): Long {
        val mealId = if (meal.id == 0L) insertMeal(meal) else meal.id.also { updateMeal(meal); deleteMealItems(it) }
        insertItems(mealId, items, dishIngredients)
        markFoodsUsed(items.map { it.foodId }, meal.timestamp)
        return mealId
    }

    /** Re-inserts a deleted meal under its original id (undo). */
    @Transaction
    suspend fun restoreMeal(meal: MealEntity, items: List<MealItemEntity>, dishIngredients: Map<Int, List<Long>>) {
        insertMeal(meal)
        insertItems(meal.id, items, dishIngredients)
    }

    private suspend fun insertItems(mealId: Long, items: List<MealItemEntity>, dishIngredients: Map<Int, List<Long>>) {
        items.forEachIndexed { index, item ->
            val itemId = insertMealItem(item.copy(id = 0, mealId = mealId))
            dishIngredients[index]?.distinct()?.takeIf { it.isNotEmpty() }?.let { ids ->
                insertMealItemIngredients(ids.map { MealItemIngredientEntity(itemId, it) })
            }
        }
    }

    // Bowel movements
    @Insert suspend fun insertBowelMovement(entry: BowelMovementEntity): Long
    @Update suspend fun updateBowelMovement(entry: BowelMovementEntity)
    @Delete suspend fun deleteBowelMovement(entry: BowelMovementEntity)
    @Query("SELECT * FROM bowel_movement WHERE id = :id") suspend fun getBowelMovement(id: Long): BowelMovementEntity?
    @Query("SELECT * FROM bowel_movement WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeBowelMovements(from: Instant, to: Instant): Flow<List<BowelMovementEntity>>

    // Symptoms: entries saved together share a timestamp and are edited as one group.
    @Insert suspend fun insertSymptoms(entries: List<SymptomEntryEntity>): List<Long>
    @Query("DELETE FROM symptom_entry WHERE timestamp = :timestamp") suspend fun deleteSymptomsAt(timestamp: Instant)
    @Query("SELECT * FROM symptom_entry WHERE timestamp = :timestamp") suspend fun getSymptomsAt(timestamp: Instant): List<SymptomEntryEntity>
    @Query("SELECT * FROM symptom_entry WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeSymptoms(from: Instant, to: Instant): Flow<List<SymptomEntryEntity>>

    @Query("DELETE FROM symptom_entry WHERE timestamp = :timestamp AND type = :type")
    suspend fun deleteSymptomAt(timestamp: Instant, type: SymptomType)

    /**
     * Replaces the group at [oldTimestamp] (null = new group) with [entries]. If another group already sits at
     * the new timestamp, the two merge; for a symptom type present in both, the new value wins.
     */
    @Transaction
    suspend fun replaceSymptomGroup(oldTimestamp: Instant?, entries: List<SymptomEntryEntity>) {
        oldTimestamp?.let { deleteSymptomsAt(it) }
        entries.forEach { deleteSymptomAt(it.timestamp, it.type) }
        insertSymptoms(entries.map { it.copy(id = 0) })
    }

    // Medication
    @Insert suspend fun insertMedication(entry: MedicationIntakeEntity): Long
    @Update suspend fun updateMedication(entry: MedicationIntakeEntity)
    @Delete suspend fun deleteMedication(entry: MedicationIntakeEntity)
    @Query("SELECT * FROM medication_intake WHERE id = :id") suspend fun getMedication(id: Long): MedicationIntakeEntity?
    @Query("SELECT * FROM medication_intake WHERE timestamp >= :from AND timestamp < :to ORDER BY timestamp")
    fun observeMedication(from: Instant, to: Instant): Flow<List<MedicationIntakeEntity>>
    /** Newest first; the repository de-duplicates by name for autocomplete and effect pre-fill. */
    @Query("SELECT * FROM medication_intake ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMedication(limit: Int = 200): List<MedicationIntakeEntity>

    // Daily check-in
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCheckIn(entry: DailyCheckInEntity)
    @Query("SELECT * FROM daily_check_in WHERE date = :date") fun observeCheckIn(date: LocalDate): Flow<DailyCheckInEntity?>
    @Query("SELECT * FROM daily_check_in WHERE date = :date") suspend fun getCheckIn(date: LocalDate): DailyCheckInEntity?

    // Calendar (SPEC 2, 6.2): just what the day score and the "has entries" marker need.
    @Query("SELECT timestamp, severity AS value FROM symptom_entry WHERE timestamp >= :from AND timestamp < :to")
    fun observeSymptomSeverities(from: Instant, to: Instant): Flow<List<TimedValue>>
    @Query("SELECT timestamp, bristol AS value FROM bowel_movement WHERE timestamp >= :from AND timestamp < :to")
    fun observeBristolTypes(from: Instant, to: Instant): Flow<List<TimedValue>>
    @Query(
        "SELECT timestamp FROM meal WHERE timestamp >= :from AND timestamp < :to " +
            "UNION ALL SELECT timestamp FROM medication_intake WHERE timestamp >= :from AND timestamp < :to",
    )
    fun observeOtherEntryTimes(from: Instant, to: Instant): Flow<List<Instant>>
    /** Antibiotic intakes are shown as shaded days (SPEC 6.3 step 7). */
    @Query("SELECT timestamp FROM medication_intake WHERE effect = 'ANTIBIOTIC' AND timestamp >= :from AND timestamp < :to")
    fun observeAntibioticTimes(from: Instant, to: Instant): Flow<List<Instant>>

    // Analysis (SPEC 6): all entries at once, read in one transaction so they fit together.
    @Query("SELECT * FROM meal") suspend fun getAllMeals(): List<MealEntity>
    @Query("SELECT * FROM meal_item") suspend fun getAllMealItems(): List<MealItemEntity>
    @Query("SELECT * FROM meal_item_ingredient") suspend fun getAllMealItemIngredients(): List<MealItemIngredientEntity>
    @Query("SELECT * FROM symptom_entry") suspend fun getAllSymptoms(): List<SymptomEntryEntity>
    @Query("SELECT * FROM bowel_movement") suspend fun getAllBowelMovements(): List<BowelMovementEntity>
    @Query("SELECT * FROM medication_intake") suspend fun getAllMedication(): List<MedicationIntakeEntity>

    @Transaction
    suspend fun getJournalSnapshot() = JournalSnapshot(
        meals = getAllMeals(),
        items = getAllMealItems(),
        itemIngredients = getAllMealItemIngredients(),
        symptoms = getAllSymptoms(),
        bowelMovements = getAllBowelMovements(),
        medication = getAllMedication(),
    )
}

data class JournalSnapshot(
    val meals: List<MealEntity>,
    val items: List<MealItemEntity>,
    val itemIngredients: List<MealItemIngredientEntity>,
    val symptoms: List<SymptomEntryEntity>,
    val bowelMovements: List<BowelMovementEntity>,
    val medication: List<MedicationIntakeEntity>,
)

data class TimedValue(val timestamp: Instant, val value: Int)
