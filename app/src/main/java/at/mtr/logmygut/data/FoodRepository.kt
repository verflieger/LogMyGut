package at.mtr.logmygut.data

import androidx.room.withTransaction
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.tagsFollowSeed
import at.mtr.logmygut.data.db.toEntity
import at.mtr.logmygut.data.db.toRow
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.seed.SeedImporter
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.FoodUnit
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.fullRowsFor
import at.mtr.logmygut.domain.ownRows
import at.mtr.logmygut.domain.levelOf
import at.mtr.logmygut.domain.parentProblems
import at.mtr.logmygut.domain.resolveTags
import at.mtr.logmygut.domain.userRowsFor
import at.mtr.logmygut.domain.isDrinkUnit
import at.mtr.logmygut.domain.matchedAlias
import at.mtr.logmygut.domain.TagSuggester
import at.mtr.logmygut.domain.normalizeForSearch
import at.mtr.logmygut.domain.unitLabelKey
import kotlinx.coroutines.flow.Flow

/**
 * Access to the food library: search, tag lookup, creating and editing foods (SPEC 4).
 * [resetFood] restores one built-in food from the seed, for "Reset to default" ([SeedImporter.reset]).
 */
class FoodRepository(
    private val db: LogMyGutDatabase,
    private val tagSuggester: TagSuggester,
    private val resetFood: suspend (Long) -> SeedImporter.ResetResult = { error("No seed file available") },
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
        val rowsByFood = dao.getTagsFor(parentOf.keys).groupBy({ it.foodId }, { it.toRow() })
        return resolveTags(foodIds, parentOf, rowsByFood).filterValues { it.isNotEmpty() }
    }

    suspend fun getDishIngredientIds(dishId: Long): List<Long> = dao.getDishIngredientIds(dishId)

    // Library (SPEC 4.1)

    /** All foods of [kind], hidden ones included, alphabetically. */
    fun observeLibrary(kind: FoodKind): Flow<List<FoodEntity>> = dao.observeByKind(kind)

    fun observeFood(id: Long): Flow<FoodEntity?> = dao.observeFood(id)

    /** Units of logged foods can't change: meal items store a count or a size matching the unit. */
    suspend fun isUsedInMeals(id: Long): Boolean = dao.isUsedInMeals(id)

    suspend fun dishesUsing(ingredientId: Long): List<FoodEntity> = dao.getDishesUsing(ingredientId)

    /** Every ingredient's parent link, for [canBeParent]; load it once and check many candidates against it. */
    suspend fun parentMap(): Map<Long, Long?> = dao.getIngredientParentMap()

    /**
     * Whether [parentId] may become the parent of [childId] (null = a new food) in [parentMap]: no cycle,
     * and the hierarchy stays within [MaxFoodDepth] levels including the child's own descendants.
     */
    fun canBeParent(parentMap: Map<Long, Long?>, childId: Long?, parentId: Long): Boolean {
        val child = childId ?: NEW_FOOD_ID
        if (parentId == child) return false
        val changed = parentMap + (child to parentId)
        // levelOf also rejects a parent on or below a cycle, which parentProblems only reports through the cycle.
        return levelOf(changed, child) != null && parentProblems(changed).none { child in it.foods }
    }

    suspend fun canBeParent(childId: Long?, parentId: Long): Boolean = canBeParent(parentMap(), childId, parentId)

    /**
     * Creates ([IngredientDraft.id] 0) or updates an ingredient; [IngredientDraft.tags] are the tags it should end up with.
     * A built-in ingredient that follows the seed stores only its differences from the seed; saving it with another
     * parent detaches it from the seed (SPEC 4.4).
     */
    suspend fun saveIngredient(draft: IngredientDraft): Long {
        require(draft.name.isNotBlank()) { "Name is required" }
        requireUnitLabel(draft.unit, draft.unitLabel)
        val aliases = draft.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return db.withTransaction {
            val existing = draft.id.takeIf { it != 0L }?.let { dao.getFood(it) }
            draft.parentId?.let { require(canBeParent(existing?.id, it)) { "Invalid parent" } }
            val parentTags = draft.parentId?.let { getIngredientTags(it) }.orEmpty()
            val unit = if (existing != null && dao.isUsedInMeals(existing.id)) existing.unit else draft.unit
            val unitLabel = storedUnitLabel(unit, draft.unitLabel, fallback = existing?.unitLabel)
            val name = draft.name.trim()
            if (existing == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.INGREDIENT, name = name, aliases = aliases, unit = unit, unitLabel = unitLabel,
                        note = draft.note, parentId = draft.parentId,
                        portionGrams = draft.portionGrams, drink = draft.drink,
                    ),
                    tags = fullRowsFor(draft.tags, parentTags).map { it.toEntity() },
                    ingredientIds = emptyList(),
                )
            } else {
                val fields = existing.copy(
                    name = name, aliases = aliases, unit = unit, unitLabel = unitLabel, note = draft.note,
                    parentId = draft.parentId, portionGrams = draft.portionGrams, drink = draft.drink,
                    tagsDetached = existing.tagsDetached || (existing.builtIn && draft.parentId != existing.parentId),
                )
                val rowsBefore = dao.getTags(existing.id).map { it.toRow() }.toSet()
                if (fields.tagsFollowSeed()) {
                    val seedRows = rowsBefore.filter { it.source == TagSource.SEED }
                    dao.replaceTags(existing.id, TagSource.USER, userRowsFor(draft.tags, seedRows, parentTags).map { it.toEntity() })
                } else {
                    // Detached and user-created foods keep their full own list, relative to the (new) parent.
                    dao.replaceAllTags(existing.id, fullRowsFor(draft.tags, parentTags).map { it.toEntity() })
                }
                // Saving without a change doesn't mark a built-in food as edited (SPEC 4.1).
                val changed = fields != existing || dao.getTags(existing.id).map { it.toRow() }.toSet() != rowsBefore
                dao.updateFood(fields.copy(userModified = existing.userModified || (existing.builtIn && changed)))
                existing.id
            }
        }
    }

    /** Creates ([DishDraft.id] 0) or updates a dish. */
    suspend fun saveDish(draft: DishDraft): Long {
        require(draft.name.isNotBlank()) { "Name is required" }
        require(draft.ingredientIds.isNotEmpty()) { "A dish needs at least one ingredient" }
        requireUnitLabel(draft.unit, draft.unitLabel)
        val aliases = draft.aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val name = draft.name.trim()
        return db.withTransaction {
            val existing = draft.id.takeIf { it != 0L }?.let { dao.getFood(it) }
            if (existing == null) {
                createDish(name, draft.unit, draft.unitLabel, draft.ingredientIds, aliases, draft.drink, draft.portionGrams)
            } else {
                val unit = if (dao.isUsedInMeals(existing.id)) existing.unit else draft.unit
                val fields = existing.copy(
                    name = name, aliases = aliases, unit = unit,
                    unitLabel = storedUnitLabel(unit, draft.unitLabel, fallback = existing.unitLabel),
                    drink = draft.drink, portionGrams = draft.portionGrams.takeIf { draft.drink },
                )
                // Saving without a change doesn't mark a built-in dish as edited (SPEC 4.1).
                val recipeChanged = dao.getDishIngredientIds(existing.id).toSet() != draft.ingredientIds.toSet()
                dao.updateFood(fields.copy(userModified = existing.userModified || (existing.builtIn && (fields != existing || recipeChanged))))
                if (recipeChanged) dao.replaceDishIngredients(existing.id, draft.ingredientIds)
                existing.id
            }
        }
    }

    /** Hidden foods disappear from search but stay in past meals; nothing is ever deleted. */
    suspend fun setHidden(id: Long, hidden: Boolean) = dao.setHidden(id, hidden)

    /**
     * A user-owned copy named "… (Kopie)" with the same tags, parent or recipe. Returns its id.
     * The copy's own tags are the original's own tags as user rows, so it resolves to the same tags.
     */
    suspend fun duplicate(id: Long, copySuffix: String): Long = db.withTransaction {
        val food = dao.getFood(id) ?: error("Food $id not found")
        val name = "${food.name} $copySuffix"
        dao.insertFoodWithDetails(
            food = food.copy(
                id = 0, name = name, aliases = emptyList(), builtIn = false, userModified = false, hidden = false,
                seedKey = null, lastUsedAt = null, tagsDetached = false,
            ),
            tags = ownRows(dao.getTags(id).map { it.toRow() }).map { it.copy(source = TagSource.USER).toEntity() },
            ingredientIds = dao.getDishIngredientIds(id),
        )
    }

    /** "Reset to default" (SPEC 4.1), see [SeedImporter.reset]. */
    suspend fun resetToSeed(id: Long): SeedImporter.ResetResult = resetFood(id)

    fun suggestTags(name: String) = tagSuggester.suggest(name)

    suspend fun createIngredient(
        name: String,
        unit: FoodUnit,
        unitLabel: String?,
        tags: List<IngredientTag>,
        aliases: List<String> = emptyList(),
        note: String? = null,
    ): Long {
        requireUnitLabel(unit, unitLabel)
        return dao.insertFoodWithDetails(
            food = FoodEntity(
                kind = FoodKind.INGREDIENT,
                name = name.trim(),
                aliases = aliases,
                unit = unit,
                unitLabel = storedUnitLabel(unit, unitLabel),
                note = note,
                drink = isDrinkUnit(unit, unitLabel),
            ),
            tags = fullRowsFor(tags, parentTags = emptyList()).map { it.toEntity() },
            ingredientIds = emptyList(),
        )
    }

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
        requireUnitLabel(unit, unitLabel)
        return dao.insertFoodWithDetails(
            food = FoodEntity(
                kind = FoodKind.DISH,
                name = name.trim(),
                aliases = aliases,
                unit = unit,
                unitLabel = storedUnitLabel(unit, unitLabel),
                drink = drink,
                portionGrams = portionGrams.takeIf { drink },
            ),
            tags = emptyList(),
            ingredientIds = ingredientIds,
        )
    }
}

private fun requireUnitLabel(unit: FoodUnit, label: String?) =
    require(unit == FoodUnit.SIZE || !label.isNullOrBlank()) { "Count units need a label" }

/**
 * The label stored for a food with [unit]: for count units [label] as its built-in key ([unitLabelKey]), or [fallback]
 * when no label is given; sizes have none.
 */
private fun storedUnitLabel(unit: FoodUnit, label: String?, fallback: String? = null): String? =
    (label?.let(::unitLabelKey) ?: fallback).takeIf { unit == FoodUnit.COUNT }

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

/** Placeholder id of an unsaved food in a parent map: it has no children yet. */
private const val NEW_FOOD_ID = -1L
