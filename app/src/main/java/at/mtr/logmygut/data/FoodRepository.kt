package at.mtr.logmygut.data

import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.searchTextFor
import at.mtr.logmygut.data.seed.toEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.inheritTags
import at.mtr.logmygut.domain.matchedAlias
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

    /** Search results with the context the picker shows: matched alias and parent food. */
    suspend fun searchHits(query: String, kind: FoodKind? = null, limit: Int = 30): List<FoodHit> {
        val foods = search(query, kind, limit)
        val parentNames = dao.getFoods(foods.mapNotNull { it.parentId }.distinct()).associate { it.id to it.name }
        val normalized = normalizeForSearch(query)
        return foods.map { FoodHit(it, matchedAlias(it.name, it.aliases, normalized), it.parentId?.let(parentNames::get)) }
    }

    /** An ingredient's resolved tags, including those inherited from its parents. */
    suspend fun getIngredientTags(foodId: Long): List<IngredientTag> = getIngredientTags(listOf(foodId))[foodId].orEmpty()

    /**
     * Resolved tags for many ingredients at once, keyed by food id (foods without tags are absent).
     * Inheritance follows SPEC 4.4; depth is capped at [MaxFoodDepth] so a bad parent link can't loop.
     */
    suspend fun getIngredientTags(foodIds: Collection<Long>): Map<Long, List<IngredientTag>> {
        val parentOf = mutableMapOf<Long, Long?>()
        var pending: Collection<Long> = foodIds.toSet()
        while (pending.isNotEmpty()) {
            val rows = dao.getParentIds(pending)
            rows.forEach { parentOf[it.id] = it.parentId }
            pending = rows.mapNotNull { it.parentId }.filterNot { it in parentOf }.toSet()
        }
        val rowsByFood = dao.getTagsFor(parentOf.keys).groupBy { it.foodId }

        val resolved = mutableMapOf<Long, List<IngredientTag>>()
        fun resolve(id: Long, depth: Int): List<IngredientTag> = resolved[id] ?: run {
            val parent = parentOf[id]?.takeIf { depth < MaxFoodDepth - 1 }?.let { resolve(it, depth + 1) }.orEmpty()
            val (removed, own) = rowsByFood[id].orEmpty().partition { it.removed }
            inheritTags(parent, own.map { it.toDomain() }, removed.map { it.tag }).also { resolved[id] = it }
        }
        return foodIds.associateWith { resolve(it, 0) }.filterValues { it.isNotEmpty() }
    }

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

/** A search result: [matchedAlias] is set when the query matched an alias rather than the name. */
data class FoodHit(val food: FoodEntity, val matchedAlias: String?, val parentName: String?)

internal fun FoodTagEntity.toDomain() = IngredientTag(
    tag = tag,
    threshold = minSize?.let { Threshold.MinSize(it) } ?: minCount?.let { Threshold.MinCount(it) },
)
