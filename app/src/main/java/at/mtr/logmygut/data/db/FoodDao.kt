package at.mtr.logmygut.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.normalizeForSearch
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodDao {
    /** Raw insert that keeps [FoodEntity.searchText] as given; use [insertFood]. */
    @Insert suspend fun insertFoodRow(food: FoodEntity): Long
    /** Raw update that keeps [FoodEntity.searchText] as given; use [updateFood]. */
    @Update suspend fun updateFoodRow(food: FoodEntity)
    // Every food write goes through these two, so searchText always matches name and aliases.
    suspend fun insertFood(food: FoodEntity): Long = insertFoodRow(food.withSearchText())
    suspend fun updateFood(food: FoodEntity) = updateFoodRow(food.withSearchText())
    @Insert suspend fun insertTags(tags: List<FoodTagEntity>)
    @Insert suspend fun insertDishIngredients(rows: List<DishIngredientEntity>)

    @Query("DELETE FROM food_tag WHERE foodId = :foodId") suspend fun deleteTags(foodId: Long)
    @Query("DELETE FROM food_tag WHERE foodId = :foodId AND source = :source") suspend fun deleteTags(foodId: Long, source: TagSource)
    @Query("DELETE FROM dish_ingredient WHERE dishId = :dishId") suspend fun deleteDishIngredients(dishId: Long)

    @Query("SELECT * FROM food WHERE id = :id") suspend fun getFood(id: Long): FoodEntity?
    @Query("SELECT * FROM food_tag WHERE foodId = :foodId") suspend fun getTags(foodId: Long): List<FoodTagEntity>
    @Query("SELECT * FROM food_tag WHERE foodId IN (:foodIds)") suspend fun getTagsFor(foodIds: Collection<Long>): List<FoodTagEntity>
    @Query("SELECT ingredientId FROM dish_ingredient WHERE dishId = :dishId") suspend fun getDishIngredientIds(dishId: Long): List<Long>

    @Query("SELECT id, parentId FROM food WHERE id IN (:ids)") suspend fun getParentIds(ids: Collection<Long>): List<FoodParent>
    @Query("SELECT id, parentId FROM food WHERE kind = 'INGREDIENT'") suspend fun getIngredientParents(): List<FoodParent>
    /** Every ingredient's parent link: food id → parent id. */
    suspend fun getIngredientParentMap(): Map<Long, Long?> = getIngredientParents().associate { it.id to it.parentId }

    // Library screen (SPEC 4.1)
    @Query("SELECT * FROM food WHERE kind = :kind ORDER BY name COLLATE NOCASE") fun observeByKind(kind: FoodKind): Flow<List<FoodEntity>>
    @Query("SELECT * FROM food WHERE id = :id") fun observeFood(id: Long): Flow<FoodEntity?>
    @Query("UPDATE food SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE food SET userModified = 0 WHERE id = :id") suspend fun clearUserModified(id: Long)
    @Query("UPDATE food SET tagsDetached = 0 WHERE id = :id") suspend fun clearTagsDetached(id: Long)
    @Query("UPDATE food SET tagsDetached = 1 WHERE id = :id") suspend fun markTagsDetached(id: Long)
    @Query(
        "SELECT EXISTS(SELECT 1 FROM meal_item WHERE foodId = :id) OR EXISTS(SELECT 1 FROM meal_item_ingredient WHERE ingredientId = :id)",
    )
    suspend fun isUsedInMeals(id: Long): Boolean
    @Query("SELECT f.* FROM food f JOIN dish_ingredient d ON d.dishId = f.id WHERE d.ingredientId = :id ORDER BY f.name COLLATE NOCASE")
    suspend fun getDishesUsing(id: Long): List<FoodEntity>
    @Query("UPDATE food SET parentId = :parentId WHERE id = :id") suspend fun setParent(id: Long, parentId: Long?)
    @Query("SELECT * FROM food") suspend fun getAllFoods(): List<FoodEntity>
    @Query("SELECT * FROM food WHERE seedKey IS NOT NULL") suspend fun getSeededFoods(): List<FoodEntity>
    @Query("SELECT * FROM food WHERE seedKey = :seedKey") suspend fun getBySeedKey(seedKey: String): FoodEntity?

    // Expert screens (SPEC 2)
    @Query("SELECT * FROM food WHERE builtIn = 1 AND tagsDetached = 1 ORDER BY name COLLATE NOCASE")
    fun observeDetached(): Flow<List<FoodEntity>>
    /** Built-in foods that may differ from the seed: edited, detached or with user tag rows. Also watches food_tag. */
    @Query(
        """
        SELECT * FROM food f WHERE f.builtIn = 1 AND (f.userModified = 1 OR f.tagsDetached = 1
            OR EXISTS(SELECT 1 FROM food_tag t WHERE t.foodId = f.id AND t.source = 'USER'))
        ORDER BY f.name COLLATE NOCASE
        """,
    )
    fun observeChangedBuiltIns(): Flow<List<FoodEntity>>

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

    /** Replaces a food's tag rows of [source] with [tags] (all of that source), keeping the others (no userModified change). */
    @Transaction
    suspend fun replaceTags(foodId: Long, source: TagSource, tags: List<FoodTagEntity>) {
        require(tags.all { it.source == source }) { "Tag rows of another source than $source" }
        deleteTags(foodId, source)
        if (tags.isNotEmpty()) insertTags(tags.map { it.copy(foodId = foodId) })
    }

    /** Replaces all of a food's tag rows, whatever their source. */
    @Transaction
    suspend fun replaceAllTags(foodId: Long, tags: List<FoodTagEntity>) {
        deleteTags(foodId)
        if (tags.isNotEmpty()) insertTags(tags.map { it.copy(foodId = foodId) })
    }

    /** Replaces a dish's recipe without marking it as user-modified (used by the seed update). */
    @Transaction
    suspend fun replaceDishIngredients(dishId: Long, ingredientIds: List<Long>) {
        deleteDishIngredients(dishId)
        insertDishIngredients(ingredientIds.distinct().map { DishIngredientEntity(dishId, it) })
    }

    /**
     * Recomputes searchText where it's out of date, e.g. after [normalizeForSearch] changed or for rows written
     * before this was enforced. Returns the number of rows fixed.
     */
    @Transaction
    suspend fun refreshSearchText(): Int {
        val stale = getAllFoods().filter { it.searchText != searchTextFor(it.name, it.aliases) }
        stale.forEach { updateFood(it) }
        return stale.size
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

/** Search column content: normalized name and aliases, separated so matches don't span entries ("name|alias|…"). */
fun searchTextFor(name: String, aliases: List<String>): String =
    (listOf(name) + aliases).joinToString("|") { normalizeForSearch(it) }

internal fun FoodEntity.withSearchText() = copy(searchText = searchTextFor(name, aliases))

/** Whether the name or an alias contains [normalizedQuery] (from [normalizeForSearch]). */
fun FoodEntity.matchesSearch(normalizedQuery: String): Boolean = normalizedQuery in searchText

data class SeedKeyId(val id: Long, val seedKey: String)

data class FoodParent(val id: Long, val parentId: Long?)
