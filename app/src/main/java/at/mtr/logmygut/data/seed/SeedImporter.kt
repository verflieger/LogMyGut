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
 * Brings the built-in foods in line with the seed file (SPEC 4). Safe to run on every start:
 * - seed entries missing from the database are added;
 * - built-in foods the user hasn't changed get the seed's current name, aliases, note, tags, parent or recipe;
 * - foods the user changed, and user-created foods, are never touched.
 * Units are never changed on existing foods, because logged meal items depend on them.
 */
class SeedImporter(private val db: LogMyGutDatabase) {

    data class Result(val inserted: Int, val updated: Int)

    suspend fun import(seed: SeedFile): Result = db.withTransaction {
        val dao = db.foodDao()
        val existing = dao.getSeededFoods().associateBy { it.seedKey!! }
        var inserted = 0
        val updated = mutableSetOf<String>()
        /** Seed keys whose rows follow the seed (new or unmodified), so their parent/recipe gets synced. */
        val managed = mutableSetOf<String>()

        for (ingredient in seed.ingredients) {
            val tags = ingredient.tags.map { it.toEntity() } + ingredient.removedTags.map { FoodTagEntity(0, it, removed = true) }
            val current = existing[ingredient.key]
            if (current == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.INGREDIENT,
                        name = ingredient.name,
                        aliases = ingredient.aliases,
                        unit = ingredient.unit,
                        unitLabel = ingredient.unitLabel,
                        note = ingredient.note,
                        builtIn = true,
                        seedKey = ingredient.key,
                        searchText = searchTextFor(ingredient.name, ingredient.aliases),
                    ),
                    tags = tags,
                    ingredientIds = emptyList(),
                )
                inserted++
                managed += ingredient.key
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(name = ingredient.name, aliases = ingredient.aliases, note = ingredient.note)
                    .withSearchText()
                val tagsChanged = dao.getTags(current.id).map { it.copy(foodId = 0) }.toSet() != tags.toSet()
                if (synced != current) dao.updateFood(synced)
                if (tagsChanged) dao.replaceTags(current.id, tags)
                if (synced != current || tagsChanged) updated += ingredient.key
                managed += ingredient.key
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
            val current = existing[dish.key]
            if (current == null) {
                dao.insertFoodWithDetails(
                    food = FoodEntity(
                        kind = FoodKind.DISH,
                        name = dish.name,
                        aliases = dish.aliases,
                        unit = dish.unit,
                        unitLabel = dish.unitLabel,
                        builtIn = true,
                        seedKey = dish.key,
                        searchText = searchTextFor(dish.name, dish.aliases),
                    ),
                    tags = emptyList(),
                    ingredientIds = ingredientIds,
                )
                inserted++
            } else if (current.builtIn && !current.userModified) {
                val synced = current.copy(name = dish.name, aliases = dish.aliases).withSearchText()
                val recipeChanged = dao.getDishIngredientIds(current.id).toSet() != ingredientIds.toSet()
                if (synced != current) dao.updateFood(synced)
                if (recipeChanged) dao.replaceDishIngredients(current.id, ingredientIds)
                if (synced != current || recipeChanged) updated += dish.key
            }
        }
        Result(inserted, updated.size)
    }
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
