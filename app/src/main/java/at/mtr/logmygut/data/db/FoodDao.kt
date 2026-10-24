package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import at.mtr.logmygut.domain.FoodKind

@Dao
interface FoodDao {
    @Insert suspend fun insertFood(food: FoodEntity): Long
    @Update suspend fun updateFood(food: FoodEntity)
    @Insert suspend fun insertTags(tags: List<FoodTagEntity>)
    @Insert suspend fun insertDishIngredients(rows: List<DishIngredientEntity>)

    @Query("DELETE FROM food_tag WHERE foodId = :foodId") suspend fun deleteTags(foodId: Long)
    @Query("DELETE FROM dish_ingredient WHERE dishId = :dishId") suspend fun deleteDishIngredients(dishId: Long)

    @Query("SELECT * FROM food WHERE id = :id") suspend fun getFood(id: Long): FoodEntity?
    @Query("SELECT * FROM food_tag WHERE foodId = :foodId") suspend fun getTags(foodId: Long): List<FoodTagEntity>
    @Query("SELECT * FROM food_tag WHERE foodId IN (:foodIds)") suspend fun getTagsFor(foodIds: Collection<Long>): List<FoodTagEntity>
    @Query("SELECT ingredientId FROM dish_ingredient WHERE dishId = :dishId") suspend fun getDishIngredientIds(dishId: Long): List<Long>

    @Query("SELECT seedKey FROM food WHERE seedKey IS NOT NULL") suspend fun getSeedKeys(): List<String>
    @Query("SELECT id, seedKey FROM food WHERE seedKey IS NOT NULL") suspend fun getSeedKeyIds(): List<SeedKeyId>
    @Query("SELECT COUNT(*) FROM food WHERE kind = :kind") suspend fun count(kind: FoodKind): Int

    /**
     * Visible foods whose normalized name/aliases contain [query] (already normalized).
     * Ranking: exact name/alias, then name prefix, then alias prefix, then substring;
     * within each, recently used first, then alphabetical. (searchText is "name|alias|alias".)
     */
    @Query(
        """
        SELECT * FROM food
        WHERE hidden = 0 AND searchText LIKE '%' || :query || '%'
        ORDER BY
            CASE
                WHEN '|' || searchText || '|' LIKE '%|' || :query || '|%' THEN 0
                WHEN searchText LIKE :query || '%' THEN 1
                WHEN searchText LIKE '%|' || :query || '%' THEN 2
                ELSE 3
            END,
            lastUsedAt IS NULL, lastUsedAt DESC,
            name COLLATE NOCASE
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 30): List<FoodEntity>

    /** Inserts a food with its tags (ingredient) or recipe (dish) atomically. */
    @Transaction
    suspend fun insertFoodWithDetails(food: FoodEntity, tags: List<FoodTagEntity>, ingredientIds: List<Long>): Long {
        val id = insertFood(food)
        if (tags.isNotEmpty()) insertTags(tags.map { it.copy(foodId = id) })
        if (ingredientIds.isNotEmpty()) insertDishIngredients(ingredientIds.distinct().map { DishIngredientEntity(id, it) })
        return id
    }
}

data class SeedKeyId(val id: Long, val seedKey: String)
