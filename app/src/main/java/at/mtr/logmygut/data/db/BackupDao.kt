package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Whole-database read and replace for backups (SPEC 7). Rows keep their ids, so references stay intact. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM food ORDER BY id") suspend fun getFoods(): List<FoodEntity>
    @Query("SELECT * FROM food_tag ORDER BY foodId, tag") suspend fun getFoodTags(): List<FoodTagEntity>
    @Query("SELECT * FROM dish_ingredient ORDER BY dishId, ingredientId") suspend fun getDishIngredients(): List<DishIngredientEntity>
    @Query("SELECT * FROM meal ORDER BY id") suspend fun getMeals(): List<MealEntity>
    @Query("SELECT * FROM meal_item ORDER BY id") suspend fun getMealItems(): List<MealItemEntity>
    @Query("SELECT * FROM meal_item_ingredient ORDER BY mealItemId, ingredientId") suspend fun getMealItemIngredients(): List<MealItemIngredientEntity>
    @Query("SELECT * FROM bowel_movement ORDER BY id") suspend fun getBowelMovements(): List<BowelMovementEntity>
    @Query("SELECT * FROM symptom_entry ORDER BY id") suspend fun getSymptoms(): List<SymptomEntryEntity>
    @Query("SELECT * FROM medication_intake ORDER BY id") suspend fun getMedication(): List<MedicationIntakeEntity>
    @Query("SELECT * FROM daily_check_in ORDER BY date") suspend fun getCheckIns(): List<DailyCheckInEntity>

    @Transaction
    suspend fun getAll() = DatabaseContent(
        foods = getFoods(),
        foodTags = getFoodTags(),
        dishIngredients = getDishIngredients(),
        meals = getMeals(),
        mealItems = getMealItems(),
        mealItemIngredients = getMealItemIngredients(),
        bowelMovements = getBowelMovements(),
        symptoms = getSymptoms(),
        medication = getMedication(),
        checkIns = getCheckIns(),
    )

    @Query("DELETE FROM meal_item_ingredient") suspend fun deleteMealItemIngredients()
    @Query("DELETE FROM meal_item") suspend fun deleteMealItems()
    @Query("DELETE FROM meal") suspend fun deleteMeals()
    @Query("DELETE FROM dish_ingredient") suspend fun deleteDishIngredients()
    @Query("DELETE FROM food_tag") suspend fun deleteFoodTags()
    @Query("DELETE FROM food") suspend fun deleteFoods()
    @Query("DELETE FROM bowel_movement") suspend fun deleteBowelMovements()
    @Query("DELETE FROM symptom_entry") suspend fun deleteSymptoms()
    @Query("DELETE FROM medication_intake") suspend fun deleteMedication()
    @Query("DELETE FROM daily_check_in") suspend fun deleteCheckIns()

    /** Raw insert that keeps [FoodEntity.searchText] as given; use [insertFoods]. */
    @Insert suspend fun insertFoodRows(rows: List<FoodEntity>)
    suspend fun insertFoods(rows: List<FoodEntity>) = insertFoodRows(rows.map { it.withSearchText() })
    @Insert suspend fun insertFoodTags(rows: List<FoodTagEntity>)
    @Insert suspend fun insertDishIngredients(rows: List<DishIngredientEntity>)
    @Insert suspend fun insertMeals(rows: List<MealEntity>)
    @Insert suspend fun insertMealItems(rows: List<MealItemEntity>)
    @Insert suspend fun insertMealItemIngredients(rows: List<MealItemIngredientEntity>)
    @Insert suspend fun insertBowelMovements(rows: List<BowelMovementEntity>)
    @Insert suspend fun insertSymptoms(rows: List<SymptomEntryEntity>)
    @Insert suspend fun insertMedication(rows: List<MedicationIntakeEntity>)
    @Insert suspend fun insertCheckIns(rows: List<DailyCheckInEntity>)

    /**
     * Replaces the whole database with [content] in one transaction: if any insert fails
     * (e.g. a broken reference), the old data stays. Children are deleted before parents.
     */
    @Transaction
    suspend fun replaceAll(content: DatabaseContent) {
        deleteMealItemIngredients()
        deleteMealItems()
        deleteMeals()
        deleteDishIngredients()
        deleteFoodTags()
        deleteFoods()
        deleteBowelMovements()
        deleteSymptoms()
        deleteMedication()
        deleteCheckIns()

        insertFoods(content.foods)
        insertFoodTags(content.foodTags)
        insertDishIngredients(content.dishIngredients)
        insertMeals(content.meals)
        insertMealItems(content.mealItems)
        insertMealItemIngredients(content.mealItemIngredients)
        insertBowelMovements(content.bowelMovements)
        insertSymptoms(content.symptoms)
        insertMedication(content.medication)
        insertCheckIns(content.checkIns)
    }
}

/** Every row of every table. */
data class DatabaseContent(
    val foods: List<FoodEntity>,
    val foodTags: List<FoodTagEntity>,
    val dishIngredients: List<DishIngredientEntity>,
    val meals: List<MealEntity>,
    val mealItems: List<MealItemEntity>,
    val mealItemIngredients: List<MealItemIngredientEntity>,
    val bowelMovements: List<BowelMovementEntity>,
    val symptoms: List<SymptomEntryEntity>,
    val medication: List<MedicationIntakeEntity>,
    val checkIns: List<DailyCheckInEntity>,
)
