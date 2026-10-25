package at.mtr.logmygut.data

import androidx.room.withTransaction
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.data.seed.toEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.inheritTags
import at.mtr.logmygut.domain.isDrinkUnit
import at.mtr.logmygut.domain.matchedAlias
import at.mtr.logmygut.domain.TagSuggester
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.normalizeForSearch
import at.mtr.logmygut.domain.unitLabelKey
import kotlinx.coroutines.flow.Flow

/**
 * Access to the food library: search, tag lookup, creating and editing foods (SPEC 4).
 * [syncSeed] brings the built-in foods in line with the seed, for "Reset to default".
 */
class FoodRepository(
    private val db: LogMyGutDatabase,
    private val tagSuggester: TagSuggester,
    private val syncSeed: suspend () -> Unit = { error("No seed file available") },
) {
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

    // Library (SPEC 4.1)

    /** All foods of [kind], hidden ones included, alphabetically. */
    fun observeLibrary(kind: FoodKind): Flow<List<FoodEntity>> = dao.observeByKind(kind)

    fun observeFood(id: Long): Flow<FoodEntity?> = dao.observeFood(id)

    /** Units of logged foods can't change: meal items store a count or a size matching the unit. */
    suspend fun isUsedInMeals(id: Long): Boolean = dao.isUsedInMeals(id)

    suspend fun dishesUsing(ingredientId: Long): List<FoodEntity> = dao.getDishesUsing(ingredientId)

    /**
     * Whether [parentId] may become the parent of [childId] (null = a new food): no cycle,
     * and the hierarchy stays within [MaxFoodDepth] levels including the child's own descendants.
     */
    suspend fun canBeParent(childId: Long?, parentId: Long): Boolean {
        if (parentId == childId) return false
        val ancestors = ancestorsOf(parentId)
        if (childId != null && childId in ancestors) return false
        val childHeight = if (childId == null) 1 else subtreeHeight(childId)
        return ancestors.size + 1 + childHeight <= MaxFoodDepth
    }

    /** Creates ([IngredientDraft.id] 0) or updates an ingredient; [IngredientDraft.tags] are the tags it should end up with. */
    suspend fun saveIngredient(draft: IngredientDraft): Long {
        require(draft.name.isNotBlank()) { "Name is required" }
        require(draft.unit == FoodUnit.SIZE || !draft.unitLabel.isNullOrBlank()) { "Count units need a label" }
        draft.parentId?.let { require(canBeParent(draft.id.takeIf { id -> id != 0L }, it)) { "Invalid parent" } }
        val rows = ownTagRows(draft.parentId, draft.tags)
        val aliases = draft.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return db.withTransaction {
            val existing = draft.id.takeIf { it != 0L }?.let { dao.getFood(it) }
            val unit = if (existing != null && dao.isUsedInMeals(existing.id)) existing.unit else draft.unit
            val unitLabel = draft.unitLabel?.let(::unitLabelKey).takeIf { unit == FoodUnit.COUNT }
                ?: existing?.unitLabel.takeIf { unit == FoodUnit.COUNT }
            val name = draft.name.trim()
            if (existing == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.INGREDIENT, name = name, aliases = aliases, unit = unit, unitLabel = unitLabel,
                        note = draft.note, parentId = draft.parentId,
                        portionGrams = draft.portionGrams, drink = draft.drink,
                    ),
                    tags = rows,
                    ingredientIds = emptyList(),
                )
            } else {
                dao.updateFood(
                    existing.copy(
                        name = name, aliases = aliases, unit = unit, unitLabel = unitLabel, note = draft.note,
                        parentId = draft.parentId, portionGrams = draft.portionGrams, drink = draft.drink, userModified = existing.builtIn,
                    ),
                )
                dao.replaceTags(existing.id, rows)
                existing.id
            }
        }
    }

    /** Creates ([DishDraft.id] 0) or updates a dish. */
    suspend fun saveDish(draft: DishDraft): Long {
        require(draft.name.isNotBlank()) { "Name is required" }
        require(draft.ingredientIds.isNotEmpty()) { "A dish needs at least one ingredient" }
        require(draft.unit == FoodUnit.SIZE || !draft.unitLabel.isNullOrBlank()) { "Count units need a label" }
        val aliases = draft.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val name = draft.name.trim()
        return db.withTransaction {
            val existing = draft.id.takeIf { it != 0L }?.let { dao.getFood(it) }
            if (existing == null) {
                createDish(name, draft.unit, draft.unitLabel, draft.ingredientIds, aliases, draft.drink, draft.portionGrams)
            } else {
                val unit = if (dao.isUsedInMeals(existing.id)) existing.unit else draft.unit
                dao.updateFood(
                    existing.copy(
                        name = name, aliases = aliases, unit = unit,
                        unitLabel = (draft.unitLabel?.let(::unitLabelKey) ?: existing.unitLabel).takeIf { unit == FoodUnit.COUNT },
                        userModified = existing.builtIn,
                        drink = draft.drink, portionGrams = draft.portionGrams.takeIf { draft.drink },
                    ),
                )
                dao.setDishIngredients(existing.id, draft.ingredientIds)
                existing.id
            }
        }
    }

    /** Hidden foods disappear from search but stay in past meals; nothing is ever deleted. */
    suspend fun setHidden(id: Long, hidden: Boolean) = dao.setHidden(id, hidden)

    /** A user-owned copy named "… (Kopie)" with the same tags, parent or recipe. Returns its id. */
    suspend fun duplicate(id: Long, copySuffix: String): Long = db.withTransaction {
        val food = dao.getFood(id) ?: error("Food $id not found")
        val name = "${food.name} $copySuffix"
        dao.insertFoodWithDetails(
            food = food.copy(
                id = 0, name = name, aliases = emptyList(), builtIn = false, userModified = false, hidden = false,
                seedKey = null, lastUsedAt = null,
            ),
            tags = dao.getTags(id),
            ingredientIds = dao.getDishIngredientIds(id),
        )
    }

    /** Restores a built-in food to the current seed version (SPEC 4.1). Its unit stays, see [SeedImporter]. */
    suspend fun resetToSeed(id: Long) {
        val food = dao.getFood(id) ?: return
        require(food.builtIn) { "Only built-in foods can be reset" }
        dao.clearUserModified(id)
        syncSeed()
    }

    private suspend fun ancestorsOf(id: Long): List<Long> {
        val chain = mutableListOf<Long>()
        var current: Long? = dao.getParentIds(listOf(id)).singleOrNull()?.parentId
        while (current != null && current !in chain && chain.size < MaxFoodDepth) {
            chain += current
            current = dao.getParentIds(listOf(current)).singleOrNull()?.parentId
        }
        return chain
    }

    /** Levels from [id] down to its deepest descendant, [id] itself counting as 1. */
    private suspend fun subtreeHeight(id: Long): Int {
        var height = 1
        var level = listOf(id)
        while (height <= MaxFoodDepth) {
            level = dao.getChildIds(level)
            if (level.isEmpty()) break
            height++
        }
        return height
    }

    /** Own tag rows so that inheriting from [parentId] yields exactly [tags] (SPEC 4.4). */
    private suspend fun ownTagRows(parentId: Long?, tags: List<IngredientTag>): List<FoodTagEntity> {
        val inherited = parentId?.let { getIngredientTags(it) }.orEmpty()
        val own = tags.filter { it !in inherited }
        val removed = inherited.map { it.tag }.filter { tag -> tags.none { it.tag == tag } }
        return own.map { it.toEntity() } + removed.map { FoodTagEntity(foodId = 0, tag = it, removed = true) }
    }

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
            unitLabel = unitLabel?.let(::unitLabelKey).takeIf { unit == FoodUnit.COUNT },
            note = note,
            drink = isDrinkUnit(unit, unitLabel),
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
        drink: Boolean = isDrinkUnit(unit, unitLabel),
        portionGrams: Double? = null,
    ): Long {
        require(ingredientIds.isNotEmpty()) { "A dish needs at least one ingredient" }
        return dao.insertFoodWithDetails(
            food = FoodEntity(
                kind = FoodKind.DISH,
                name = name.trim(),
                aliases = aliases,
                unit = unit,
                unitLabel = unitLabel?.let(::unitLabelKey).takeIf { unit == FoodUnit.COUNT },
                drink = drink,
                portionGrams = portionGrams.takeIf { drink },
            ),
            tags = emptyList(),
            ingredientIds = ingredientIds,
        )
    }
}

/** What the ingredient editor saves. [tags] are the final, effective tags; inheritance is worked out on save. */
data class IngredientDraft(
    val id: Long,
    val name: String,
    val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    val portionGrams: Double?,
    val note: String?,
    val parentId: Long?,
    val tags: List<IngredientTag>,
    /** A drink: [portionGrams] is then millilitres. */
    val drink: Boolean = false,
)

data class DishDraft(
    val id: Long,
    val name: String,
    val aliases: List<String>,
    val unit: FoodUnit,
    val unitLabel: String?,
    val ingredientIds: List<Long>,
    val drink: Boolean = false,
    /** Millilitres of one portion, for a drink. */
    val portionGrams: Double? = null,
)

/** A search result: [matchedAlias] is set when the query matched an alias rather than the name. */
data class FoodHit(val food: FoodEntity, val matchedAlias: String?, val parentName: String?)

internal fun FoodTagEntity.toDomain() = IngredientTag(
    tag = tag,
    threshold = minSize?.let { Threshold.MinSize(it) } ?: minCount?.let { Threshold.MinCount(it) },
)
