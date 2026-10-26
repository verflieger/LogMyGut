package at.mtr.logmygut.data.seed

import androidx.room.withTransaction
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.data.db.tagsFollowSeed
import at.mtr.logmygut.data.db.toEntity
import at.mtr.logmygut.data.db.toRow
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.MaxFoodDepth
import at.mtr.logmygut.domain.TagRow
import at.mtr.logmygut.domain.TagSource
import at.mtr.logmygut.domain.fullRowsFor
import at.mtr.logmygut.domain.planSeedParents
import at.mtr.logmygut.domain.resolveTags

/**
 * Brings the built-in foods in line with the seed file (SPEC 4), named in [language][import]. Safe to run on every
 * start and after a language change:
 * - seed entries missing from the database are added;
 * - built-in foods the user hasn't changed get the seed's current name, aliases, note, portion weight, drink flag
 *   and recipe; built-in foods the user changed keep these, except their name, which follows the language as long
 *   as the user didn't rename them;
 * - built-in ingredients whose tags follow the seed (attached, see [tagsFollowSeed]) get the seed's tags as their
 *   seed rows and the seed's parent, even when the user changed them: the user's own rows stay and apply on top;
 * - such an ingredient is detached instead, keeping its resolved tags as its own, when the seed no longer has it or
 *   its seed parent isn't possible here (a cycle or more than [MaxFoodDepth] levels with the other foods);
 * - detached ingredients keep their tags and parent;
 * - user-created foods are never touched.
 * Names are in the given language; the names and aliases in the other languages become extra aliases, so search
 * finds a food in any language. Units are never changed on existing foods, because logged meal items depend on them.
 */
class SeedImporter(private val db: LogMyGutDatabase) {

    data class Result(val inserted: Int, val updated: Int)

    /** Outcome of [reset]: done, or why nothing was changed. */
    enum class ResetResult { RESET, NOT_IN_SEED, PARENT_IMPOSSIBLE }

    suspend fun import(seed: SeedFile, language: String = "de"): Result = db.withTransaction {
        val existing = db.foodDao().getSeededFoods().associateBy { it.seedKey!! }
        val updated = mutableSetOf<String>()
        // 1. New ingredients with their seed rows but no parent yet; the other fields of existing ones.
        var inserted = syncIngredientFields(seed, existing, language, updated)
        // 2. Parents and seed rows of the ingredients that follow the seed, now that every ingredient has an id.
        syncIngredientParents(seed, existing, updated)
        // 3. Dishes, whose recipes refer to the ingredients.
        inserted += syncDishes(seed, existing, language, updated)
        Result(inserted, updated.size)
    }

    /**
     * Restores the built-in food [foodId] to the seed (SPEC 4.1): drops the user's tag rows and changes, then imports.
     * Checked first, so nothing changes when the seed no longer has the food or the import would detach it again
     * because its seed parent isn't possible here.
     */
    suspend fun reset(seed: SeedFile, foodId: Long, language: String = "de"): ResetResult = db.withTransaction {
        val dao = db.foodDao()
        val food = dao.getFood(foodId)
        require(food != null && food.builtIn) { "Only built-in foods can be reset" }
        val seedFood = when (food.kind) {
            FoodKind.INGREDIENT -> seed.ingredients.firstOrNull { it.key == food.seedKey }
            FoodKind.DISH -> seed.dishes.firstOrNull { it.key == food.seedKey }
        } ?: return@withTransaction ResetResult.NOT_IN_SEED
        if (seedFood is SeedIngredient) {
            // The same plan as the import that follows, which sees this food attached at its current parent.
            val inputs = parentInputs(seed, dao.getSeededFoods().associateBy { it.seedKey!! })
            if (foodId in planSeedParents(inputs.currentParent, inputs.seedParent, inputs.attached + foodId).toDetach) {
                return@withTransaction ResetResult.PARENT_IMPOSSIBLE
            }
            // The seed rows right away, so the food never is without its tags during the import.
            dao.replaceAllTags(foodId, seedFood.seedRows().map { it.toEntity() })
        }
        dao.clearTagsDetached(foodId)
        dao.clearUserModified(foodId)
        import(seed, language)
        ResetResult.RESET
    }

    /** Inserts the seed's new ingredients (seed rows, no parent) and syncs the fields of existing ones; returns the number inserted. */
    private suspend fun syncIngredientFields(
        seed: SeedFile,
        existing: Map<String, FoodEntity>,
        language: String,
        updated: MutableSet<String>,
    ): Int {
        val dao = db.foodDao()
        var inserted = 0
        for (ingredient in seed.ingredients) {
            val text = ingredient.searchableText(language)
            val current = existing[ingredient.key]
            if (current == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.INGREDIENT,
                        name = text.name,
                        aliases = text.aliases,
                        unit = ingredient.unit,
                        unitLabel = ingredient.unitLabel,
                        note = text.note,
                        portionGrams = ingredient.grams,
                        drink = ingredient.drink,
                        builtIn = true,
                        seedKey = ingredient.key,
                    ),
                    tags = ingredient.seedRows().map { it.toEntity() },
                    ingredientIds = emptyList(),
                )
                inserted++
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(
                    name = text.name, aliases = text.aliases, note = text.note,
                    portionGrams = ingredient.grams, drink = ingredient.drink,
                )
                if (synced != current) {
                    dao.updateFood(synced)
                    updated += ingredient.key
                }
            } else if (current.builtIn) {
                if (renameToLanguage(current, ingredient, language)) updated += ingredient.key
            }
        }
        return inserted
    }

    /**
     * Moves the attached ingredients to their seed parent and gives them the seed's rows, or detaches them where
     * [planSeedParents] says so. All checks see the final parent map; detaches use the rows as they were.
     */
    private suspend fun syncIngredientParents(seed: SeedFile, existing: Map<String, FoodEntity>, updated: MutableSet<String>) {
        val dao = db.foodDao()
        val inputs = parentInputs(seed, existing)
        val plan = planSeedParents(inputs.currentParent, inputs.seedParent, inputs.attached)
        val keyById = inputs.idBySeedKey.entries.associate { (key, id) -> id to key }
        fun changed(id: Long) {
            if (id !in inputs.newIds) updated += keyById.getValue(id)
        }

        // 1. Detached foods keep their effective tags as their own. A new food's rows refer to its seed parent, and
        // new parents aren't linked yet either, so its tags are resolved along the target map with the seed parents.
        detach(plan.toDetach, inputs.newIds, inputs.currentParent, newRowParent = plan.target + inputs.seedParent.filterKeys { it in inputs.newIds })
        plan.toDetach.forEach(::changed)

        // 2. Seed rows of the foods that follow the seed, then 3. their parents.
        val following = inputs.attached - plan.toDetach
        val seedRows = seed.ingredients.associate { inputs.idBySeedKey.getValue(it.key) to it.seedRows() }
        for (id in following) {
            val rows = seedRows.getValue(id)
            if (dao.getTags(id).map { it.toRow() }.filter { it.source == TagSource.SEED }.toSet() != rows.toSet()) {
                dao.replaceTags(id, TagSource.SEED, rows.map { it.toEntity() })
                changed(id)
            }
        }
        for (id in following) {
            if (plan.target[id] != inputs.currentParent[id]) {
                dao.setParent(id, plan.target[id])
                changed(id)
            }
        }
    }

    /** Inserts the seed's new dishes and syncs the existing ones; returns the number inserted. */
    private suspend fun syncDishes(seed: SeedFile, existing: Map<String, FoodEntity>, language: String, updated: MutableSet<String>): Int {
        val dao = db.foodDao()
        val idBySeedKey = dao.getSeedKeyIds().associate { it.seedKey to it.id }
        var inserted = 0
        for (dish in seed.dishes) {
            val ingredientIds = dish.ingredients.map { idBySeedKey.getValue(it) }
            val text = dish.searchableText(language)
            val current = existing[dish.key]
            if (current == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.DISH,
                        name = text.name,
                        aliases = text.aliases,
                        unit = dish.unit,
                        unitLabel = dish.unitLabel,
                        portionGrams = dish.grams,
                        drink = dish.drink,
                        builtIn = true,
                        seedKey = dish.key,
                    ),
                    tags = emptyList(),
                    ingredientIds = ingredientIds,
                )
                inserted++
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(name = text.name, aliases = text.aliases, portionGrams = dish.grams, drink = dish.drink)
                val recipeChanged = dao.getDishIngredientIds(current.id).toSet() != ingredientIds.toSet()
                if (synced != current) dao.updateFood(synced)
                if (recipeChanged) dao.replaceDishIngredients(current.id, ingredientIds)
                if (synced != current || recipeChanged) updated += dish.key
            } else if (current.builtIn) {
                if (renameToLanguage(current, dish, language)) updated += dish.key
            }
        }
        return inserted
    }

    /** What [planSeedParents] needs, with each seed key's food id; [newIds] are the seed ingredients not in [existing]. */
    private class ParentInputs(
        val idBySeedKey: Map<String, Long>,
        val newIds: Set<Long>,
        val currentParent: Map<Long, Long?>,
        val seedParent: Map<Long, Long?>,
        val attached: Set<Long>,
    )

    /**
     * Collects the [ParentInputs] for [seed], with [existing] the seeded foods as they were before this import. A seed
     * ingredient not in the database yet (when [reset] checks ahead of the import) gets a placeholder id below 0 and,
     * like a just inserted one, no parent.
     */
    private suspend fun parentInputs(seed: SeedFile, existing: Map<String, FoodEntity>): ParentInputs {
        val dao = db.foodDao()
        val idBySeedKey = dao.getSeedKeyIds().associateTo(mutableMapOf()) { it.seedKey to it.id }
        val missing = seed.ingredients.map { it.key }.filter { it !in idBySeedKey }
        missing.forEachIndexed { i, key -> idBySeedKey[key] = -1L - i }
        val newIds = seed.ingredients.filter { it.key !in existing }.mapTo(mutableSetOf()) { idBySeedKey.getValue(it.key) }
        return ParentInputs(
            idBySeedKey = idBySeedKey,
            newIds = newIds,
            currentParent = dao.getIngredientParentMap() + missing.associate { idBySeedKey.getValue(it) to null },
            seedParent = seed.ingredients.associate { idBySeedKey.getValue(it.key) to it.parent?.let(idBySeedKey::getValue) },
            // New seed foods follow the seed; the others' flags are unchanged since [existing] was read.
            attached = existing.values.filter { it.tagsFollowSeed() }.mapTo(mutableSetOf()) { it.id } + newIds,
        )
    }

    /**
     * Detaches the ingredients [ids] from the seed without changing their effective tags (SPEC 4): these are worked
     * out from their stored rows under the parent the rows refer to, and stored as user rows relative to their
     * [currentParent]. For an existing food that is its current parent. A food [inserted in this import][newIds] has
     * no parent link yet; its rows refer to its seed parent, so it is resolved along [newRowParent].
     */
    private suspend fun detach(ids: Set<Long>, newIds: Set<Long>, currentParent: Map<Long, Long?>, newRowParent: Map<Long, Long?>) {
        if (ids.isEmpty()) return
        val dao = db.foodDao()
        val (new, old) = ids.partition { it in newIds }
        // A new food has no current parent, so only old ones need their parent's tags.
        val toResolve = old + old.mapNotNull { currentParent[it] }
        fun chain(id: Long, parentOf: Map<Long, Long?>) = generateSequence(id) { parentOf[it] }.take(MaxFoodDepth).toList()
        val chains = toResolve.flatMap { chain(it, currentParent) } + new.flatMap { chain(it, newRowParent) }
        val rows = dao.getTagsFor(chains.toSet()).groupBy({ it.foodId }, { it.toRow() })
        // All resolved before the first write, so one detached food's new rows don't affect another's.
        val resolved = resolveTags(toResolve, currentParent, rows) + resolveTags(new, newRowParent, rows)
        for (id in ids) {
            val parentTags = currentParent[id]?.let(resolved::getValue).orEmpty()
            dao.replaceAllTags(id, fullRowsFor(resolved.getValue(id), parentTags).map { it.toEntity() })
            dao.markTagsDetached(id)
        }
    }

    /**
     * A built-in food the user changed keeps its aliases, note and so on, but its name follows [language] as long as
     * it is still one of the seed's names (in any language, or the seed key): a name the user chose, even one of the
     * aliases, stays. The old name becomes an alias.
     */
    private suspend fun renameToLanguage(current: FoodEntity, food: SeedFood, language: String): Boolean {
        val target = food.text(language).name
        val seedNames = listOf(food.name, food.key) + food.translations.values.map { it.name }
        if (current.name == target || seedNames.none { it.equals(current.name, ignoreCase = true) }) return false
        val aliases = (current.aliases.filterNot { it.equals(target, ignoreCase = true) } + current.name).distinctBy { it.lowercase() }
        db.foodDao().updateFood(current.copy(name = target, aliases = aliases))
        return true
    }
}

/** The ingredient's own tags and removals as seed rows. */
private fun SeedIngredient.seedRows(): List<TagRow> =
    tags.map { TagRow(it.tag, it.threshold, removed = false, TagSource.SEED) } +
        removedTags.map { TagRow(it, threshold = null, removed = true, TagSource.SEED) }

/** The food's texts in [language], with its names and aliases in the other languages added as aliases for search. */
internal fun SeedFood.searchableText(language: String): SeedText {
    val shown = text(language)
    val aliases = (shown.aliases + allNames())
        .filterNot { it.equals(shown.name, ignoreCase = true) }
        .distinctBy { it.lowercase() }
    return shown.copy(aliases = aliases)
}
