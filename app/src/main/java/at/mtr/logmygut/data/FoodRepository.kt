package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.searchTextFor
import at.mtr.logmygut.data.seed.toEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.TagSuggester
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.normalizeForSearch

/** Access to the food library: search, tag lookup and creating user foods. */
class FoodRepository(db: LogMyGutDatabase, private val tagSuggester: TagSuggester) {
    private val dao = db.foodDao()

    /** [kind] null searches ingredients and dishes. */
    suspend fun search(query: String, kind: FoodKind? = null, limit: Int = 30): List<FoodEntity> {
        val normalized = normalizeForSearch(query)
        // Escaping isn't needed for LIKE wildcards here: '%' and '_' in a food search are harmless.
        return if (normalized.isEmpty()) emptyList() else dao.search(normalized, kind, limit)
    }

    suspend fun getFood(id: Long): FoodEntity? = dao.getFood(id)

    /** Foods by id, in the order of [ids]; unknown ids are skipped. */
    suspend fun getFoods(ids: List<Long>): List<FoodEntity> {
        val byId = dao.getFoods(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Permanently changes a dish's recipe (applies to future meals only). */
    suspend fun setDishIngredients(dishId: Long, ingredientIds: List<Long>) {
        require(ingredientIds.isNotEmpty()) { "A dish needs at least one ingredient" }
        dao.setDishIngredients(dishId, ingredientIds)
    }

    suspend fun getIngredientTags(foodId: Long): List<IngredientTag> = dao.getTags(foodId).map { it.toDomain() }

    /** Tags for many ingredients at once, keyed by food id (foods without tags are absent). */
    suspend fun getIngredientTags(foodIds: Collection<Long>): Map<Long, List<IngredientTag>> =
        dao.getTagsFor(foodIds).groupBy({ it.foodId }, { it.toDomain() })

    suspend fun getDishIngredientIds(dishId: Long): List<Long> = dao.getDishIngredientIds(dishId)

    fun suggestTags(name: String) = tagSuggester.suggest(name)

    suspend fun createIngredient(
        name: String,
        unit: FoodUnit,
        unitLabel: String?,
        tags: List<IngredientTag>,
        aliases: List<String> = emptyList(),
        note: String? = null,
    ): Long = dao.insertFoodWithDetails(
        food = FoodEntity(
            kind = FoodKind.INGREDIENT,
            name = name.trim(),
            aliases = aliases,
            unit = unit,
            unitLabel = unitLabel.takeIf { unit == FoodUnit.COUNT },
            note = note,
            searchText = searchTextFor(name, aliases),
        ),
        tags = tags.map { it.toEntity() },
        ingredientIds = emptyList(),
    )

    suspend fun createDish(
        name: String,
        unit: FoodUnit,
        unitLabel: String?,
        ingredientIds: List<Long>,
        aliases: List<String> = emptyList(),
    ): Long {
        require(ingredientIds.isNotEmpty()) { "A dish needs at least one ingredient" }
        return dao.insertFoodWithDetails(
            food = FoodEntity(
                kind = FoodKind.DISH,
                name = name.trim(),
                aliases = aliases,
                unit = unit,
                unitLabel = unitLabel.takeIf { unit == FoodUnit.COUNT },
                searchText = searchTextFor(name, aliases),
            ),
            tags = emptyList(),
            ingredientIds = ingredientIds,
        )
    }
}

internal fun FoodTagEntity.toDomain() = IngredientTag(
    tag = tag,
    threshold = minSize?.let { Threshold.MinSize(it) } ?: minCount?.let { Threshold.MinCount(it) },
)
