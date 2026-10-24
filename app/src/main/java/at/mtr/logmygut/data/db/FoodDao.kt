package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import at.mtr.logmygut.domain.FoodKind
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT id, parentId FROM food WHERE id IN (:ids)") suspend fun getParentIds(ids: Collection<Long>): List<FoodParent>
    @Query("SELECT id FROM food WHERE parentId IN (:ids)") suspend fun getChildIds(ids: Collection<Long>): List<Long>

    // Library screen (SPEC 4.1)
    @Query("SELECT * FROM food WHERE kind = :kind ORDER BY name COLLATE NOCASE") fun observeByKind(kind: FoodKind): Flow<List<FoodEntity>>
    @Query("SELECT * FROM food WHERE id = :id") fun observeFood(id: Long): Flow<FoodEntity?>
    @Query("UPDATE food SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE food SET userModified = 0 WHERE id = :id") suspend fun clearUserModified(id: Long)
    @Query(
        "SELECT EXISTS(SELECT 1 FROM meal_item WHERE foodId = :id) OR EXISTS(SELECT 1 FROM meal_item_ingredient WHERE ingredientId = :id)",
    )
    suspend fun isUsedInMeals(id: Long): Boolean
    @Query("SELECT f.* FROM food f JOIN dish_ingredient d ON d.dishId = f.id WHERE d.ingredientId = :id ORDER BY f.name COLLATE NOCASE")
    suspend fun getDishesUsing(id: Long): List<FoodEntity>
    @Query("UPDATE food SET parentId = :parentId WHERE id = :id") suspend fun setParent(id: Long, parentId: Long?)
    @Query("SELECT * FROM food WHERE seedKey IS NOT NULL") suspend fun getSeededFoods(): List<FoodEntity>

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
        WHERE hidden = 0 AND searchText LIKE '%' || :query || '%' AND (:kind IS NULL OR kind = :kind)
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
    suspend fun search(query: String, kind: FoodKind? = null, limit: Int = 30): List<FoodEntity>

    @Query("SELECT * FROM food WHERE id IN (:ids)") suspend fun getFoods(ids: Collection<Long>): List<FoodEntity>
    @Query("UPDATE food SET userModified = 1 WHERE id = :id AND builtIn = 1") suspend fun markUserModified(id: Long)

    /** Replaces a dish's saved recipe. */
    @Transaction
    suspend fun setDishIngredients(dishId: Long, ingredientIds: List<Long>) {
        deleteDishIngredients(dishId)
        insertDishIngredients(ingredientIds.distinct().map { DishIngredientEntity(dishId, it) })
        markUserModified(dishId)
    }

    /** Replaces a food's own tag rows (no userModified change; used by the seed update). */
    @Transaction
    suspend fun replaceTags(foodId: Long, tags: List<FoodTagEntity>) {
        deleteTags(foodId)
        if (tags.isNotEmpty()) insertTags(tags.map { it.copy(foodId = foodId) })
    }

    /** Replaces a dish's recipe without marking it as user-modified (used by the seed update). */
    @Transaction
    suspend fun replaceDishIngredients(dishId: Long, ingredientIds: List<Long>) {
        deleteDishIngredients(dishId)
        insertDishIngredients(ingredientIds.distinct().map { DishIngredientEntity(dishId, it) })
    }

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

data class FoodParent(val id: Long, val parentId: Long?)
