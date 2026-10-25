package at.mtr.logmygut.data.seed

import androidx.room.withTransaction
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.data.db.FoodTagEntity
import at.mtr.logmygut.data.db.LogMyGutDatabase
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.domain.IngredientTag
import at.mtr.logmygut.domain.Threshold
import at.mtr.logmygut.domain.normalizeForSearch

/**
 * Brings the built-in foods in line with the seed file (SPEC 4), named in [language][import]. Safe to run on every
 * start and after a language change:
 * - seed entries missing from the database are added;
 * - built-in foods the user hasn't changed get the seed's current name, aliases, note, portion weight, drink flag,
 *   tags, parent or recipe;
 * - built-in foods the user changed keep everything except their name, which follows the language as long as the
 *   user didn't rename them;
 * - user-created foods are never touched.
 * Names are in the given language; the names and aliases in the other languages become extra aliases, so search
 * finds a food in any language. Units are never changed on existing foods, because logged meal items depend on them.
 */
class SeedImporter(private val db: LogMyGutDatabase) {

    data class Result(val inserted: Int, val updated: Int)

    suspend fun import(seed: SeedFile, language: String = "de"): Result = db.withTransaction {
        val dao = db.foodDao()
        val existing = dao.getSeededFoods().associateBy { it.seedKey!! }
        var inserted = 0
        val updated = mutableSetOf<String>()
        /** Seed keys whose rows follow the seed (new or unmodified), so their parent/recipe gets synced. */
        val managed = mutableSetOf<String>()

        for (ingredient in seed.ingredients) {
            val tags = ingredient.tags.map { it.toEntity() } + ingredient.removedTags.map { FoodTagEntity(0, it, removed = true) }
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
                        searchText = searchTextFor(text.name, text.aliases),
                    ),
                    tags = tags,
                    ingredientIds = emptyList(),
                )
                inserted++
                managed += ingredient.key
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(
                    name = text.name, aliases = text.aliases, note = text.note,
                    portionGrams = ingredient.grams, drink = ingredient.drink,
                ).withSearchText()
                val tagsChanged = dao.getTags(current.id).map { it.copy(foodId = 0) }.toSet() != tags.toSet()
                if (synced != current) dao.updateFood(synced)
                if (tagsChanged) dao.replaceTags(current.id, tags)
                if (synced != current || tagsChanged) updated += ingredient.key
                managed += ingredient.key
            } else if (current.builtIn) {
                if (renameToLanguage(current, ingredient, language)) updated += ingredient.key
            }
        }

        // Dishes and parent links after ingredients, so every referenced ingredient has an id.
        val idBySeedKey = dao.getSeedKeyIds().associate { it.seedKey to it.id }
        val parentOf = dao.getParentIds(idBySeedKey.values).associate { it.id to it.parentId }
        for (ingredient in seed.ingredients.filter { it.key in managed }) {
            val id = idBySeedKey.getValue(ingredient.key)
            val parentId = ingredient.parent?.let(idBySeedKey::getValue)
            if (parentOf[id] != parentId) {
                dao.setParent(id, parentId)
                if (ingredient.key in existing) updated += ingredient.key
            }
        }

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
                        searchText = searchTextFor(text.name, text.aliases),
                    ),
                    tags = emptyList(),
                    ingredientIds = ingredientIds,
                )
                inserted++
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(name = text.name, aliases = text.aliases, portionGrams = dish.grams, drink = dish.drink).withSearchText()
                val recipeChanged = dao.getDishIngredientIds(current.id).toSet() != ingredientIds.toSet()
                if (synced != current) dao.updateFood(synced)
                if (recipeChanged) dao.replaceDishIngredients(current.id, ingredientIds)
                if (synced != current || recipeChanged) updated += dish.key
            } else if (current.builtIn) {
                if (renameToLanguage(current, dish, language)) updated += dish.key
            }
        }
        Result(inserted, updated.size)
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
        db.foodDao().updateFood(current.copy(name = target, aliases = aliases).withSearchText())
        return true
    }
}

/** The food's texts in [language], with its names and aliases in the other languages added as aliases for search. */
internal fun SeedFood.searchableText(language: String): SeedText {
    val shown = text(language)
    val aliases = (shown.aliases + allNames())
        .filterNot { it.equals(shown.name, ignoreCase = true) }
        .distinctBy { it.lowercase() }
    return shown.copy(aliases = aliases)
}

private fun FoodEntity.withSearchText() = copy(searchText = searchTextFor(name, aliases))

/** Search column content: normalized name and aliases, separated so matches don't span entries. */
fun searchTextFor(name: String, aliases: List<String>): String =
    (listOf(name) + aliases).joinToString("|") { normalizeForSearch(it) }

internal fun IngredientTag.toEntity(foodId: Long = 0) = FoodTagEntity(
    foodId = foodId,
    tag = tag,
    minSize = (threshold as? Threshold.MinSize)?.size,
    minCount = (threshold as? Threshold.MinCount)?.n,
)
